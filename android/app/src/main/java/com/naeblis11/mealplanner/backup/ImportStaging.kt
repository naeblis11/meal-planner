package com.naeblis11.mealplanner.backup

import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.domain.Amounts
import com.naeblis11.mealplanner.domain.Fraction
import com.naeblis11.mealplanner.domain.Orf
import com.naeblis11.mealplanner.domain.OrfEditing
import com.naeblis11.mealplanner.domain.Py
import com.naeblis11.mealplanner.domain.RecipeFormatException
import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.SubmittedRow
import com.naeblis11.mealplanner.domain.YamlMap
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** NEW: goes in as is. DUPLICATE: its name is taken; ignored unless renamed. UPDATE: same recipe_uuid as one in the library. */
enum class StagedKind { NEW, DUPLICATE, UPDATE }

data class StagedRecipe(
    val tempId: Int,
    val title: String,
    val doc: YamlMap,
    val kind: StagedKind,
    val amountIssues: Int,
)

/**
 * An import waiting for review. Nothing in it has been written; its images are
 * files in the import's staging folder (see [ImportReader.read]).
 */
data class StagedImport(
    val sourceName: String,
    val recipes: List<StagedRecipe>,
    val images: Map<String, File>,
    val errors: List<Pair<String, String>>,
)

/** Sorts an import into new recipes, duplicates and updates, like the Pi's import step. */
object ImportStager {
    suspend fun stage(
        bundle: ImportBundle,
        repository: RecipeRepository,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): StagedImport = withContext(dispatcher) {
        val takenNames = repository.lowerCaseNames()
        val seenUuids = mutableSetOf<String>()
        val recipes = bundle.recipes.mapIndexed { i, incoming ->
            val doc = incoming.doc
            val uuid = Orf.cleanNone(doc["recipe_uuid"])?.let { Py.str(it) }?.takeIf { it.isNotEmpty() }
            // A second recipe in the same file with the same uuid would overwrite the first.
            val repeated = uuid != null && !seenUuids.add(uuid)
            if (repeated) doc["recipe_uuid"] = null
            val nameKey = incoming.title.lowercase(Locale.ROOT)
            val kind = when {
                uuid != null && !repeated && repository.idForUuid(uuid) != null -> StagedKind.UPDATE
                nameKey in takenNames -> StagedKind.DUPLICATE
                else -> StagedKind.NEW
            }
            if (kind != StagedKind.DUPLICATE) takenNames += nameKey
            StagedRecipe(i, incoming.title, doc, kind, OrfEditing.findUnparseableAmountSlots(doc).size)
        }
        StagedImport(bundle.sourceName, recipes, bundle.images, bundle.errors)
    }
}

enum class ImportAction { IMPORT, UPDATE, SKIP }

/** What the review screen submitted for one staged recipe. */
data class ImportDecision(
    val tempId: Int,
    val action: ImportAction,
    val title: String,
    val category: String = "",
    val subcategory: String = "",
    val servingsAmount: String = "",
    val servingsUnit: String = "",
    val ingredientRows: List<SubmittedRow>? = null,
)

data class ImportOutcome(
    val imported: Int,
    val updated: Int,
    val ignored: List<String>,
    val errors: List<Pair<String, String>>,
)

/** A decision can't be carried out (a name clash, no ingredients); nothing was written. */
class ImportValidationException(message: String) : Exception(message)

/**
 * The review screen's Confirm, like the Pi's /recipes/import/confirm: every
 * recipe is checked first, then photos are written and all recipes saved in
 * one transaction.
 */
class ImportCommitter(
    private val repository: RecipeRepository,
    private val imagesDir: File,
    private val newUuid: () -> String = { UUID.randomUUID().toString() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    // images: (name in the library's photo folder, file to copy it from).
    private class Pending(val doc: YamlMap, val update: Boolean, val images: List<Pair<String, File>>)

    suspend fun confirm(staged: StagedImport, decisions: List<ImportDecision>): ImportOutcome = withContext(dispatcher) {
        val byId = decisions.associateBy { it.tempId }
        val existingNames = repository.lowerCaseNames()
        val finalTitles = mutableSetOf<String>()
        val ignored = mutableListOf<String>()
        val pending = mutableListOf<Pending>()

        for (entry in staged.recipes) {
            val decision = byId[entry.tempId]
            if (decision == null || decision.action == ImportAction.SKIP) {
                ignored += entry.title
                continue
            }
            val submitted = Py.strip(decision.title)
            val entryKey = entry.title.lowercase(Locale.ROOT)
            val title = if (entry.kind == StagedKind.DUPLICATE) {
                // The title starts as the existing name; only a real change imports a copy.
                if (submitted.isEmpty() || submitted.lowercase(Locale.ROOT) == entryKey) {
                    ignored += entry.title
                    continue
                }
                submitted
            } else {
                submitted.ifEmpty { entry.title }
            }
            val titleKey = title.lowercase(Locale.ROOT)
            val renamed = titleKey != entryKey
            val clash = if (entry.kind == StagedKind.UPDATE) {
                // An update may keep (or take) the name of the very recipe it replaces, but no other.
                val own = Orf.cleanNone(entry.doc["recipe_uuid"])?.let { Py.str(it) }?.let { repository.idForUuid(it) }
                val ownName = own?.let { repository.doc(it)?.get("recipe_name") }?.let { Py.str(it).lowercase(Locale.ROOT) }
                (titleKey in existingNames && titleKey != ownName) || titleKey in finalTitles
            } else {
                // Checked whether or not it was renamed: an un-renamed NEW title that is now taken
                // means the library changed since staging (or this import was already confirmed).
                if (!renamed && entry.kind == StagedKind.NEW && titleKey in existingNames) {
                    throw ImportValidationException(
                        "'$title' is already in your library. The library changed since this import was read; read the file again.",
                    )
                }
                titleKey in existingNames || titleKey in finalTitles
            }
            if (clash) {
                throw ImportValidationException(
                    "Can't import '${entry.title}' as '$title': that name is already in your library. Pick another name" +
                        if (entry.kind == StagedKind.DUPLICATE) ", or leave the title as it was to ignore." else ".",
                )
            }
            finalTitles += titleKey
            pending += prepare(entry, decision, title, renamed, staged.images)
        }

        // On failure every photo written is removed and every one it replaced is put back.
        val written = mutableListOf<WrittenImage>()
        try {
            writeImages(pending.flatMap { it.images }, written)
            repository.saveAll(pending.map { it.doc })
        } catch (e: Throwable) {
            rollBack(written)
            throw e
        }
        written.forEach { it.backup?.delete() }
        ImportOutcome(
            imported = pending.count { !it.update },
            updated = pending.count { it.update },
            ignored = ignored,
            errors = staged.errors,
        )
    }

    private suspend fun prepare(
        entry: StagedRecipe,
        decision: ImportDecision,
        title: String,
        renamed: Boolean,
        bundleImages: Map<String, File>,
    ): Pending {
        @Suppress("UNCHECKED_CAST")
        val doc = RecipeYaml.deepCopy(entry.doc) as YamlMap
        val update = entry.kind == StagedKind.UPDATE

        val rows = decision.ingredientRows
        if (rows != null) {
            val oldValue = doc["ingredients"]
            @Suppress("UNCHECKED_CAST")
            val old = if (Py.truthy(oldValue)) oldValue as? List<Any?> ?: emptyList() else emptyList()
            val rebuilt = OrfEditing.ingredientsFromRows(rows, old)
            if (rebuilt.isEmpty()) throw ImportValidationException("'$title' needs at least one ingredient.")
            doc["ingredients"] = rebuilt
        }

        doc["category"] = Py.strip(decision.category).ifEmpty { "None" }
        doc["subcategory"] = Py.strip(decision.subcategory).ifEmpty { "None" }
        val servingsText = Py.strip(decision.servingsAmount)
        val servings = if (servingsText.isEmpty()) null else Amounts.parseAmount(servingsText)
        if (servings != null && servings > Fraction.ZERO) {
            doc["yields"] = mutableListOf<Any?>(
                linkedMapOf<Any?, Any?>(
                    "amount" to if (servings.denominator == BigInteger.ONE) Py.intValue(servings.numerator) else Amounts.formatAmount(servings),
                    "unit" to Py.strip(decision.servingsUnit).ifEmpty { "servings" },
                ),
            )
        }

        var identityReset = false
        if (renamed) {
            doc["recipe_name"] = title
            // A renamed copy is a new recipe, never an update of the one it collided with.
            if (!update) {
                doc["recipe_uuid"] = newUuid()
                identityReset = true
            }
        }

        val images = prepareImage(doc, identityReset, update, bundleImages)
        try {
            Orf.parse(RecipeYaml.dump(doc))
        } catch (e: RecipeFormatException) {
            throw ImportValidationException("'$title' could not be imported: ${e.message}")
        }
        return Pending(doc, update, images)
    }

    // Photo files to write for this recipe, fixing its image field on the way.
    private suspend fun prepareImage(
        doc: YamlMap,
        identityReset: Boolean,
        update: Boolean,
        bundleImages: Map<String, File>,
    ): List<Pair<String, File>> {
        val name = Orf.cleanNone(doc["image"])?.let { Py.str(it) } ?: return emptyList()
        if (!RecipeRepository.isSafeImageName(name)) {
            doc.remove("image")
            return emptyList()
        }
        fun sourceOf(file: String): File? = (bundleImages[file] ?: File(imagesDir, file)).takeIf { it.isFile }
        val main = sourceOf(name)
        if (main == null) {
            doc.remove("image")
            return emptyList()
        }
        if (!identityReset && bundleImages[name] == null) return emptyList()

        val extension = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        val target = when {
            identityReset -> Py.str(doc["recipe_uuid"]) + extension
            // A bundled photo must not replace a library file of the same name that
            // belongs to another recipe; it is named after this recipe instead.
            takenByAnother(name, doc, update) -> ensureUuid(doc) + extension
            else -> name
        }
        doc["image"] = target
        val files = mutableListOf(target to main)
        val thumb = RecipeRepository.thumbName(name)
        if (thumb != name) sourceOf(thumb)?.let { files += RecipeRepository.thumbName(target) to it }
        return files
    }

    // True when [name] (or its thumbnail) is already in the photo folder and is not
    // the photo of the very recipe this update replaces.
    private suspend fun takenByAnother(name: String, doc: YamlMap, update: Boolean): Boolean {
        val thumb = RecipeRepository.thumbName(name)
        val exists = File(imagesDir, name).exists() || (thumb != name && File(imagesDir, thumb).exists())
        if (!exists) return false
        if (!update) return true
        val ownImage = Orf.cleanNone(doc["recipe_uuid"])?.let { Py.str(it) }
            ?.let { repository.idForUuid(it) }
            ?.let { repository.doc(it) }
            ?.let { Orf.cleanNone(it["image"]) }
            ?.let { Py.str(it) }
        return ownImage != name
    }

    // The recipe's uuid, giving it one first (as the first key, as the repository does) if it has none.
    private fun ensureUuid(doc: YamlMap): String {
        Orf.cleanNone(doc["recipe_uuid"])?.let { Py.str(it) }?.takeIf { it.isNotEmpty() }?.let { return it }
        val uuid = newUuid()
        if (doc.containsKey("recipe_uuid")) {
            doc["recipe_uuid"] = uuid
        } else {
            val rest = LinkedHashMap(doc)
            doc.clear()
            doc["recipe_uuid"] = uuid
            doc.putAll(rest)
        }
        return uuid
    }

    // A photo written by this import, and a copy of the file it replaced (null if none).
    private class WrittenImage(val target: File, val backup: File?)

    // Writes each file via a temp file. A file it replaces is first COPIED aside, for
    // rollback: the original stays in place until the new one atomically replaces it,
    // so a process killed mid-import never leaves a recipe without its photo. A stray
    // copy is deleted at the next start (AppContainer.startupCleanup).
    private fun writeImages(files: List<Pair<String, File>>, written: MutableList<WrittenImage>) {
        if (files.isEmpty()) return
        imagesDir.mkdirs()
        for ((index, file) in files.withIndex()) {
            val (name, source) = file
            val target = File(imagesDir, name)
            val temp = File(imagesDir, "$name.tmp")
            try {
                source.copyTo(temp, overwrite = true)
                val backup = if (target.exists()) File(imagesDir, "$name.$index.bak").also { target.copyTo(it, overwrite = true) } else null
                written += WrittenImage(target, backup)
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally {
                temp.delete()
            }
        }
    }

    private fun rollBack(written: List<WrittenImage>) {
        for (image in written.asReversed()) {
            try {
                val backup = image.backup
                if (backup != null) {
                    Files.move(backup.toPath(), image.target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } else {
                    image.target.delete()
                }
            } catch (e: IOException) {
                // Keep restoring the rest; the original error is what the caller sees.
            }
        }
    }
}
