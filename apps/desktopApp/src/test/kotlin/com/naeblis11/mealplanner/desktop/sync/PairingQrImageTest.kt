package com.naeblis11.mealplanner.desktop.sync

import com.naeblis11.mealplanner.sync.PairingQr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** LAN sync stage 2b (D10): the pairing text as QR modules, with the three finder patterns where a scanner looks. */
class PairingQrImageTest {
    @Test
    fun thePairingTextBecomesAQrCodeWithItsFinderPatterns() {
        val modules = qrModules(PairingQr.encode(listOf("192.0.2.20", "198.51.100.5"), 5001, "012345", "ab".repeat(32)))
        val size = modules.size
        assertTrue(size >= 21)
        assertEquals(0, (size - 17) % 4)
        // Each finder pattern's corner is dark, and the separator beside it light: top-left, top-right, bottom-left.
        assertTrue(modules.isDark(0, 0))
        assertFalse(modules.isDark(7, 0))
        assertTrue(modules.isDark(size - 1, 0))
        assertFalse(modules.isDark(size - 8, 0))
        assertTrue(modules.isDark(0, size - 1))
        assertFalse(modules.isDark(0, size - 8))
        // The finder's centre is dark, inside its light ring.
        assertTrue(modules.isDark(3, 3))
        assertFalse(modules.isDark(1, 1))
    }

    @Test
    fun columnsAndRowsAreNotSwapped() {
        // The timing pattern runs along row 6 (x varies) and column 6 (y varies); the one dark module (QR spec 7.9) sits at
        // column 8, row size - 8, and its mirror (column size - 8, row 8) is format information, not fixed dark. Version 1+
        // always has it, so x is the column exactly when isDark(8, size - 8) holds for every text.
        for (text in listOf("a", PairingQr.encode(listOf("192.0.2.20"), 5001, "000000", "cd".repeat(32)))) {
            val modules = qrModules(text)
            assertTrue(text, modules.isDark(8, modules.size - 8))
        }
    }
}
