package com.naeblis11.mealplanner.photos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.naeblis11.mealplanner.data.RecipeRepository
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

actual object PhotoProcessor {
    /** Writes `<uuid>.jpg` and `<uuid>_thumb.jpg` into [imagesDir] and returns the detail file's name. */
    actual fun save(bytes: ByteArray, imagesDir: File, recipeUuid: String): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("That file is not a valid image.")

        // Decode no larger than needed: keep the longest side at least PhotoSizes.DETAIL_MAX.
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PhotoSizes.DETAIL_MAX) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("That file is not a valid image.")

        val upright = upright(decoded, bytes)
        imagesDir.mkdirs()
        val name = "$recipeUuid.jpg"
        writeJpeg(fitWithin(upright, PhotoSizes.DETAIL_MAX), File(imagesDir, name), 85)
        writeJpeg(centreCrop(upright, PhotoSizes.THUMB_SIZE), File(imagesDir, RecipeRepository.thumbName(name)), 80)
        return name
    }

    private fun upright(bitmap: Bitmap, bytes: ByteArray): Bitmap {
        val orientation = try {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: IOException) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    // Pillow's thumbnail(): shrink to fit, never enlarge.
    private fun fitWithin(bitmap: Bitmap, max: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= max) return bitmap
        val scale = max.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    // Pillow's ImageOps.fit(): the centred square, scaled to size.
    private fun centreCrop(bitmap: Bitmap, size: Int): Bitmap {
        val side = minOf(bitmap.width, bitmap.height)
        val square = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
        return Bitmap.createScaledBitmap(square, size, size, true)
    }

    private fun writeJpeg(bitmap: Bitmap, target: File, quality: Int) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        try {
            val ok = temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
            if (!ok) throw PhotoWriteException("Could not save ${target.name}.")
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) throw PhotoWriteException("Could not save ${target.name}.")
            }
        } finally {
            temp.delete()
        }
    }
}
