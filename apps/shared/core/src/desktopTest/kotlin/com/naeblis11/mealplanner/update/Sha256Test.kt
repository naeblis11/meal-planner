package com.naeblis11.mealplanner.update

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class Sha256Test {
    @Test
    fun itIsTheStandardDigestInLowercaseHex() {
        val abc = "abc".encodeToByteArray()
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.of(abc))
        val file = File.createTempFile("mp-sha", ".bin")
        try {
            file.writeBytes(abc)
            assertEquals(Sha256.of(abc), Sha256.of(file))
        } finally {
            file.delete()
        }
    }
}
