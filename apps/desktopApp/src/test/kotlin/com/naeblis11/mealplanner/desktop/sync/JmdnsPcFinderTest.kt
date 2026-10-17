package com.naeblis11.mealplanner.desktop.sync

import com.naeblis11.mealplanner.desktop.peers.PeerRecord
import com.naeblis11.mealplanner.desktop.peers.PeerTxt
import com.naeblis11.mealplanner.sync.FoundPc
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.jmdns.ServiceInfo

/** Stage 4a, R5: a PC finds the main PC through the browse seam; no JmDNS, no socket. */
class JmdnsPcFinderTest {
    private fun testNet(last: Int): Inet4Address = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, last.toByte())) as Inet4Address

    private fun id(n: Int) = "%032x".format(n)

    private fun record(n: Int, role: String = PeerTxt.ROLE_MASTER, sync: Int? = 5001) =
        PeerRecord("PC$n", "0123456789abcdef", 1_700_000_000_000L, id(n), "1.0", sync, role)

    private fun service(record: PeerRecord, vararg addresses: String?) =
        ResolvedService(record.name, addresses.toList(), PeerTxt.encode(record).mapValues { it.value.toByteArray(Charsets.UTF_8) })

    @Test
    fun mainPcsAreFoundAndClientsGarbageAndOurselvesAreNot() = runBlocking<Unit> {
        val finder = JmdnsPcFinder(
            ownId = { id(9) },
            addresses = { listOf(testNet(1)) },
            browse = { _, _ ->
                listOf(
                    service(record(1), "192.0.2.20"),
                    service(record(2, role = PeerTxt.ROLE_CLIENT), "192.0.2.21"),
                    service(record(3, sync = null), "192.0.2.22"),
                    service(record(9), "192.0.2.29"),
                    ResolvedService("JUNK", listOf("192.0.2.23"), mapOf("v" to "1".toByteArray())),
                )
            },
        )
        assertEquals(listOf(FoundPc("PC1", "192.0.2.20", 5001)), finder.find(5_000))
    }

    @Test
    fun anIpv6OnlyPcIsDroppedAndTheFirstIpv4IsUsed() = runBlocking<Unit> {
        val finder = JmdnsPcFinder(
            addresses = { listOf(testNet(1)) },
            browse = { _, _ -> listOf(service(record(1), "fe80::1"), service(record(2), "fe80::2", "192.0.2.21")) },
        )
        assertEquals(listOf(FoundPc("PC2", "192.0.2.21", 5001)), finder.find(5_000))
    }

    @Test
    fun everyInterfaceIsLookedOnAtOnceAndAnAddressFoundTwiceIsListedOnce() = runBlocking<Unit> {
        val asked = CopyOnWriteArrayList<Pair<Inet4Address, Long>>()
        val finder = JmdnsPcFinder(
            addresses = { listOf(testNet(1), testNet(2), testNet(3)) },
            browse = { address, millis ->
                asked += address to millis
                if (address == testNet(2)) throw java.io.IOException("this interface is down")
                listOf(service(record(1), "192.0.2.20"))
            },
        )
        assertEquals(listOf(FoundPc("PC1", "192.0.2.20", 5001)), finder.find(3_000))
        assertEquals(setOf(testNet(1), testNet(2), testNet(3)), asked.map { it.first }.toSet())
        assertTrue(asked.all { it.second == 3_000L })
    }

    @Test
    fun itNeverThrows() = runBlocking<Unit> {
        assertEquals(emptyList<FoundPc>(), JmdnsPcFinder(addresses = { throw IllegalStateException("no interfaces") }).find(1_000))
        assertEquals(emptyList<FoundPc>(), JmdnsPcFinder(ownId = { throw IllegalStateException("no database") }, addresses = { emptyList() }).find(1_000))
    }

    @Test
    fun aLookThatHangsIsCutAtTheTimeout() = runBlocking<Unit> {
        val finder = JmdnsPcFinder(
            addresses = { listOf(testNet(1)) },
            browse = { _, _ ->
                Thread.sleep(60_000)
                emptyList()
            },
        )
        val started = System.nanoTime()
        assertEquals(emptyList<FoundPc>(), finder.find(100))
        val took = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $took ms", took < 100 + JmdnsPcFinder.SLACK_MILLIS + 2_000)
    }

    @Test
    fun aResolvedServiceKeepsItsTxtAndTooManyKeysAreRefused() {
        val txt = PeerTxt.encode(record(1))
        val resolved = JmdnsPcFinder.resolved(ServiceInfo.create(PeerTxt.SERVICE_TYPE, "PC1", 5000, 0, 0, txt))!!
        assertEquals("PC1", resolved.name)
        assertEquals(txt, resolved.txt.mapValues { it.value?.toString(Charsets.UTF_8) })
        val crowded = (1..PeerTxt.MAX_TXT_KEYS + 1).associate { "k$it" to "x" }
        assertNull(JmdnsPcFinder.resolved(ServiceInfo.create(PeerTxt.SERVICE_TYPE, "PC1", 5000, 0, 0, crowded)))
    }
}
