package com.naeblis11.mealplanner.desktop.peers

import java.net.Inet4Address
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/**
 * Discovery over mDNS with JmDNS (P6-R1, P6-R5): one JmDNS per interface [addresses] gives (PeerInterfaces.pick), each
 * announcing this PC as PeerTxt.SERVICE_TYPE and listening for the others.
 * - JmDNS.create, registerService and JmDNS.close run on [workers], daemon threads. The threads JmDNS starts for
 *   itself are not all daemon threads: its SocketListener is one, but its listener executor's threads are non-daemon
 *   whoever creates them (3.6.3 makes them with Executors.defaultThreadFactory). So these threads don't end the
 *   process: Quit ends it through the app's exit path (P6-R5).
 * - registerService returns before the announce finishes (3.6.3 starts its prober and returns), so the others see this
 *   PC a few seconds after [announce] returns.
 * - [announce] is on only when some interface's registerService worked; one that failed is closed at once.
 * - A start that outlives [startMillis] keeps going in the background, and [close] still stops it. If [announce] has
 *   said off, a start that finishes later closes itself and never announces, so off is truly off. A later [announce]
 *   (PeerWatch tries again while off, P6-T4a) starts afresh; a start left over from an earlier one still closes itself.
 * - [browse] waits on a latch that [close] releases, so a look under way never holds up the app's close. Its listener
 *   is a [BrowseListener], which bounds what a crowded or hostile network can cost.
 * - [close] closes each JmDNS in its own worker task, so every interface starts its goodbye at once, and waits at most
 *   [closeMillis] for them all (JmDNS.close can take about 5 s); the rest finish on daemon workers.
 * - P6-R11: the log gets only this PC's own addresses, exception class names and the number of goodbyes still going
 *   at close, never a peer's data; a peer's address and port are never used.
 * Each JmDNS has the host name `mealplanner-<first 8 of the instance id>-<n>`, so it never claims the PC's own
 * `<name>.local`, which Windows answers for itself; the service instance name is the PC's name.
 */
class JmdnsDiscovery(
    private val addresses: () -> List<Inet4Address> = { PeerInterfaces.pick(PeerInterfaces.system()) },
    private val startMillis: Long = START_MILLIS,
    private val closeMillis: Long = CLOSE_MILLIS,
    private val log: (String) -> Unit = { System.err.println(it) },
    // One interface's responder: JmDNS.create in the app; tests pass a fake, so no socket ever opens (P6-R2).
    private val open: (Inet4Address, String) -> Responder = { address, host -> JmdnsResponder(JmDNS.create(address, host)) },
) : PeerDiscovery {
    private val workers: ExecutorService = Executors.newCachedThreadPool { task -> Thread(task, "meal-planner-peers").apply { isDaemon = true } }
    private val lock = Any()
    private val running = mutableListOf<Responder>() // guarded by lock
    private var isClosed = false // guarded by lock
    private var attempt = 0 // guarded by lock: the announce under way; a start from an earlier one closes itself
    private var saidOff = false // guarded by lock: this attempt's announce returned false, so a late start closes itself
    private val closedSignal = CountDownLatch(1)

    override fun announce(record: PeerRecord, port: Int): Boolean {
        val current = synchronized(lock) {
            if (isClosed) return false
            saidOff = false
            ++attempt
        }
        val chosen = try {
            addresses()
        } catch (e: Exception) {
            log("Meal Planner: couldn't list this PC's network interfaces: ${e.javaClass.simpleName}")
            emptyList()
        }
        if (chosen.isEmpty()) return false
        val starts = try {
            chosen.mapIndexed { n, address ->
                workers.submit(Callable { startOn(current, address, "mealplanner-${record.instanceId.take(8)}-$n", record, port) })
            }
        } catch (e: RejectedExecutionException) {
            return false // Closed meanwhile.
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(startMillis)
        var any = false
        for (start in starts) {
            val left = (deadline - System.nanoTime()).coerceAtLeast(0L)
            try {
                if (start.get(left, TimeUnit.NANOSECONDS)) any = true
            } catch (e: TimeoutException) {
                // Still starting: it keeps going, and close() stops it.
            } catch (e: ExecutionException) {
                log("Meal Planner: announcing this PC failed: ${e.cause?.javaClass?.simpleName}")
            }
        }
        // Under the lock startOn keeps a JmDNS under, so a start that finishes after this answer sees it.
        return synchronized(lock) {
            val on = any || running.isNotEmpty()
            if (!on) saidOff = true
            on
        }
    }

    // On a worker. A JmDNS is kept (for browse and close) only once its announce has worked, so on means announced; one
    // whose register fails is closed here. One that finishes after close(), after its announce() said off, or after a
    // later announce() began is closed here too, so it doesn't stay on the network.
    private fun startOn(from: Int, address: Inet4Address, host: String, record: PeerRecord, port: Int): Boolean {
        fun wanted() = !isClosed && from == attempt && !saidOff
        val dns = try {
            open(address, host)
        } catch (e: Exception) {
            log("Meal Planner: couldn't look for other Meal Planner PCs on ${address.hostAddress}: ${e.javaClass.simpleName}")
            return false
        }
        // Not wanted already: close it without announcing.
        if (!synchronized(lock) { wanted() }) {
            closeQuietly(dns)
            return false
        }
        try {
            dns.register(serviceInfo(record, port))
        } catch (e: Exception) {
            log("Meal Planner: announcing this PC on ${address.hostAddress} failed: ${e.javaClass.simpleName}")
            closeQuietly(dns)
            return false
        }
        val kept = synchronized(lock) {
            val keep = wanted()
            if (keep) running += dns
            keep
        }
        if (!kept) closeQuietly(dns)
        return kept
    }

    override fun browse(millis: Long): List<PeerRecord> {
        val instances = synchronized(lock) { if (isClosed) emptyList() else running.toList() }
        if (instances.isEmpty()) return emptyList()
        val listener = BrowseListener(closed = { closedSignal.count == 0L }) { event ->
            event.dns.requestServiceInfo(event.type, event.name, 1L)
        }
        for (dns in instances) {
            try {
                dns.addServiceListener(PeerTxt.SERVICE_TYPE, listener)
            } catch (e: Exception) {
                // Closed meanwhile: the others still look.
            }
        }
        try {
            closedSignal.await(millis, TimeUnit.MILLISECONDS)
        } finally {
            listener.finish()
            for (dns in instances) {
                try {
                    dns.removeServiceListener(PeerTxt.SERVICE_TYPE, listener)
                } catch (e: Exception) {
                    // Closed meanwhile.
                }
            }
        }
        return listener.finish()
    }

    override fun close() {
        val toClose = synchronized(lock) {
            if (isClosed) return
            isClosed = true
            running.toList().also { running.clear() }
        }
        closedSignal.countDown()
        // One task per JmDNS, so a slow goodbye on one interface never holds up the others' within the bound.
        val closings = toClose.map { dns -> workers.submit { closeQuietly(dns) } }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(closeMillis)
        var late = 0
        for ((n, closing) in closings.withIndex()) {
            val left = (deadline - System.nanoTime()).coerceAtLeast(0L)
            try {
                closing.get(left, TimeUnit.NANOSECONDS)
            } catch (e: TimeoutException) {
                late++
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                late += closings.size - n
                break
            } catch (e: ExecutionException) {
                // closeQuietly throws nothing.
            }
        }
        if (late > 0) {
            log("Meal Planner: $late of ${closings.size} network goodbyes still going after $closeMillis ms; they finish in the background.")
        }
        workers.shutdown()
    }

    companion object {
        const val START_MILLIS = 10_000L
        const val CLOSE_MILLIS = 1_000L

        /** This PC's service: the PC's name as the instance name, the server's port, and the six TXT keys. */
        fun serviceInfo(record: PeerRecord, port: Int): ServiceInfo =
            ServiceInfo.create(PeerTxt.SERVICE_TYPE, record.name, port, 0, 0, PeerTxt.encode(record))

        /**
         * A peer's record from what JmDNS resolved; null when it isn't one, including a TXT record with more than
         * PeerTxt.MAX_TXT_KEYS keys (P6-R11). Never throws: it runs on JmDNS's threads, on untrusted data.
         */
        fun decode(info: ServiceInfo): PeerRecord? =
            try {
                val keys = info.propertyNames
                if (keys == null) {
                    null
                } else {
                    val txt = HashMap<String, String?>()
                    var tooMany = false
                    while (keys.hasMoreElements()) {
                        if (txt.size == PeerTxt.MAX_TXT_KEYS) {
                            tooMany = true
                            break
                        }
                        val key = keys.nextElement() ?: continue
                        txt[key] = info.getPropertyString(key)
                    }
                    if (tooMany) null else PeerTxt.decode(info.name, txt)
                }
            } catch (e: RuntimeException) {
                null
            }

        private fun closeQuietly(dns: Responder) {
            try {
                dns.close()
            } catch (e: Exception) {
                // Going anyway.
            }
        }
    }
}

/** One interface's mDNS responder, behind a seam so JmdnsDiscovery's bookkeeping is tested without a socket. */
interface Responder {
    /** Announces [info]; throws when it can't. */
    fun register(info: ServiceInfo)

    fun addServiceListener(type: String, listener: ServiceListener)

    fun removeServiceListener(type: String, listener: ServiceListener)

    /** Says goodbye and stops; may take a few seconds. */
    fun close()
}

/** The app's responder: one JmDNS. */
private class JmdnsResponder(private val dns: JmDNS) : Responder {
    override fun register(info: ServiceInfo) = dns.registerService(info)

    override fun addServiceListener(type: String, listener: ServiceListener) = dns.addServiceListener(type, listener)

    override fun removeServiceListener(type: String, listener: ServiceListener) = dns.removeServiceListener(type, listener)

    override fun close() = dns.close()
}

/**
 * One browse's listener (P6-R11), kept apart from JmDNS so a test can drive it by hand. JmDNS calls it on each
 * instance's own listener thread, and addServiceListener replays the records it already holds on the browsing thread,
 * so several threads may call at once.
 * - A record that decodes is kept, at most MAX_PEERS + 1 of them, first come first kept. Nothing reserves a place for
 *   this PC's own record, so on a crowded network it may be among those left out.
 * - A name whose record doesn't decode yet is asked for with [request] (requestServiceInfo, which in JmDNS 3.6.3 can
 *   wait up to about 200 ms on whichever thread called, whatever the 1 ms asked for), at most MAX_REQUESTS times per
 *   browse, and never once [closed] says close has begun. So a flood of names that never decode costs at most about
 *   MAX_REQUESTS x 200 ms of JmDNS's threads, however many names there are.
 * - After [finish], every callback returns at once, so callbacks still queued on JmDNS's threads cost nothing.
 */
internal class BrowseListener(
    private val closed: () -> Boolean,
    private val request: (ServiceEvent) -> Unit,
) : ServiceListener {
    private val seen = LinkedHashMap<String, PeerRecord>() // guarded by itself
    private val requested = AtomicInteger()

    @Volatile
    private var done = false

    /** Ends this browse: later callbacks do nothing. Returns the records kept, in the order first seen. */
    fun finish(): List<PeerRecord> {
        done = true
        return synchronized(seen) { seen.values.toList() }
    }

    override fun serviceAdded(event: ServiceEvent) {
        if (done || keep(event.info) || full() || closed()) return
        if (requested.incrementAndGet() > MAX_REQUESTS) return
        // No TXT record yet: ask for it, and it comes with serviceResolved.
        try {
            request(event)
        } catch (e: Exception) {
            // Closed meanwhile.
        }
    }

    override fun serviceRemoved(event: ServiceEvent) = Unit

    override fun serviceResolved(event: ServiceEvent) {
        if (!done) keep(event.info)
    }

    private fun full(): Boolean = synchronized(seen) { seen.size >= MAX_KEPT }

    // True when the record decoded, kept or not.
    private fun keep(info: ServiceInfo?): Boolean {
        if (info == null) return false
        val record = JmdnsDiscovery.decode(info) ?: return false
        synchronized(seen) {
            if (!done && seen.size < MAX_KEPT) seen.putIfAbsent(record.instanceId, record)
        }
        return true
    }

    companion object {
        /** Records one browse keeps: MAX_PEERS others plus one more, first come first kept. */
        const val MAX_KEPT = PeerTxt.MAX_PEERS + 1

        /** requestServiceInfo calls per browse: as many as the records it can keep. */
        const val MAX_REQUESTS = PeerTxt.MAX_PEERS + 1
    }
}
