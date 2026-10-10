package com.naeblis11.mealplanner.desktop

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * P7-R2: the installer's and the launcher's icon, made once from the repo's icon.png with Pillow and committed. An
 * .ico is a 6-byte header, then one 16-byte entry per size (a width of 0 means 256), each pointing at a PNG.
 */
class PackagingIconTest {
    @Test
    fun theInstallerIconHoldsEveryWindowsSizeAsPng() {
        val bytes = File(System.getProperty("appIco") ?: error("appIco is not set; run through Gradle")).readBytes()
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0, header.getShort(0).toInt())
        assertEquals(1, header.getShort(2).toInt()) // an icon, not a cursor
        val count = header.getShort(4).toInt()
        val sides = (0 until count).map { i ->
            val entry = 6 + 16 * i
            val side = (bytes[entry].toInt() and 0xFF).let { if (it == 0) 256 else it }
            val length = header.getInt(entry + 8)
            val offset = header.getInt(entry + 12)
            val image = ImageIO.read(ByteArrayInputStream(bytes, offset, length))
            assertNotNull("the $side px entry doesn't decode as a PNG", image)
            assertEquals(side, image.width)
            assertEquals(side, image.height)
            side
        }
        assertEquals(listOf(16, 24, 32, 48, 64, 128, 256), sides.sorted())
    }
}
