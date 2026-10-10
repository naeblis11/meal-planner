package com.naeblis11.mealplanner.photos

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.folder.ExistingFileRefusedException
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.FileImageOutputStream
import kotlin.math.roundToInt

actual object PhotoProcessor {
    actual fun save(bytes: ByteArray, imagesDir: File, recipeUuid: String): String {
        val decoded = decode(bytes)
        val upright = upright(opaque(decoded), orientation(bytes))
        // Through NIO, so a folder Windows refuses (Controlled folder access) says so instead of failing later (P7-R10b).
        try {
            Files.createDirectories(imagesDir.toPath())
        } catch (e: IOException) {
            throw PhotoWriteException("Could not save $recipeUuid.jpg.", e)
        }
        val name = "$recipeUuid.jpg"
        writeJpeg(fitWithin(upright, PhotoSizes.DETAIL_MAX), File(imagesDir, name), 0.85f)
        writeJpeg(centreCrop(upright, PhotoSizes.THUMB_SIZE), File(imagesDir, RecipeRepository.thumbName(name)), 0.80f)
        return name
    }

    /**
     * Reads the photo, refusing a decompression bomb (a tiny file that declares billions of pixels): the size comes from the
     * header alone, and a large photo is decoded subsampled (every n-th pixel, n a power of two) to still be at least twice
     * [PhotoSizes.DETAIL_MAX] on its longest side, as the Android version's inSampleSize does.
     */
    private fun decode(bytes: ByteArray): BufferedImage {
        val invalid = IOException("That file is not a valid image.")
        val stream = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)) ?: throw invalid
        stream.use { input ->
            val readers = ImageIO.getImageReaders(input)
            while (readers.hasNext()) {
                val reader = readers.next()
                try {
                    input.seek(0) // a reader that failed part-way has moved the stream
                    reader.setInput(input, false, true)
                    val width = reader.getWidth(0)
                    val height = reader.getHeight(0)
                    if (width <= 0 || height <= 0 || width > MAX_SIDE || height > MAX_SIDE || width.toLong() * height > MAX_PIXELS) throw invalid
                    val step = subsampling(maxOf(width, height))
                    val params = reader.defaultReadParam.apply { setSourceSubsampling(step, step, 0, 0) }
                    return reader.read(0, params)
                } catch (e: OutOfMemoryError) {
                    throw invalid
                } catch (e: IOException) {
                    if (e === invalid) throw e
                } catch (e: RuntimeException) {
                    // This reader can't read it; try the next one.
                } finally {
                    reader.dispose()
                }
            }
        }
        throw invalid
    }

    // The largest power of two that leaves the longest side at least twice the detail size.
    internal fun subsampling(longest: Int): Int {
        var step = 1
        while ((longest + step * 2 - 1) / (step * 2) >= PhotoSizes.DETAIL_MAX * 2) step *= 2
        return step
    }

    private const val MAX_PIXELS = 50_000_000L
    private const val MAX_SIDE = 20_000

    /** EXIF Orientation (1-8); 1 when there is none or it can't be read. */
    private fun orientation(bytes: ByteArray): Int = try {
        ImageMetadataReader.readMetadata(ByteArrayInputStream(bytes))
            .getFirstDirectoryOfType(ExifIFD0Directory::class.java)
            ?.takeIf { it.containsTag(ExifIFD0Directory.TAG_ORIENTATION) }
            ?.getInt(ExifIFD0Directory.TAG_ORIENTATION) ?: 1
    } catch (e: Exception) {
        1
    }

    /** JPEG has no alpha: flatten onto white, as Pillow's convert("RGB") does for a PNG. */
    private fun opaque(image: BufferedImage): BufferedImage {
        if (image.type == BufferedImage.TYPE_INT_RGB) return image
        val rgb = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        rgb.createGraphics().apply {
            color = Color.WHITE
            fillRect(0, 0, image.width, image.height)
            drawImage(image, 0, 0, null)
            dispose()
        }
        return rgb
    }

    // The same eight cases as the Android version's Matrix, as a pixel mapping.
    private fun upright(image: BufferedImage, orientation: Int): BufferedImage {
        if (orientation !in 2..8) return image
        val w = image.width
        val h = image.height
        val swaps = orientation >= 5
        val out = BufferedImage(if (swaps) h else w, if (swaps) w else h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val (dx, dy) = when (orientation) {
                    2 -> (w - 1 - x) to y // flip horizontal
                    3 -> (w - 1 - x) to (h - 1 - y) // rotate 180
                    4 -> x to (h - 1 - y) // flip vertical
                    5 -> y to x // transpose
                    6 -> (h - 1 - y) to x // rotate 90 clockwise
                    7 -> (h - 1 - y) to (w - 1 - x) // transverse
                    else -> y to (w - 1 - x) // 8: rotate 270 clockwise
                }
                out.setRGB(dx, dy, image.getRGB(x, y))
            }
        }
        return out
    }

    // Pillow's thumbnail(): shrink to fit, never enlarge.
    private fun fitWithin(image: BufferedImage, max: Int): BufferedImage {
        val longest = maxOf(image.width, image.height)
        if (longest <= max) return image
        val scale = max.toDouble() / longest
        return scaled(image, (image.width * scale).roundToInt().coerceAtLeast(1), (image.height * scale).roundToInt().coerceAtLeast(1))
    }

    // Pillow's ImageOps.fit(): the centred square, scaled to size.
    private fun centreCrop(image: BufferedImage, size: Int): BufferedImage {
        val side = minOf(image.width, image.height)
        return scaled(image.getSubimage((image.width - side) / 2, (image.height - side) / 2, side, side), size, size)
    }

    private fun scaled(image: BufferedImage, width: Int, height: Int): BufferedImage {
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            drawImage(image, 0, 0, width, height, null)
            dispose()
        }
        return out
    }

    private fun writeJpeg(image: BufferedImage, target: File, quality: Float) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        try {
            val writer = ImageIO.getImageWritersByFormatName("jpg").next()
            // Opened directly, so a refused file throws "(Access is denied)" rather than ImageIO's null (P7-R10b).
            FileImageOutputStream(temp).use { out ->
                try {
                    writer.output = out
                    val params = writer.defaultWriteParam.apply {
                        compressionMode = ImageWriteParam.MODE_EXPLICIT
                        compressionQuality = quality
                    }
                    writer.write(null, IIOImage(image, null, null), params)
                } finally {
                    writer.dispose()
                }
            }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AccessDeniedException) {
                // Over a photo already there: a lock or a read-only file, not Controlled folder access (P7-R10b M3).
                throw ExistingFileRefusedException(e)
            }
        } catch (e: IOException) {
            throw PhotoWriteException("Could not save ${target.name}.", e)
        } finally {
            temp.delete()
        }
    }
}
