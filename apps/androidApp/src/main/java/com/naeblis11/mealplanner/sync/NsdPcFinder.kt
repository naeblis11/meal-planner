package com.naeblis11.mealplanner.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The phone's look for Meal Planner PCs (LAN sync stage 2c, D2):
 * - The whole look, start wait and resolves included, is bound by one deadline of the time asked; what was resolved by
 *   then is returned. It browses PcRecords.SERVICE_TYPE with Android's NsdManager for the first part (BROWSE_SHARE).
 * - It then resolves what it saw one at a time, each for at most RESOLVE_MILLIS: before Android 14 a second resolve
 *   while one runs is refused. On API 26-33 a timed-out resolve can't be cancelled, so the ones after it are refused
 *   until it ends.
 * - It keeps what PcRecords accepts: an IPv4 address (on API 34+ the first IPv4 of all the service's addresses, since a
 *   PC may list a link-local IPv6 one first) and a `sync` port.
 * Android's own mDNS does the network work: this file opens no socket and imports nothing from java.net
 * (UpdateNetworkCodeTest). NSD needs no runtime permission at targetSdk 35. It never throws: with no network it finds
 * nothing. The manager is looked up only when asked, so making this does nothing.
 */
class NsdPcFinder(context: Context) : PcFinder {
    private val app = context.applicationContext

    override suspend fun find(timeoutMillis: Long): List<FoundPc> {
        val nsd = app.getSystemService(NsdManager::class.java) ?: return emptyList()
        val found = CopyOnWriteArrayList<FoundPc>()
        withTimeoutOrNull(timeoutMillis) {
            for (service in browse(nsd, (timeoutMillis * BROWSE_SHARE).toLong()).take(MAX_SERVICES)) {
                val resolved = withTimeoutOrNull(RESOLVE_MILLIS) { resolve(nsd, service) } ?: continue
                val host = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    PcRecords.firstIpv4(resolved.hostAddresses.map { it.hostAddress })
                } else {
                    @Suppress("DEPRECATION")
                    resolved.host?.hostAddress
                }
                val pc = PcRecords.foundPc(resolved.serviceName, host, resolved.attributes) ?: continue
                if (found.none { it.host == pc.host && it.port == pc.port }) found += pc
            }
        }
        return found.toList()
    }

    private suspend fun browse(nsd: NsdManager, millis: Long): List<NsdServiceInfo> {
        val seen = CopyOnWriteArrayList<NsdServiceInfo>()
        val started = CompletableDeferred<Boolean>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                started.complete(false)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onDiscoveryStarted(serviceType: String) {
                started.complete(true)
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                if (seen.none { it.serviceName == info.serviceName }) seen += info
            }

            override fun onServiceLost(info: NsdServiceInfo) = Unit
        }
        try {
            nsd.discoverServices(PcRecords.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: RuntimeException) {
            return emptyList()
        }
        try {
            // The start wait counts in the browse time, and the caller's deadline covers both.
            val begun = System.nanoTime()
            if (withTimeoutOrNull(millis) { started.await() } != true) return emptyList()
            delay(millis - (System.nanoTime() - begun) / 1_000_000)
        } finally {
            try {
                nsd.stopServiceDiscovery(listener)
            } catch (e: RuntimeException) {
                // Never started (or already stopped): nothing to stop.
            }
        }
        return seen.toList()
    }

    // resolveService is deprecated from API 34 (registerServiceInfoCallback); it still works there, one at a time.
    @Suppress("DEPRECATION")
    private suspend fun resolve(nsd: NsdManager, service: NsdServiceInfo): NsdServiceInfo? = suspendCancellableCoroutine { done ->
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                if (done.isActive) done.resume(null)
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                if (done.isActive) done.resume(info)
            }
        }
        try {
            nsd.resolveService(service, listener)
        } catch (e: RuntimeException) {
            done.resume(null)
            return@suspendCancellableCoroutine
        }
        done.invokeOnCancellation {
            // Android 14+ can stop a resolve still running, so the next one isn't refused; before that it ends by itself.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    nsd.stopServiceResolution(listener)
                } catch (e: RuntimeException) {
                    // Already finished.
                }
            }
        }
    }

    private companion object {
        const val RESOLVE_MILLIS = 3_000L
        const val MAX_SERVICES = 8
        const val BROWSE_SHARE = 0.6
    }
}
