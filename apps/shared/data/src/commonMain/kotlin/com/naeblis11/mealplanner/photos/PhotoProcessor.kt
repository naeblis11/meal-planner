package com.naeblis11.mealplanner.photos

import java.io.File
import java.io.IOException

/**
 * A photo that decoded but couldn't be written ("Could not save <name>."), with what refused it as its [cause], so the
 * desktop can tell Controlled folder access from any other failure (P7-R10b).
 */
class PhotoWriteException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The Pi's photo sizes (app.py `_process_and_save_recipe_image`). */
object PhotoSizes {
    const val DETAIL_MAX = 800
    const val THUMB_SIZE = 96
}

/**
 * A recipe photo as the Pi stores it: turned upright from its EXIF orientation, a detail
 * JPEG fitted within [PhotoSizes.DETAIL_MAX] and a [PhotoSizes.THUMB_SIZE] centre-cropped
 * thumbnail, named after the recipe's uuid. Throws IOException("That file is not a valid
 * image.") for anything that doesn't decode, and [PhotoWriteException] when the files can't be written.
 */
expect object PhotoProcessor {
    /** Writes `<uuid>.jpg` and `<uuid>_thumb.jpg` into [imagesDir] and returns the detail file's name. */
    fun save(bytes: ByteArray, imagesDir: File, recipeUuid: String): String
}
