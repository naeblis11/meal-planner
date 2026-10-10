package com.naeblis11.mealplanner.desktop.google

import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The one test that uses real DPAPI (P5-R4): Windows only, in memory, with a made-up value; no file is read or
 * written, so the user's own sign-in is never touched.
 */
class DpapiProtectorTest {
    @Test
    fun windowsSealsAndUnsealsInMemoryAndRefusesWhatItDidntSeal() {
        assumeTrue(System.getProperty("os.name").orEmpty().startsWith("Windows"))
        val protector = DpapiProtector()
        val plain = "not-a-real-token-x".toByteArray(Charsets.UTF_8)
        val sealed = protector.protect(plain)
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("not-a-real-token-x"))
        assertArrayEquals(plain, protector.unprotect(sealed))
        assertThrows(IOException::class.java) { protector.unprotect("not sealed by DPAPI".toByteArray(Charsets.US_ASCII)) }
    }
}
