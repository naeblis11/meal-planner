package com.naeblis11.mealplanner.backup

import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.domain.OrfEditing
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ExportSummary(val recipes: Int, val images: Int)

/**
 * Writes the library as a zip in the Pi's data-folder layout:
 * `recipes/<slug>.yaml` (each recipe's YAML, verbatim) and
 * `recipe-images/<file>` (its photo and thumbnail). Unzipped into the Pi's
 * data folder and rescanned, it becomes the Pi's library; imported here, it
 * updates the recipes it came from.
 */
class BackupExporter(
    private val repository: RecipeRepository,
    private val imagesDir: File,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Writes the zip to [out] and closes it. If this throws, [out] has been
     * closed without finishing the zip; the caller must delete the destination,
     * because a streaming reader can still find the entries written before the failure.
     * Runs on the exporter's dispatcher.
     */
    suspend fun export(out: OutputStream): ExportSummary = withContext(dispatcher) {
        val takenNames = mutableSetOf<String>()
        val writtenImages = mutableSetOf<String>()
        val zip = ZipOutputStream(out)
        try {
            val recipes = repository.allRecipes()
            for (recipe in recipes) {
                val fileName = OrfEditing.uniqueFilename(recipe.name, takenNames)
                zip.putNextEntry(ZipEntry("recipes/$fileName"))
                zip.write(recipe.rawYaml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                val image = recipe.imageFilename ?: continue
                if (!RecipeRepository.isSafeImageName(image)) continue
                for (name in listOf(image, RecipeRepository.thumbName(image)).distinct()) {
                    val file = File(imagesDir, name)
                    if (!file.isFile || !writtenImages.add(name)) continue
                    zip.putNextEntry(ZipEntry("${ImportReader.IMAGES_DIR}/$name"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            zip.close()  // Success: finish the zip and close out
            ExportSummary(recipes.size, writtenImages.size)
        } catch (e: Exception) {
            // Failure: close out without finishing the zip
            runCatching { out.close() }
            throw e
        }
    }
}
