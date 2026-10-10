package com.naeblis11.mealplanner.photos

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import javax.imageio.ImageIO
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopPhotoProcessorTest {
    private val dir: File = Files.createTempDirectory("mp-photos").toFile()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun jpeg(width: Int, height: Int): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray()

    /** [jpeg] with an EXIF APP1 segment holding only Orientation = [orientation], right after SOI. */
    private fun withOrientation(jpeg: ByteArray, orientation: Int): ByteArray {
        val tiff = byteArrayOf(
            0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08, // "MM", 42, IFD0 at offset 8
            0x00, 0x01, // one entry
            0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, // Orientation, SHORT, count 1
            0x00, orientation.toByte(), 0x00, 0x00, // value, padded
            0x00, 0x00, 0x00, 0x00, // no next IFD
        )
        val payload = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
        val length = payload.size + 2
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + payload
        return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
    }

    private fun size(name: String): Pair<Int, Int> = ImageIO.read(File(dir, name)).let { it.width to it.height }

    @Test
    fun writesADetailWithin800AndA96Thumbnail() {
        assertEquals("u1.jpg", PhotoProcessor.save(jpeg(1600, 800), dir, "u1"))
        assertEquals(800 to 400, size("u1.jpg"))
        assertEquals(96 to 96, size("u1_thumb.jpg"))
    }

    @Test
    fun neverEnlargesASmallPhoto() {
        PhotoProcessor.save(jpeg(300, 200), dir, "u2")
        assertEquals(300 to 200, size("u2.jpg"))
    }

    @Test
    fun turnsAPhotoUprightFromItsExifOrientation() {
        PhotoProcessor.save(withOrientation(jpeg(1600, 800), 6), dir, "u3") // rotate 90 clockwise
        assertEquals(400 to 800, size("u3.jpg"))
    }

    private fun int32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val body = type.toByteArray(Charsets.US_ASCII) + data
        return int32(data.size.toLong()) + body + int32(CRC32().apply { update(body) }.value)
    }

    private val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun pngHeader(width: Int, height: Int) = chunk("IHDR", int32(width.toLong()) + int32(height.toLong()) + byteArrayOf(8, 0, 0, 0, 0))

    /**
     * A VALID 8-bit grey PNG of [width] x [height] (all black): every row is stream-deflated, so it is a few tens of KB even
     * for tens of millions of pixels, and a decoder that doesn't refuse it really would decode every pixel.
     */
    private fun validGreyPng(width: Int, height: Int): ByteArray {
        val packed = ByteArrayOutputStream()
        DeflaterOutputStream(packed).use { out ->
            val row = ByteArray(1 + width) // filter type 0, then zero pixels
            repeat(height) { out.write(row) }
        }
        return pngSignature + pngHeader(width, height) + chunk("IDAT", packed.toByteArray()) + chunk("IEND", ByteArray(0))
    }

    /** A PNG whose header declares [width] x [height] but whose data is a few bytes: broken, and huge on paper. */
    private fun brokenHugePng(width: Int, height: Int): ByteArray {
        val packed = ByteArray(128)
        val deflater = Deflater().apply { setInput(ByteArray(64)); finish() }
        val length = deflater.deflate(packed)
        return pngSignature + pngHeader(width, height) + chunk("IDAT", packed.copyOf(length)) + chunk("IEND", ByteArray(0))
    }

    private fun assertRefused(png: ByteArray) {
        val e = assertThrows(IOException::class.java) { PhotoProcessor.save(png, dir, "refused") }
        assertEquals("That file is not a valid image.", e.message)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aValidPhotoOverFiftyMillionPixelsIsRefused() {
        val png = validGreyPng(7_100, 7_100) // 50.41 million pixels, just over the cap
        assertTrue("${png.size} bytes", png.size < 200_000)
        assertRefused(png)
    }

    @Test
    fun aValidPhotoWithASideOverTwentyThousandIsRefused() {
        val png = validGreyPng(25_000, 10) // only 250,000 pixels
        assertTrue("${png.size} bytes", png.size < 200_000)
        assertRefused(png)
    }

    @Test
    fun aValidPhotoJustUnderTheCapsIsStillSaved() {
        assertEquals("u8.jpg", PhotoProcessor.save(validGreyPng(7_000, 7_000), dir, "u8")) // 49 million pixels
        assertEquals(800 to 800, size("u8.jpg"))
    }

    @Test
    fun aBrokenPhotoWithAHugeHeaderIsRefusedQuicklyRatherThanHanging() {
        val started = System.nanoTime()
        assertRefused(brokenHugePng(30_000, 30_000))
        assertRefused(brokenHugePng(25_000, 10))
        assertTrue("took ${(System.nanoTime() - started) / 1_000_000} ms", (System.nanoTime() - started) / 1_000_000 < 2_000)
    }

    @Test
    fun theDecodeStepIsTheLargestPowerOfTwoThatKeepsTwiceTheDetailSize() {
        assertEquals(1, PhotoProcessor.subsampling(1_600))
        assertEquals(1, PhotoProcessor.subsampling(1_700))
        assertEquals(2, PhotoProcessor.subsampling(3_200))
        assertEquals(2, PhotoProcessor.subsampling(4_000))
        assertEquals(4, PhotoProcessor.subsampling(12_000))
    }

    @Test
    fun aVeryLargePhotoIsDecodedSubsampledAndStillFillsTheDetailSize() {
        assertEquals("u7.jpg", PhotoProcessor.save(jpeg(4000, 3000), dir, "u7"))
        assertEquals(800 to 600, size("u7.jpg"))
        assertEquals(96 to 96, size("u7_thumb.jpg"))
    }

    @Test
    fun aWebpPhotoIsSavedAsJpeg() {
        // An 8 x 6 lossy WebP made with Pillow. The JDK's ImageIO can't read WebP on its own.
        val webp = Base64.getDecoder().decode("UklGRjoAAABXRUJQVlA4IC4AAADQAQCdASoIAAYAAUAmJaACdLoB+AADsAD+pNf/TSPGkeNI+Yt/84ljqd3aAAAA")
        assertEquals("u6.jpg", PhotoProcessor.save(webp, dir, "u6"))
        assertEquals(8 to 6, size("u6.jpg"))
        assertEquals(96 to 96, size("u6_thumb.jpg"))
    }

    @Test
    fun rejectsSomethingThatIsNotAnImage() {
        val e = assertThrows(IOException::class.java) { PhotoProcessor.save("not a photo".toByteArray(), dir, "u4") }
        assertEquals("That file is not a valid image.", e.message)
    }

    /** 80x40 white with a 20x20 red block in the top-left corner, as a JPEG. */
    private fun markedJpeg(): ByteArray {
        val image = BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 40) for (x in 0 until 80) image.setRGB(x, y, if (x < 20 && y < 20) 0xFF0000 else 0xFFFFFF)
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
    }

    private fun isRed(rgb: Int) = (rgb shr 16 and 0xFF) > 180 && (rgb shr 8 and 0xFF) < 90 && (rgb and 0xFF) < 90

    private fun isWhite(rgb: Int) = (rgb shr 16 and 0xFF) > 180 && (rgb shr 8 and 0xFF) > 180 && (rgb and 0xFF) > 180

    @Test
    fun everyExifOrientationEndsUpUprightWithTheMarkerWhereTheSpecSaysItGoes() {
        // Where the top-left red block lands (the centre of its 20x20 area) once the photo is upright, by EXIF orientation:
        // 1 as is; 2 mirrored left-right; 3 turned 180; 4 mirrored top-bottom; 5 transposed; 6 turned 90 clockwise;
        // 7 transversed; 8 turned 270 clockwise. The corners of a 40-wide (or 80-wide) result are named by their block centres.
        data class Case(val orientation: Int, val width: Int, val height: Int, val markerX: Int, val markerY: Int)
        val cases = listOf(
            Case(1, 80, 40, 10, 10),
            Case(2, 80, 40, 69, 10),
            Case(3, 80, 40, 69, 29),
            Case(4, 80, 40, 10, 29),
            Case(5, 40, 80, 10, 10),
            Case(6, 40, 80, 29, 10),
            Case(7, 40, 80, 29, 69),
            Case(8, 40, 80, 10, 69),
        )
        for (c in cases) {
            val uuid = "o${c.orientation}"
            PhotoProcessor.save(withOrientation(markedJpeg(), c.orientation), dir, uuid)
            assertEquals("size for orientation ${c.orientation}", c.width to c.height, size("$uuid.jpg"))
            val out = ImageIO.read(File(dir, "$uuid.jpg"))
            val centres = listOf(10 to 10, c.width - 11 to 10, c.width - 11 to c.height - 11, 10 to c.height - 11)
            for ((x, y) in centres) {
                val rgb = out.getRGB(x, y)
                if (x == c.markerX && y == c.markerY) {
                    assertTrue("orientation ${c.orientation}: marker at ($x,$y) should be red", isRed(rgb))
                } else {
                    assertTrue("orientation ${c.orientation}: ($x,$y) should be white", isWhite(rgb))
                }
            }
        }
    }
}
