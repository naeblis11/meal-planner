package com.naeblis11.mealplanner.desktop.sync

import com.naeblis11.mealplanner.desktop.peers.PeerInterfaces
import com.naeblis11.mealplanner.desktop.peers.PeerTxt
import com.naeblis11.mealplanner.sync.FoundPc
import com.naeblis11.mealplanner.sync.PcFinder
import com.naeblis11.mealplanner.sync.PcRecords
import java.net.Inet4Address
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/** One service a look resolved, as plain values: its instance name, its addresses and its TXT record. */
data class ResolvedService(val name: String?, val addresses: List<String?>, val txt: Map<String, ByteArray?>)

/**
 * How a PC finds the household's main PC (LAN sync stage 4a, R5): the phone's NsdPcFinder, for the desktop. On each home
 * interface ([addresses], PeerInterfaces.pick, so never Hyper-V, WSL, Docker or a VPN) [browse] lists
 * `_mealplanner._tcp.local.` for up to the timeout, all interfaces at once; each record goes through the phone's
 * PcRecords.foundPc, so only a main PC offering its sync port at an IPv4 address is kept, a client or garbled record never.
 * This PC's own record ([ownId], its PeerIdentity instance id) is left out, and so is a second record for an address
 * already found. A look that fails on one interface leaves the others; [find] never throws. P6-R11 holds: nothing found
 * is logged.
 */
class JmdnsPcFinder(
    private val ownId: suspend () -> String? = { null },
    private val addresses: () -> List<Inet4Address> = { PeerInterfaces.pick(PeerInterfaces.system()) },
    private val browse: (Inet4Address, Long) -> List<ResolvedService> = ::jmdnsBrowse,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PcFinder {
    override suspend fun find(timeoutMillis: Long): List<FoundPc> = withContext(io) {
        try {
            val own = ownId()
            val chosen = addresses()
            val seen = withTimeoutOrNull(timeoutMillis + SLACK_MILLIS) {
                coroutineScope {
                    chosen.map { address ->
                        async {
                            try {
                                runInterruptible { browse(address, timeoutMillis) }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                emptyList()
                            }
                        }
                    }.awaitAll()
                }
            } ?: emptyList()
            seen.flatten()
                .filter { own == null || it.txt["id"]?.toString(Charsets.UTF_8) != own }
                .mapNotNull { PcRecords.foundPc(it.name, PcRecords.firstIpv4(it.addresses), it.txt) }
                .distinctBy { it.host }
                .take(PeerTxt.MAX_PEERS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        /** How much longer than the look the whole find may take: JmDNS's create and resolve. */
        const val SLACK_MILLIS = 2_000L

        /**
         * One look on [address]: a JmDNS of its own (a host name of its own, so it claims nothing), listing the service
         * type for [millis], then closed on a daemon thread so its goodbye never holds up the answer. A record with more
         * than PeerTxt.MAX_TXT_KEYS keys is skipped, as JmdnsDiscovery does.
         */
        fun jmdnsBrowse(address: Inet4Address, millis: Long): List<ResolvedService> {
            val dns = JmDNS.create(address, "mealplanner-find-${UUID.randomUUID().toString().take(8)}")
            try {
                return dns.list(PeerTxt.SERVICE_TYPE, millis).take(PeerTxt.MAX_PEERS + 1).mapNotNull(::resolved)
            } finally {
                Thread({ runCatching { dns.close() } }, "meal-planner-find").apply { isDaemon = true }.start()
            }
        }

        /** [info] as plain values; null when its TXT record has too many keys or can't be read. Never throws. */
        fun resolved(info: ServiceInfo): ResolvedService? =
            try {
                val keys = info.propertyNames
                val txt = HashMap<String, ByteArray?>()
                var tooMany = false
                while (keys != null && keys.hasMoreElements()) {
                    if (txt.size == PeerTxt.MAX_TXT_KEYS) {
                        tooMany = true
                        break
                    }
                    val key = keys.nextElement() ?: continue
                    txt[key] = info.getPropertyBytes(key)
                }
                if (tooMany) null else ResolvedService(info.name, info.inet4Addresses.map { it.hostAddress }, txt)
            } catch (e: RuntimeException) {
                null
            }
    }
}
