package com.naeblis11.mealplanner.data

import androidx.room.withTransaction
import com.naeblis11.mealplanner.domain.JsonTree
import com.naeblis11.mealplanner.domain.Orf
import com.naeblis11.mealplanner.domain.Py
import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.YamlMap
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A recipe with its ingredient and step rows, for the recipe page. */
data class RecipeDetail(
    val recipe: RecipeEntity,
    val ingredients: List<RecipeIngredientEntity>,
    val steps: List<RecipeStepEntity>,
)

/**
 * The recipe library. A recipe is its ORF YAML: saving dumps the map to
 * YAML, parses it the way the Pi does, and rebuilds the index rows from
 * that parse, like writing the file and syncing on the Pi. YAML work and
 * file IO run on [dispatcher], so callers may be on the main thread.
 */
class RecipeRepository(
    private val db: AppDatabase,
    private val imagesDir: File,
    private val newUuid: () -> String = { UUID.randomUUID().toString() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val dao = db.recipeDao()

    // Serialises every write, so a read-modify-write (a rating, a photo) can't be
    // interleaved with another and lose its change. Not re-entrant: hold it once.
    private val writeLock = Mutex()

    /** Saves one recipe; see [saveAll]. */
    suspend fun save(doc: YamlMap): Long = saveAll(listOf(doc)).single()

    /**
     * Saves recipes in one transaction. Each gets a recipe_uuid if it has
     * none (written into its map, first, as the Pi inserts the line), and
     * all are parsed before anything is written, so one bad recipe leaves
     * the library untouched. Returns the row ids, in order.
     */
    suspend fun saveAll(docs: List<YamlMap>): List<Long> = writeLock.withLock { saveAllLocked(docs) }

    private suspend fun saveAllLocked(docs: List<YamlMap>): List<Long> = withContext(dispatcher) {
        val prepared = docs.map { doc ->
            ensureUuid(doc)
            val raw = RecipeYaml.dump(doc)
            raw to Orf.parse(raw)
        }
        db.withTransaction { prepared.map { (raw, parsed) -> upsert(raw, parsed) } }
    }

    private fun ensureUuid(doc: YamlMap) {
        if (Py.truthy(Orf.cleanNone(doc["recipe_uuid"]))) return
        val uuid = newUuid()
        if (doc.containsKey("recipe_uuid")) {
            doc["recipe_uuid"] = uuid
        } else {
            val rest = LinkedHashMap(doc)
            doc.clear()
            doc["recipe_uuid"] = uuid
            doc.putAll(rest)
        }
    }

    // The Pi's _write_recipe: find by uuid, replace the row and its children.
    private suspend fun upsert(raw: String, parsed: Map<String, Any?>): Long {
        val uuid = Py.str(parsed["recipe_uuid"])
        val existing = dao.idForUuid(uuid)
        val entity = RecipeEntity(
            id = existing ?: 0,
            recipeUuid = uuid,
            name = Py.str(parsed["name"]),
            author = text(parsed["author"]),
            sourceAuthorsJson = JsonTree.encode(parsed["source_authors"]),
            sourceUrl = text(parsed["source_url"]),
            sourceBookJson = JsonTree.encode(parsed["source_book"]),
            ovenTempJson = JsonTree.encode(parsed["oven_temp"]),
            ovenFan = text(parsed["oven_fan"]),
            ovenTime = text(parsed["oven_time"]),
            yieldsJson = JsonTree.encode(parsed["yields"]),
            notesJson = JsonTree.encode(parsed["notes"]),
            category = text(parsed["category"]),
            subcategory = text(parsed["subcategory"]),
            imageFilename = text(parsed["image"]),
            rating = parsed["rating"] as Int?,
            rawYaml = raw,
        )
        val id = if (existing == null) {
            dao.insertRecipe(entity)
        } else {
            dao.deleteIngredients(existing)
            dao.deleteSteps(existing)
            dao.updateRecipe(entity)
            existing
        }

        @Suppress("UNCHECKED_CAST")
        val ingredients = parsed["ingredients"] as List<Map<String, Any?>>
        dao.insertIngredients(
            ingredients.mapIndexed { order, ingredient ->
                val amounts = ingredient["amounts"] as List<*>
                @Suppress("UNCHECKED_CAST")
                val first = amounts.firstOrNull() as Map<Any?, Any?>? ?: emptyMap()
                RecipeIngredientEntity(
                    recipeId = id,
                    orderNum = order,
                    name = Py.str(ingredient["name"]),
                    usdaNum = text(ingredient["usda_num"]),
                    amount = text(first["amount"]),
                    unit = text(first["unit"]),
                    section = text(ingredient["section"]),
                    amountsJson = JsonTree.toJson(amounts).toString(),
                    processingJson = JsonTree.encode(ingredient["processing"]),
                    notesJson = JsonTree.encode(ingredient["notes"]),
                    substitutionsJson = JsonTree.encode(ingredient["substitutions"]),
                )
            },
        )

        @Suppress("UNCHECKED_CAST")
        val steps = parsed["steps"] as List<Map<String, Any?>>
        dao.insertSteps(
            steps.mapIndexed { order, step ->
                RecipeStepEntity(
                    recipeId = id,
                    orderNum = order,
                    stepText = Py.str(step["step_text"]),
                    stepNotesJson = JsonTree.encode(step["notes"]),
                    haccpJson = JsonTree.encode(step["haccp"]),
                )
            },
        )
        return id
    }

    // A TEXT column holds whatever YAML gave, as text (SQLite would store 2 as "2").
    private fun text(value: Any?): String? = value?.let { Py.str(it) }

    /** The recipe's YAML as an editable map, or null if it doesn't exist. */
    suspend fun doc(id: Long): YamlMap? = withContext(dispatcher) {
        val row = dao.recipe(id) ?: return@withContext null
        @Suppress("UNCHECKED_CAST")
        RecipeYaml.load(row.rawYaml) as YamlMap
    }

    /** Sets 1..5 stars, or clears with 0 (written as `rating: None`, as the Pi does). */
    suspend fun setRating(id: Long, rating: Int) {
        require(rating in 0..5) { "A rating is 0 to 5 stars." }
        writeLock.withLock {
            val doc = doc(id) ?: return
            doc["rating"] = if (rating == 0) "None" else rating
            saveAllLocked(listOf(doc))
        }
    }

    /** How many meals [id] is planned for; the delete confirmation says so. */
    fun plannedCount(id: Long): Flow<Int> = dao.observePlannedCount(id)

    /** Deletes the recipe and its photo files, unless another recipe uses the same photo. */
    suspend fun delete(id: Long): Unit = writeLock.withLock { withContext(dispatcher) { deleteLocked(id) } }

    private suspend fun deleteLocked(id: Long) {
        val row = dao.recipe(id) ?: return
        db.withTransaction {
            // As the Pi's sync does when a recipe file goes: its planned meals go with it.
            dao.deletePlanEntries(id)
            dao.deleteIngredients(id)
            dao.deleteSteps(id)
            dao.deleteRecipe(id)
        }
        row.imageFilename?.let { deleteImageIfUnused(it) }
    }

    /** Points the recipe at [imageFilename]; an old, differently named photo nothing else uses is deleted. */
    suspend fun setImage(id: Long, imageFilename: String): Unit = writeLock.withLock {
        withContext(dispatcher) { setImageLocked(id, imageFilename) }
    }

    private suspend fun setImageLocked(id: Long, imageFilename: String) {
        val row = dao.recipe(id) ?: return
        @Suppress("UNCHECKED_CAST")
        val doc = RecipeYaml.load(row.rawYaml) as YamlMap
        doc["image"] = imageFilename
        saveAllLocked(listOf(doc))
        val old = row.imageFilename
        if (old != null && old != imageFilename) deleteImageIfUnused(old)
    }

    /** Clears the recipe's photo (`image: None`, as the Pi writes) and deletes the files if nothing else uses them. */
    suspend fun removeImage(id: Long): Unit = writeLock.withLock { withContext(dispatcher) { removeImageLocked(id) } }

    private suspend fun removeImageLocked(id: Long) {
        val row = dao.recipe(id) ?: return
        @Suppress("UNCHECKED_CAST")
        val doc = RecipeYaml.load(row.rawYaml) as YamlMap
        doc["image"] = "None"
        saveAllLocked(listOf(doc))
        row.imageFilename?.let { deleteImageIfUnused(it) }
    }

    private suspend fun deleteImageIfUnused(name: String) {
        if (isSafeImageName(name) && dao.countImageUsers(name) == 0) {
            File(imagesDir, name).delete()
            File(imagesDir, thumbName(name)).delete()
        }
    }

    /** Lower-cased names of every recipe, for duplicate checks (Python's `.lower()`). */
    suspend fun lowerCaseNames(): MutableSet<String> = dao.names().mapTo(HashSet()) { it.lowercase(Locale.ROOT) }

    suspend fun idForUuid(uuid: String): Long? = dao.idForUuid(uuid)

    suspend fun allRecipes(): List<RecipeEntity> = dao.allRecipes()

    /** The library list, filtered like the Pi's search box; a blank query lists everything. */
    fun summaries(query: String): Flow<List<RecipeSummary>> =
        if (query.isBlank()) dao.observeSummaries() else dao.search("%${query.trim()}%")

    fun imageFile(name: String): File = File(imagesDir, name)

    /** The recipe page's data; emits again after every save, and null once the recipe is deleted. */
    fun observeDetail(id: Long): Flow<RecipeDetail?> = dao.observeRecipe(id)
        .map { row -> row?.let { RecipeDetail(it, dao.ingredients(id), dao.steps(id)) } }
        .distinctUntilChanged()
        .flowOn(dispatcher)

    /** Lower-cased names of every recipe except [id] (all of them when null), for the edit form's name check. */
    suspend fun lowerCaseNamesExcept(id: Long?): Set<String> = withContext(dispatcher) {
        (if (id == null) dao.names() else dao.namesExcept(id)).mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
    }

    companion object {
        private val SAFE_IMAGE_NAME = Regex("[A-Za-z0-9._-]+")

        /** The Pi's `_thumb_filename`: `x.jpg` -> `x_thumb.jpg`. */
        fun thumbName(imageFilename: String): String = imageFilename.replace(".jpg", "_thumb.jpg")

        /** A bare file name: no folders, no `..`. */
        fun isSafeImageName(name: String): Boolean =
            SAFE_IMAGE_NAME.matches(name) && name != "." && name != ".."
    }
}
