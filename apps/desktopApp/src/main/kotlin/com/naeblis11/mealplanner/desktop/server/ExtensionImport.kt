package com.naeblis11.mealplanner.desktop.server

import com.naeblis11.mealplanner.backup.ImportBundle
import com.naeblis11.mealplanner.backup.IncomingRecipe
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.domain.Py
import com.naeblis11.mealplanner.domain.WebExtraction
import com.naeblis11.mealplanner.domain.WebPayloadCheck
import com.naeblis11.mealplanner.importing.ImportInbox
import com.naeblis11.mealplanner.importing.PreparedImport
import com.naeblis11.mealplanner.photos.PhotoProcessor
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * A recipe from the Chrome extension (P4-R3, P4-R4), as app.py's recipes_import_extension:
 * - checked, turned into Open Recipe Format, and its photo fetched and processed into a new staging folder;
 * - handed to [inbox] for the same review as a file import (the review applies the duplicate rule), and
 *   [onReceived] brings the window forward;
 * - nothing is written to the library here; Confirm on the review does that;
 * - a photo that can't be had never stops the recipe: it comes in without one, and the reply says so.
 * Blocking (the photo download).
 */
class ExtensionImport(
    private val newStagingDir: () -> File,
    private val fetchImage: (String) -> ByteArray,
    private val inbox: ImportInbox,
    private val onReceived: () -> Unit,
    private val newUuid: () -> String = { UUID.randomUUID().toString() },
    private val log: (String) -> Unit = { System.err.println(it) },
) {
    fun receive(payload: Map<Any?, Any?>): JsonReply {
        when (WebExtraction.checkPayload(payload)) {
            WebPayloadCheck.COMPLETE -> Unit
            WebPayloadCheck.MISSING -> return JsonReply.error(400, MISSING)
            WebPayloadCheck.ADDRESS_TOO_LONG -> return JsonReply.error(400, ADDRESS_TOO_LONG)
            WebPayloadCheck.FIELD_TOO_LONG -> return JsonReply.error(400, FIELD_TOO_LONG)
        }
        val uuid = newUuid()
        val doc = WebExtraction.buildRecipeData(payload, uuid)
        val dir = newStagingDir()
        val images = linkedMapOf<String, File>()
        var warning: String? = null
        val imageUrl = payload["image_url"]
        if (Py.truthy(imageUrl)) {
            try {
                val url = imageUrl as? String ?: throw IOException("the photo's address isn't text")
                val name = PhotoProcessor.save(fetchImage(url), dir, uuid)
                val thumb = RecipeRepository.thumbName(name)
                doc["image"] = name
                images[name] = File(dir, name)
                images[thumb] = File(dir, thumb)
            } catch (e: Exception) {
                // The class only: the message may hold the photo's address, which says what the user browsed.
                log("Meal Planner: the recipe's photo wasn't used: ${e.javaClass.name}")
                warning = PHOTO_WARNING
            }
        }
        val title = Py.str(doc["recipe_name"])
        val source = WebExtraction.domainFromUrl(payload["source_url"]).ifEmpty { "browser extension" }
        val bundle = ImportBundle(source, listOf(IncomingRecipe(title, doc)), images, emptyList())
        if (!inbox.offer(PreparedImport(bundle, dir))) {
            dir.deleteRecursively()
            return JsonReply.error(503, QUEUE_FULL)
        }
        onReceived()
        return if (warning == null) JsonReply.ok() else JsonReply.ok("warning" to warning)
    }

    companion object {
        /** The Python server's words, which the extension shows. */
        const val MISSING = "Missing name, ingredients, or steps."

        /** The desktop's own (Python has no caps): an address or a field over WebExtraction.checkPayload's caps. */
        const val ADDRESS_TOO_LONG = "That page's address is too long. Remove the part after ? and send it again."
        const val FIELD_TOO_LONG = "That recipe has a field that is too long to import."
        const val PHOTO_WARNING = "Could not download photo"
        const val QUEUE_FULL = "Meal Planner already has recipes waiting for review. Review those, then send this one again."
    }
}
