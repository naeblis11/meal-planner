package com.naeblis11.mealplanner.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** LAN sync stage 2c (D1, spec "For 2c"): a device's name as sent at pairing always passes PairingWire.isName. */
class DeviceNamesTest {
    @Test
    fun aPlainModelIsKeptTrimmed() {
        assertEquals("Pixel 9", DeviceNames.clean("  Pixel 9 "))
    }

    @Test
    fun controlAndFormatCharactersGo() {
        assertEquals("Pixel9", DeviceNames.clean("Pixel\u0000\u200B9\u202E"))
    }

    @Test
    fun nothingLeftIsTheFallback() {
        assertEquals(DeviceNames.PHONE, DeviceNames.clean(" \u200B "))
        assertEquals(DeviceNames.PHONE, DeviceNames.clean(null))
        assertEquals("PC", DeviceNames.clean("", fallback = "PC"))
    }

    @Test
    fun aLongNameIsCutWithoutSplittingAnEmoji() {
        // 63 letters, then an emoji (two UTF-16 characters) that would make 65: it goes whole.
        assertEquals("a".repeat(63), DeviceNames.clean("a".repeat(63) + "\uD83D\uDE00" + "b"))
        val fits = "a".repeat(62) + "\uD83D\uDE00"
        assertEquals(fits, DeviceNames.clean(fits + "zzz"))
    }

    @Test
    fun everyResultIsAName() {
        for (raw in listOf("Pixel", "\uD83D\uDE00".repeat(40), "x".repeat(500), "\u0007", "\uD800 lone")) {
            assertTrue(raw, PairingWire.isName(DeviceNames.clean(raw)))
        }
    }
}
