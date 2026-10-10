package com.naeblis11.mealplanner.desktop.peers

import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P6-R2: JmdnsDiscovery's pure parts. No test opens a socket for discovery: nothing here creates a JmDNS. The tests
 * with addresses pass a fake opener (FakeResponder), so those addresses are never bound.
 */
class JmdnsDiscoveryTest {
    private val record = PeerRecord("KITCHEN", "0123456789abcdef", 1_700_000_000_000L, "0123456789abcdef0123456789abcdef", "1.0")

    @Test
    fun aRecordSurvivesJmdnsOwnTxtEncoding() {
        val info = JmdnsDiscovery.serviceInfo(record, 5055)
        assertEquals("KITCHEN", info.name)
        assertEquals(5055, info.port)
        assertEquals(record, JmdnsDiscovery.decode(info))
    }

    @Test
    fun aRecordMissingAKeyIsIgnored() {
        val info = ServiceInfo.create(PeerTxt.SERVICE_TYPE, "KITCHEN", 5000, 0, 0, PeerTxt.encode(record) - "hh")
        assertNull(JmdnsDiscovery.decode(info))
    }

    // P6-R11: a TXT record with more than MAX_TXT_KEYS keys is not a Meal Planner PC's, even with the six it needs.
    @Test
    fun aRecordWithMoreThanSixteenTxtKeysIsIgnored() {
        fun withExtra(count: Int) = PeerTxt.encode(record) + (1..count).associate { "x$it" to "y" }
        val atTheBound = ServiceInfo.create(PeerTxt.SERVICE_TYPE, "KITCHEN", 5000, 0, 0, withExtra(PeerTxt.MAX_TXT_KEYS - 6))
        assertEquals(record, JmdnsDiscovery.decode(atTheBound))
        val overTheBound = ServiceInfo.create(PeerTxt.SERVICE_TYPE, "KITCHEN", 5000, 0, 0, withExtra(PeerTxt.MAX_TXT_KEYS - 5))
        assertNull(JmdnsDiscovery.decode(overTheBound))
    }

    @Test
    fun withNoInterfaceDiscoveryIsOffAndClosesOnce() {
        val logs = mutableListOf<String>()
        val listed = AtomicInteger()
        val discovery = JmdnsDiscovery(addresses = { listed.incrementAndGet(); emptyList() }, log = { logs += it })
        assertFalse(discovery.announce(record, 5055))
        assertEquals(1, listed.get())
        val started = System.nanoTime()
        assertEquals(emptyList<PeerRecord>(), discovery.browse(5_000))
        assertTrue(System.nanoTime() - started < 1_000_000_000L)
        discovery.close()
        discovery.close()
        // Once closed, announce doesn't even list the interfaces: nothing can start.
        assertFalse(discovery.announce(record, 5055))
        assertEquals(1, listed.get())
        assertEquals(emptyList<PeerRecord>(), discovery.browse(5_000))
        assertEquals(emptyList<String>(), logs)
    }

    // P6-T4a: PeerWatch tries again while off, so an announce that said off doesn't stop a later one from looking.
    @Test
    fun anAnnounceAfterOffLooksAtTheInterfacesAgain() {
        val listed = AtomicInteger()
        val discovery = JmdnsDiscovery(addresses = { listed.incrementAndGet(); emptyList() }, log = {})
        assertFalse(discovery.announce(record, 5055))
        assertFalse(discovery.announce(record, 5055))
        assertEquals(2, listed.get())
        discovery.close()
    }

    // A responder that opens nothing: JmdnsDiscovery's bookkeeping around register and close, without JmDNS or a socket.
    private class FakeResponder(private val registerFails: Boolean) : Responder {
        @Volatile var registered = 0
        @Volatile var closed = 0

        override fun register(info: ServiceInfo) {
            if (registerFails) throw java.io.IOException("register failed")
            registered++
        }

        override fun addServiceListener(type: String, listener: ServiceListener) = Unit

        override fun removeServiceListener(type: String, listener: ServiceListener) = Unit

        override fun close() {
            closed++
        }
    }

    // TEST-NET-1 addresses, made from bytes: nothing is looked up, and nothing binds them (the opener is fake).
    private fun testNet(last: Int): Inet4Address = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, last.toByte())) as Inet4Address

    @Test
    fun anAnnounceWhoseRegisterFailsIsOffAndClosesWhatItOpened() {
        val opened = CopyOnWriteArrayList<FakeResponder>()
        val logs = CopyOnWriteArrayList<String>()
        val discovery = JmdnsDiscovery(
            addresses = { listOf(testNet(1)) },
            log = { logs += it },
            open = { _, _ -> FakeResponder(registerFails = true).also { opened += it } },
        )
        assertFalse("on, though nothing was announced", discovery.announce(record, 5055))
        assertEquals(1, opened.single().closed)
        assertTrue(logs.toString(), logs.any { it.endsWith("failed: IOException") })
        // Nothing kept: the browse has nothing to look with, and close has nothing left to close.
        assertEquals(emptyList<PeerRecord>(), discovery.browse(5_000))
        discovery.close()
        assertEquals(1, opened.single().closed)
    }

    @Test
    fun anAnnounceIsOnWhenOneInterfaceRegistersAndKeepsOnlyThatOne() {
        val opened = CopyOnWriteArrayList<FakeResponder>()
        val discovery = JmdnsDiscovery(
            addresses = { listOf(testNet(1), testNet(2)) },
            log = {},
            open = { address, _ -> FakeResponder(registerFails = address == testNet(1)).also { opened += it } },
        )
        assertTrue(discovery.announce(record, 5055))
        val failed = opened.single { it.registered == 0 }
        val working = opened.single { it.registered == 1 }
        assertEquals(1, failed.closed)
        assertEquals(0, working.closed)
        discovery.close()
        assertEquals(1, working.closed)
        assertEquals(1, failed.closed)
    }

    // A browse's listener, driven by hand: no JmDNS, no socket. Each request stands for one requestServiceInfo call.
    private fun event(name: String, info: ServiceInfo?): ServiceEvent = object : ServiceEvent(Any()) {
        override fun getDNS(): JmDNS? = null
        override fun getType(): String = PeerTxt.SERVICE_TYPE
        override fun getName(): String = name
        override fun getInfo(): ServiceInfo? = info
    }

    private fun junk(n: Int) = event("junk$n", ServiceInfo.create(PeerTxt.SERVICE_TYPE, "junk$n", 5000, ""))

    private fun peer(n: Int): ServiceEvent {
        val other = record.copy(name = "PC$n", instanceId = "%032x".format(n))
        return event(other.name, JmdnsDiscovery.serviceInfo(other, 5000))
    }

    @Test
    fun aFloodOfNamesThatNeverDecodeAsksForAtMostMaxPeersPlusOne() {
        val requests = AtomicInteger()
        val listener = BrowseListener(closed = { false }, request = { requests.incrementAndGet() })
        repeat(1_000) { listener.serviceAdded(junk(it)) }
        assertEquals(PeerTxt.MAX_PEERS + 1, requests.get())
        assertEquals(emptyList<PeerRecord>(), listener.finish())
    }

    @Test
    fun recordsThatDecodeAreKeptWithoutAskingUpToMaxPeersPlusOne() {
        val requests = AtomicInteger()
        val listener = BrowseListener(closed = { false }, request = { requests.incrementAndGet() })
        (1..40).forEach { listener.serviceAdded(peer(it)) }
        assertEquals(0, requests.get())
        assertEquals((1..PeerTxt.MAX_PEERS + 1).map { "PC$it" }, listener.finish().map { it.name })
    }

    @Test
    fun onceClosedNothingIsAskedFor() {
        val requests = AtomicInteger()
        val listener = BrowseListener(closed = { true }, request = { requests.incrementAndGet() })
        repeat(10) { listener.serviceAdded(junk(it)) }
        assertEquals(0, requests.get())
    }

    @Test
    fun afterTheBrowseEndsLeftoverCallbacksReturnAtOnce() {
        val requests = AtomicInteger()
        val listener = BrowseListener(closed = { false }, request = { requests.incrementAndGet() })
        listener.serviceResolved(peer(1))
        assertEquals(listOf("PC1"), listener.finish().map { it.name })
        repeat(10) { listener.serviceAdded(junk(it)) }
        listener.serviceAdded(peer(2))
        listener.serviceResolved(peer(3))
        assertEquals(0, requests.get())
        assertEquals(listOf("PC1"), listener.finish().map { it.name })
    }
}
