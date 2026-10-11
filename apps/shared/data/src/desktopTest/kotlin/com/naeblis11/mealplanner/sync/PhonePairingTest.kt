package com.naeblis11.mealplanner.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** LAN sync stage 2c (D1): what the phone keeps of its pairing, and the bytes it seals. */
class PhonePairingTest {
    private val pairing = PhonePairing(
        addresses = listOf("172.16.4.20", "172.20.0.9"),
        port = 5001,
        fingerprint = "ab".repeat(32),
        pcName = "KITCHEN",
        deviceKey = "12".repeat(32),
        hubId = "f".repeat(32),
        householdId = "0123456789abcdef0123456789abcdef",
    )

    @Test
    fun itRoundTripsThroughItsBytes() {
        assertEquals(pairing, PhonePairingCodec.decode(PhonePairingCodec.encode(pairing)))
    }

    @Test
    fun itsTextHidesTheKeyAndTheFingerprint() {
        val text = pairing.toString()
        assertFalse(text.contains(pairing.deviceKey))
        assertFalse(text.contains(pairing.fingerprint))
        assertTrue(text.contains("KITCHEN"))
    }

    @Test
    fun anythingElseIsRefused() {
        val good = PhonePairingCodec.encode(pairing).toString(Charsets.UTF_8)
        val bad = listOf(
            "not json",
            "[]",
            good.replace("\"v\":1", "\"v\":2"),
            good.replace("172.16.4.20", "fe80::1"),
            good.replace("\"port\":5001", "\"port\":70000"),
            good.replace("12".repeat(32), "12".repeat(31)),
            good.replace("ab".repeat(32), "AB".repeat(32)),
            good.replace("\"KITCHEN\"", "\"\""),
        )
        for (text in bad) {
            assertThrows(text, IllegalArgumentException::class.java) { PhonePairingCodec.decode(text.toByteArray()) }
        }
    }

    @Test
    fun aPairingOutOfRangeCantBeMade() {
        assertThrows(IllegalArgumentException::class.java) { pairing.copy(addresses = emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { pairing.copy(addresses = List(5) { "172.16.4.$it" }) }
        assertThrows(IllegalArgumentException::class.java) { pairing.copy(addresses = listOf("kitchen.local")) }
        assertThrows(IllegalArgumentException::class.java) { pairing.copy(hubId = "") }
    }

    @Test
    fun theMemoryStoreKeepsAndForgets() = runBlocking<Unit> {
        val store = MemoryPairingStore()
        assertNull(store.load())
        store.save(pairing)
        assertEquals(pairing, store.load())
        store.clear()
        assertNull(store.load())
    }
}
