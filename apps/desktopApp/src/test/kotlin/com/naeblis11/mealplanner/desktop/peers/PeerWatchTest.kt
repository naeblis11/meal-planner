package com.naeblis11.mealplanner.desktop.peers

import com.naeblis11.mealplanner.desktop.server.eventually
import com.naeblis11.mealplanner.settings.PeerNotice
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P6-R6, P6-R7: announce, look at start and on demand, and the yield, against FakeDiscovery. */
class PeerWatchTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val network = FakeNetwork()
    private val changes = AtomicInteger()

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun record(name: String, hash: Char, since: Long, n: Int) =
        PeerRecord(name, hash.toString().repeat(16), since, "%032x".format(n), "1.0")

    private val me = record("THIS-PC", '5', 500L, 0)
    private val older = record("DEN", '9', 100L, 1)

    private fun watch(
        discovery: PeerDiscovery,
        own: PeerRecord = me,
        log: (String) -> Unit = {},
        retryMillis: Long = PeerWatch.RETRY_MILLIS,
        onYieldChanged: () -> Unit = { changes.incrementAndGet() },
    ) = PeerWatch(discovery, own = { own }, port = 5055, onYieldChanged = onYieldChanged, retryMillis = retryMillis, log = log)

    @Test
    fun itAnnouncesThenLooksOnceAtStart() {
        val discovery = FakeDiscovery(network)
        val watch = watch(discovery)
        runBlocking { watch.start(scope).join() }
        assertEquals(me to 5055, discovery.announced)
        assertEquals(listOf("announce", "browse"), discovery.events)
        assertEquals(PeerStatus(), watch.status.value)
        assertNull(watch.notice.value)
        assertEquals(0, changes.get())
    }

    @Test
    fun anOlderHouseholdIsYieldedTo() {
        network.add(older)
        val watch = watch(FakeDiscovery(network))
        runBlocking { watch.start(scope).join() }
        assertEquals("DEN", watch.yieldTo())
        assertEquals(PeerNotice(PeerMessages.yielding("DEN"), PeerMessages.yieldAlexa("DEN")), watch.notice.value)
        assertEquals(1, changes.get())
    }

    @Test
    fun twoPcsAgreeWhichOneYields() {
        val first = watch(FakeDiscovery(network), own = older)
        val second = watch(FakeDiscovery(network))
        runBlocking {
            first.start(scope).join()
            second.start(scope).join()
        }
        assertEquals("DEN", second.yieldTo())
        // The first looked before the second was there; Settings opening looks again.
        assertNull(first.notice.value)
        first.browseNow()
        eventually { first.notice.value != null }
        assertNull(first.yieldTo())
        assertEquals(PeerNotice(PeerMessages.keeping("THIS-PC")), first.notice.value)
    }

    @Test
    fun aPeerGoneAtTheNextLookClearsTheYield() {
        network.add(older)
        val watch = watch(FakeDiscovery(network))
        runBlocking { watch.start(scope).join() }
        network.clearAdded()
        watch.browseNow()
        eventually { changes.get() == 2 }
        assertNull(watch.yieldTo())
        assertNull(watch.notice.value)
    }

    @Test
    fun aLookUnderWayIsNotDoubledAndCloseEndsIt() {
        val discovery = FakeDiscovery(network).apply { gate = CountDownLatch(1) }
        val watch = watch(discovery)
        val started = watch.start(scope)
        assertTrue(discovery.browsing.await(5, TimeUnit.SECONDS))
        // P6-PF5: a look asked for while one runs is dropped on the caller's thread, so nothing is left to wait for.
        watch.browseNow()
        watch.browseNow()
        assertEquals(1, discovery.browses.get())
        watch.close()
        watch.close()
        runBlocking { started.join() }
        assertEquals(1, discovery.closes.get())
        // Closed: dropped on the caller's thread too.
        watch.browseNow()
        assertEquals(1, discovery.browses.get())
        assertEquals(PeerStatus(), watch.status.value)
    }

    @Test
    fun withNoNetworkItIsOffAndNeverLooks() {
        val discovery = FakeDiscovery(network, available = false)
        val logs = CopyOnWriteArrayList<String>()
        val watch = watch(discovery, log = { logs += it })
        watch.start(scope)
        eventually { watch.status.value == PeerStatus(off = true) }
        // Settings opening tries the announce again (P6-T4a); with still no network there is nothing to look on. The
        // status says off before the first try lets go of the one-look flag, so the ask is repeated until one is taken.
        eventually {
            watch.browseNow()
            discovery.announces.get() >= 2
        }
        assertEquals(PeerStatus(off = true), watch.status.value)
        assertEquals(0, discovery.browses.get())
        assertNull(watch.notice.value)
        // Said once, not at every try, with the interval actually used.
        assertEquals(listOf("Meal Planner: no home network found, so other Meal Planner PCs aren't looked for yet; tried again every 60 s."), logs)
    }

    @Test
    fun whileOffTheAnnounceIsTriedAgainUntilTheNetworkComesUp() {
        // P6-T4a: started with Windows before Wi-Fi is up, it must not stay off all session.
        network.add(older)
        val discovery = FakeDiscovery(network, available = false)
        val watch = watch(discovery, retryMillis = 20)
        val started = watch.start(scope)
        eventually { discovery.announces.get() >= 2 }
        assertEquals(0, discovery.browses.get())
        discovery.available = true
        eventually { discovery.browses.get() == 1 }
        // The timer stops once it is on: the start job ends, so nothing is left to announce again (P6-R6).
        runBlocking { started.join() }
        assertEquals(me to 5055, discovery.announced)
        eventually { watch.yieldTo() == "DEN" }
        assertEquals(false, watch.status.value.off)
    }

    @Test
    fun withOnlyHyperVSwitchesUpItIsOffUntilWifiComesUp() {
        // P6-T4b with P6-T4a: announcing on vEthernet alone would succeed and stop the retry, leaving the PC unseen at
        // home all session. Here the announce asks PeerInterfaces.pick, as JmdnsDiscovery does; no socket opens.
        fun address(a: Int, b: Int, c: Int, d: Int): InetAddress = InetAddress.getByAddress(byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()))
        val hyperV = listOf(
            NetInterface("eth7", true, false, false, false, listOf(address(172, 20, 0, 1)), "vEthernet (Default Switch)"),
            NetInterface("eth8", true, false, false, false, listOf(address(172, 29, 0, 1)), "vEthernet (WSL)"),
        )
        val nics = AtomicReference(hyperV)
        val fake = FakeDiscovery(network)
        val discovery = object : PeerDiscovery by fake {
            override fun announce(record: PeerRecord, port: Int): Boolean =
                PeerInterfaces.pick(nics.get()).isNotEmpty() && fake.announce(record, port)
        }
        val watch = watch(discovery, retryMillis = 10)
        val started = watch.start(scope)
        eventually { watch.status.value.off }
        assertEquals(0, fake.browses.get())
        nics.set(hyperV + NetInterface("wlan0", true, false, false, false, listOf(address(192, 168, 1, 20)), "Wi-Fi"))
        eventually { fake.browses.get() == 1 }
        runBlocking { started.join() }
        assertEquals(PeerStatus(), watch.status.value)
    }

    @Test
    fun whileOffSettingsOpeningTriesAgainAtOnce() {
        val discovery = FakeDiscovery(network, available = false)
        val watch = watch(discovery) // the real minute: only browseNow can try again within this test
        watch.start(scope)
        eventually { watch.status.value.off }
        discovery.available = true
        // Repeated until the first try has let go of the one-look flag; the asks after the one taken are dropped.
        eventually {
            watch.browseNow()
            discovery.browses.get() == 1
        }
        assertEquals(2, discovery.announces.get())
        eventually { watch.status.value == PeerStatus() }
    }

    @Test
    fun onceOnItNeverAnnouncesAgain() {
        val discovery = FakeDiscovery(network)
        val watch = watch(discovery, retryMillis = 20)
        runBlocking { watch.start(scope).join() }
        // Each ask is repeated until it is taken (one look at a time); none of them announces.
        eventually {
            watch.browseNow()
            discovery.browses.get() >= 3
        }
        assertEquals(1, discovery.announces.get())
    }

    @Test
    fun closeStopsTheRetry() {
        val discovery = FakeDiscovery(network, available = false)
        val watch = watch(discovery, retryMillis = 10)
        val started = watch.start(scope)
        eventually { discovery.announces.get() >= 2 }
        watch.close()
        // The retry loop is the start job: once it has ended, nothing announces again.
        runBlocking { started.join() }
        val announces = discovery.announces.get()
        watch.browseNow()
        assertEquals(announces, discovery.announces.get())
        assertEquals(0, discovery.browses.get())
    }

    @Test
    fun anAnnounceThatThrowsIsOffLoggedByClassAndTriedAgain() {
        val fake = FakeDiscovery(network)
        val throws = AtomicInteger(1)
        val discovery = object : PeerDiscovery by fake {
            override fun announce(record: PeerRecord, port: Int): Boolean {
                if (throws.getAndDecrement() > 0) throw IllegalStateException("SECRET detail")
                return fake.announce(record, port)
            }
        }
        val logs = CopyOnWriteArrayList<String>()
        val watch = watch(discovery, log = { logs += it }, retryMillis = 20)
        val started = watch.start(scope)
        runBlocking { started.join() }
        assertEquals(1, fake.browses.get())
        assertEquals(PeerStatus(), watch.status.value)
        assertTrue(logs.toString(), logs.contains("Meal Planner: announcing this PC failed: IllegalStateException; tried again every 20 ms."))
        assertFalse(logs.any { it.contains("SECRET") })
    }

    @Test
    fun aFailedRebindIsLoggedApartFromTheLook() {
        network.add(older)
        val logs = CopyOnWriteArrayList<String>()
        val watch = watch(FakeDiscovery(network), log = { logs += it }, onYieldChanged = { throw IllegalStateException("no bind") })
        runBlocking { watch.start(scope).join() }
        assertEquals("DEN", watch.yieldTo())
        assertEquals(
            listOf(
                "Meal Planner: 1 other Meal Planner PC on the network; yields to an older household: yes.",
                "Meal Planner: listening again after the other-PC check failed: IllegalStateException",
            ),
            logs,
        )
    }

    @Test
    fun aLookEndingAfterCloseChangesNothing() {
        network.add(older)
        val discovery = FakeDiscovery(network).apply {
            gate = CountDownLatch(1)
            releaseOnClose = false
            answerAfterClose = true
        }
        val watch = watch(discovery)
        val started = watch.start(scope)
        assertTrue(discovery.browsing.await(5, TimeUnit.SECONDS))
        watch.close()
        // Released after close, the look would find the older household.
        discovery.gate!!.countDown()
        runBlocking { started.join() }
        assertEquals(PeerStatus(), watch.status.value)
        assertNull(watch.notice.value)
        assertEquals(0, changes.get())
    }

    @Test
    fun lookNowLooksOnceAndReturnsWithTheYieldKnown() {
        // P6-FR1: the older PC came up after this one's look at start; the look before a send finds it.
        val discovery = FakeDiscovery(network)
        val watch = watch(discovery)
        runBlocking { watch.start(scope).join() }
        assertNull(watch.yieldTo())
        network.add(older)
        runBlocking { watch.lookNow() }
        assertEquals("DEN", watch.yieldTo())
        assertEquals(2, discovery.browses.get())
        assertEquals(1, discovery.announces.get())
    }

    @Test
    fun lookNowWaitsForALookUnderWayInsteadOfStartingAnother() {
        network.add(older)
        val discovery = FakeDiscovery(network).apply { gate = CountDownLatch(1) }
        val watch = watch(discovery)
        watch.start(scope)
        assertTrue(discovery.browsing.await(5, TimeUnit.SECONDS))
        val looked = scope.async { watch.lookNow() }
        // Still waiting on the start's look, not returning on what was known before it.
        assertNull(runBlocking { withTimeoutOrNull(300) { looked.await() } })
        discovery.gate!!.countDown()
        runBlocking { looked.await() }
        assertEquals("DEN", watch.yieldTo())
        assertEquals(1, discovery.browses.get())
    }

    @Test
    fun lookNowIsBoundedWhenALookNeverEnds() {
        val discovery = FakeDiscovery(network).apply { gate = CountDownLatch(1) }
        val watch = PeerWatch(discovery, own = { me }, port = 5055, browseMillis = 50, log = {})
        watch.start(scope)
        assertTrue(discovery.browsing.await(5, TimeUnit.SECONDS))
        val began = System.nanoTime()
        runBlocking { watch.lookNow() }
        assertTrue(System.nanoTime() - began < TimeUnit.SECONDS.toNanos(4))
        discovery.gate!!.countDown()
    }

    @Test
    fun whileOffLookNowReturnsWithoutAnnouncingOrLooking() {
        // Off (no network): the send goes straight on, as with no discovery at all.
        val discovery = FakeDiscovery(network, available = false)
        val watch = watch(discovery) // the real minute: no retry within this test
        watch.start(scope)
        eventually { watch.status.value.off }
        runBlocking { watch.lookNow() }
        assertEquals(1, discovery.announces.get())
        assertEquals(0, discovery.browses.get())
    }

    @Test
    fun onceClosedLookNowReturnsWithoutLooking() {
        val discovery = FakeDiscovery(network)
        val watch = watch(discovery)
        runBlocking { watch.start(scope).join() }
        watch.close()
        runBlocking { watch.lookNow() }
        assertEquals(1, discovery.browses.get())
    }

    @Test
    fun theLogNeverNamesAPeer() {
        // P6-R11, P6-PF3: a peer's name stays in the in-memory status; the log gets counts and the decision only.
        val secret = record("SECRET-PC-NAME", '9', 100L, 7)
        network.add(secret)
        val logs = CopyOnWriteArrayList<String>()
        val watch = watch(FakeDiscovery(network), log = { logs += it })
        runBlocking { watch.start(scope).join() }
        assertEquals("SECRET-PC-NAME", watch.yieldTo())
        network.clearAdded()
        watch.browseNow()
        eventually { changes.get() == 2 }
        assertEquals(
            listOf(
                "Meal Planner: 1 other Meal Planner PC on the network; yields to an older household: yes.",
                "Meal Planner: 0 other Meal Planner PCs on the network; yields to an older household: no.",
            ),
            logs,
        )
        assertFalse(logs.any { it.contains("SECRET") })
    }
}
