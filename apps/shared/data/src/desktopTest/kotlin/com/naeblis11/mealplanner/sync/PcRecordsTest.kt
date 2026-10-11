package com.naeblis11.mealplanner.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** LAN sync stage 2c (D2): the PC's mDNS record as the phone reads it. Untrusted: anything garbled is left out. */
class PcRecordsTest {
    private fun txt(vararg pairs: Pair<String, String>): Map<String, ByteArray?> = pairs.associate { (k, v) -> k to v.toByteArray() }

    private val good = txt(
        "v" to "1", "role" to "master", "hh" to "0123456789abcdef", "since" to "1", "id" to "a".repeat(32), "app" to "1.0.7", "sync" to "5001",
    )

    @Test
    fun aMasterOfferingItsSyncPortIsFound() {
        assertEquals(FoundPc("KITCHEN", "192.0.2.20", 5001), PcRecords.foundPc("KITCHEN", "192.0.2.20", good))
    }

    @Test
    fun aRecordWithoutTheSyncKeyIsLeftOut() {
        assertNull(PcRecords.foundPc("KITCHEN", "192.0.2.20", good - "sync"))
        assertNull(PcRecords.foundPc("KITCHEN", "192.0.2.20", good + ("sync" to null)))
    }

    @Test
    fun garbledValuesAreLeftOut() {
        for (sync in listOf("0", "65536", "50o1", "123456", "")) {
            assertNull(sync, PcRecords.foundPc("K", "192.0.2.20", good + ("sync" to sync.toByteArray())))
        }
        assertNull(PcRecords.foundPc("K", "192.0.2.20", good + ("v" to "2".toByteArray())))
        assertNull(PcRecords.foundPc("K", "192.0.2.20", good + ("role" to "client".toByteArray())))
    }

    @Test
    fun onlyAnIpv4HostIsKept() {
        for (host in listOf("fe80::1", "2001:db8::5", "kitchen.local", "192.0.2.256", "", null)) {
            assertNull("$host", PcRecords.foundPc("K", host, good))
        }
        assertTrue(PairingQr.isIpv4("198.51.100.9"))
        assertFalse(PairingQr.isIpv4("198.51.100"))
    }

    @Test
    fun theNameIsCleaned() {
        assertEquals("KITCHEN", PcRecords.foundPc("KIT\u200BCHEN", "192.0.2.20", good)!!.name)
        assertEquals(PcRecords.FALLBACK_NAME, PcRecords.foundPc(null, "192.0.2.20", good)!!.name)
    }

    @Test
    fun theFirstIpv4AddressIsPickedWhateverComesFirst() {
        assertEquals("192.0.2.20", PcRecords.firstIpv4(listOf("fe80::1", null, "192.0.2.20", "198.51.100.9")))
        assertNull(PcRecords.firstIpv4(listOf("fe80::1", "2001:db8::5", null)))
        assertNull(PcRecords.firstIpv4(emptyList()))
    }
}
