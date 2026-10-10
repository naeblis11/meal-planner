package com.naeblis11.mealplanner.desktop.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P4-R2: only this PC may send recipes, judged from the address alone (no DNS). */
class LoopbackTest {
    @Test
    fun onlyLoopbackAddressesAreThisPc() {
        for (address in listOf("127.0.0.1", "127.5.6.7", "::1", "0:0:0:0:0:0:0:1", "::ffff:127.0.0.1", "/127.0.0.1")) {
            assertTrue(address, isLoopback(address))
        }
        for (address in listOf(
            "192.0.2.20", "198.51.100.1", "203.0.113.7", "0.0.0.0", "fe80::1%3", "fe80::1", "localhost", "999.0.0.1", "", null,
            // An IPv4-mapped private address, and a loopback address with a port on it (not an address).
            "::ffff:172.16.0.1", "127.0.0.1:5000", "[::1]:5000",
        )) {
            assertFalse("$address", isLoopback(address))
        }
    }
}
