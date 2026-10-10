package com.naeblis11.mealplanner.desktop.peers

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * An in-process network for FakeDiscovery (P6-R2: no test opens a socket for discovery). Each FakeDiscovery's
 * announcement is kept as the TXT map JmDNS would carry, and records added by hand join it, garbled ones too; a browse
 * decodes them all with PeerTxt, as JmdnsDiscovery does.
 */
class FakeNetwork {
    private val lock = Any()
    private val announced = LinkedHashMap<FakeDiscovery, Pair<String?, Map<String, String?>>>()
    private val added = mutableListOf<Pair<String?, Map<String, String?>>>()

    fun add(name: String?, txt: Map<String, String?>) {
        synchronized(lock) { added += (name to txt) }
    }

    fun add(record: PeerRecord) = add(record.name, PeerTxt.encode(record))

    fun clearAdded() {
        synchronized(lock) { added.clear() }
    }

    fun announce(by: FakeDiscovery, record: PeerRecord) {
        synchronized(lock) { announced[by] = record.name to PeerTxt.encode(record) }
    }

    fun leave(by: FakeDiscovery) {
        synchronized(lock) { announced.remove(by) }
    }

    fun seen(): List<PeerRecord> = synchronized(lock) { announced.values + added }.mapNotNull { (name, txt) -> PeerTxt.decode(name, txt) }
}

/**
 * Discovery without sockets. [available] false is a PC with no network (announce says off); a test may set it later,
 * as a network that comes up. Each call is noted in [events]; a browse waits on [gate] while it is set (up to 5 s, or
 * until interrupted), and close() releases it unless [releaseOnClose] is false. After close a browse finds nothing,
 * unless [answerAfterClose] is set.
 */
class FakeDiscovery(
    private val network: FakeNetwork = FakeNetwork(),
    available: Boolean = true,
    val events: MutableList<String> = CopyOnWriteArrayList(),
) : PeerDiscovery {
    @Volatile
    var available: Boolean = available

    @Volatile
    var announced: Pair<PeerRecord, Int>? = null
        private set

    /** Every announce() call, the ones that said off too. */
    val announces = AtomicInteger()

    val browses = AtomicInteger()

    @Volatile
    var releaseOnClose: Boolean = true

    @Volatile
    var answerAfterClose: Boolean = false

    /** Every close() call, the repeated ones too; only the first acts. */
    val closes = AtomicInteger()

    /** Counted down when the first browse starts. */
    val browsing = CountDownLatch(1)

    @Volatile
    var gate: CountDownLatch? = null

    /** Runs inside the first close(), before this PC leaves the network: to see what is still open then. */
    @Volatile
    var onClose: () -> Unit = {}

    override fun announce(record: PeerRecord, port: Int): Boolean {
        events += "announce"
        announces.incrementAndGet()
        if (!available) return false
        announced = record to port
        network.announce(this, record)
        return true
    }

    override fun browse(millis: Long): List<PeerRecord> {
        events += "browse"
        browses.incrementAndGet()
        browsing.countDown()
        gate?.await(5, TimeUnit.SECONDS)
        if ((closes.get() > 0 && !answerAfterClose) || !available) return emptyList()
        return network.seen()
    }

    override fun close() {
        if (closes.incrementAndGet() > 1) return
        events += "close"
        onClose()
        network.leave(this)
        if (releaseOnClose) gate?.countDown()
    }
}
