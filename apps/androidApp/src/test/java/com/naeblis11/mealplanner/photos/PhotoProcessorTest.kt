package com.naeblis11.mealplanner.photos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoProcessorTest {
    private fun png(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(28, 122, 77)) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun size(file: File) = BitmapFactory.decodeFile(file.path).let { it.width to it.height }

    @Test
    fun writesAnUprightDetailAndASquareThumbnail() {
        val dir = Files.createTempDirectory("images").toFile()
        assertEquals("abc.jpg", PhotoProcessor.save(png(1600, 1200), dir, "abc"))
        assertEquals(800 to 600, size(File(dir, "abc.jpg")))
        assertEquals(96 to 96, size(File(dir, "abc_thumb.jpg")))

        PhotoProcessor.save(png(600, 1800), dir, "tall")
        assertEquals(267 to 800, size(File(dir, "tall.jpg")))
    }

    @Test
    fun smallPhotosAreNotEnlarged() {
        val dir = Files.createTempDirectory("images").toFile()
        PhotoProcessor.save(png(300, 200), dir, "small")
        assertEquals(300 to 200, size(File(dir, "small.jpg")))
    }

    @Test
    fun refusesAFileThatIsNotAnImage() {
        val dir = Files.createTempDirectory("images").toFile()
        val error = assertThrows(IOException::class.java) { PhotoProcessor.save("not an image".toByteArray(), dir, "x") }
        assertEquals("That file is not a valid image.", error.message)
    }
}
