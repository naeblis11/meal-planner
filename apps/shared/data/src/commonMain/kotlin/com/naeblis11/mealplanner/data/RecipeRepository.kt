package com.naeblis11.mealplanner.data

import com.naeblis11.mealplanner.domain.JsonTree
import com.naeblis11.mealplanner.domain.Orf
import com.naeblis11.mealplanner.domain.OrfEditing
import com.naeblis11.mealplanner.domain.Py
import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.YamlMap
import com.naeblis11.mealplanner.folder.FileStamp
import com.naeblis11.mealplanner.folder.LIBRARY_BLOCKED_MESSAGE
import com.naeblis11.mealplanner.folder.LibraryWriteException
import com.naeblis11.mealplanner.folder.RecipeFileStore
import com.naeblis11.mealplanner.folder.isLibraryBlocked
import com.naeblis11.mealplanner.folder.librarySaveFailedMessage
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.TreeSet
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

/** P7-R10f: a save or delete refused because the recipe's file now holds another recipe (a moved library). */
const val FILE_HOLDS_ANOTHER_RECIPE = "This recipe's file now holds another recipe; nothing was saved."

/** P7-R10f: a save refused because the recipe is held (missing from the folder, or from a moved folder). */
const val RECIPE_HELD =
    "This recipe is waiting under Needs attention (its file is missing, or the recipe folder changed); nothing was saved."

/** A save refused because the recipe changed outside the app (a hand edit, or its file removed) since the form opened. */
class RecipeChangedException : IOException(MESSAGE) {
    companion object {
        const val MESSAGE = "This recipe was changed outside the app while you were editing. Close it and open it again to see the change."
    }
}

/** Why a save of a recipe whose file is missing from the recipe folder was refused. */
fun missingFileMessage(fileName: String): String =
    "$fileName is missing from the recipe folder, so this recipe can't be saved. Put the file back, or see Needs attention."

/**
 * The recipe library. A recipe is its ORF YAML: saving dumps the map to
 * YAML, parses it the way the Pi does, and rebuilds the index rows from
 * that parse, like writing the file and syncing on the Pi. YAML work and
 * file IO run on [dispatcher], so callers may be on the main thread.
 *
 * On the desktop, [files] is the recipe folder and the source of truth. A save writes each recipe's
 * file first (atomically), then its index row, which records the file's name and hash. A delete moves
 * the file to the Recycle Bin.
 */
class RecipeRepository(
    private val db: AppDatabase,
    private val imagesDir: File,
    private val newUuid: () -> String = { UUID.randomUUID().toString() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** The desktop's recipe folder; null on Android, where Room alone holds the library. */
    private val files: RecipeFileStore? = null,
    /** The desktop's notice that Windows refuses the library (P7-R10b); told by [libraryRefused]. */
    private val onLibraryBlocked: () -> Unit = {},
) {
    private val dao = db.recipeDao()

    /**
     * What to show for a failed write to the desktop's library outside the recipe files (a photo save, an import):
     * LIBRARY_BLOCKED_MESSAGE when Windows refused it (isLibraryBlocked, P7-R11b; the desktop's notice is told), else
     * "Couldn't save to Documents\Meal Planner: <reason>" with no other path (P7-R10b). Null on Android, which has no library
     * folder: the caller says what it always said.
     */
    fun libraryWriteMessage(e: IOException): String? {
        if (files == null) return null
        if (isLibraryBlocked(e)) {
            onLibraryBlocked()
            return LIBRARY_BLOCKED_MESSAGE
        }
        return if (e is LibraryWriteException) e.message else librarySaveFailedMessage(e)
    }

    // Serialises every write, so a read-modify-write (a rating, a photo) can't be
    // interleaved with another and lose its change. Not re-entrant: hold it once.
    private val writeLock = Mutex()

    /** Test seam: runs as soon as a desktop save's index transaction has committed. */
    internal var afterCommit: suspend () -> Unit = {}

    /**
     * Saves one recipe; see [saveAll]. [baseHash] is the file hash the recipe was opened at ([docWithBase]):
     * when given, the save is refused with [RecipeChangedException] if the recipe has changed outside the app
     * since then, so the edit form never saves over a hand edit. Null skips the check (always on Android).
     */
    suspend fun save(doc: YamlMap, baseHash: String? = null): Long =
        writeLock.withLock { saveAllLocked(listOf(doc), listOf(baseHash)) }.single()

    /**
     * Saves recipes in one transaction. Each gets a recipe_uuid if it has
     * none (written into its map, first, as the Pi inserts the line), and
     * all are parsed before anything is written, so one bad recipe leaves
     * the library untouched. Returns the row ids, in order.
     */
    suspend fun saveAll(docs: List<YamlMap>): List<Long> = writeLock.withLock { saveAllLocked(docs) }

    // [bases] holds each doc's baseHash (see [save]); null entries are not checked.
    private suspend fun saveAllLocked(docs: List<YamlMap>, bases: List<String?> = docs.map { null }): List<Long> = withContext(dispatcher) {
        val prepared = docs.map { doc ->
            ensureUuid(doc)
            val raw = RecipeYaml.dump(doc)
            raw to Orf.parse(raw)
        }
        val store = files ?: return@withContext db.inTransaction { prepared.map { (raw, parsed) -> upsert(raw, parsed) } }
        // Desktop: the files first, then the index, all or nothing. If a write or the index transaction
        // fails, every file this call wrote is put back as it was (a created one is deleted), so a retry
        // can't leave a second copy of the recipe in the folder; the rolled-back index still matches.
        val before = mutableListOf<Pair<String, ByteArray?>>()
        var committed = false
        try {
            val stamps = writeFiles(store, prepared, bases, before)
            val ids = db.inTransaction { prepared.mapIndexed { i, (raw, parsed) -> upsert(raw, parsed, stamps[i]) } }
            committed = true
            afterCommit()
            ids
        } catch (e: Throwable) {
            // Once the index has committed, the files are what it records (a cancel landing just then, say):
            // putting them back would leave rows that no longer match their files, and lose the save.
            if (!committed) undoWrites(store, before, e)
            throw e
        }
    }

    // An existing recipe keeps its file name. A new one gets `<slug>.yaml` (or `-2`, `-3`, ...), never a
    // name the index or the folder already has. Each file's bytes are recorded in [before] before it is written.
    // A file whose bytes no longer hash to its row's file_hash was changed outside the app (a hand edit the
    // folder sync hasn't picked up yet): it is never overwritten, and the whole save stops. So does a save whose
    // base hash isn't the row's any more: a hand edit the sync has indexed (or a removal) since the form opened.
    private suspend fun writeFiles(
        store: RecipeFileStore,
        prepared: List<Pair<String, Map<String, Any?>>>,
        bases: List<String?>,
        before: MutableList<Pair<String, ByteArray?>>,
    ): List<FileStamp> {
        // In any letter case: a row's name can't be taken by a new file whose upsert would then find that row by name.
        val taken = dao.fileNames().toCollection(TreeSet(String.CASE_INSENSITIVE_ORDER))
        val index = dao.fileIndex().associateBy { it.recipeUuid }
        return prepared.mapIndexed { i, (raw, parsed) ->
            val row = index[Py.str(parsed["recipe_uuid"])]?.takeIf { it.fileName != null }
            val base = bases[i]
            if (base != null && row?.fileHash != base) throw RecipeChangedException()
            val name = row?.fileName ?: OrfEditing.uniqueFilename(Py.str(parsed["name"]), taken, store::exists)
            if (row != null) {
                // P7-R10f, before anything is written: never over a file that now holds another recipe (a moved
                // library), and never for a recipe held under Needs attention.
                if (holdsAnotherRecipe(store, name, row.recipeUuid)) throw IOException(FILE_HOLDS_ANOTHER_RECIPE)
                if (isHeld(row.id)) throw IOException(RECIPE_HELD)
            }
            if (before.none { it.first == name }) {
                val current = store.read(name)
                // Still indexed, but its file is gone: removed by hand (the folder sync removes it in a moment) or
                // held from a mass removal. Writing it would bring back a file the user took away.
                if (row != null && current == null) throw IOException(missingFileMessage(name))
                if (row != null && current != null && sha256(current) != row.fileHash) {
                    throw IOException("$name was changed outside the app; it will reload in a moment. Try again after it does.")
                }
                before += name to current
            }
            FileStamp(name, store.write(name, raw))
        }
    }

    /**
     * P7-R10f: the desktop's folder sync says which rows are held (missing from the folder, or from a moved one); a save
     * to one is refused. Read under the write lock, which the sync holds while it changes them. False on Android.
     */
    @Volatile
    var isHeld: suspend (Long) -> Boolean = { false }

    // True when [name] exists and holds a recipe_uuid other than [uuid]. A file that can't be read as YAML says
    // nothing here; the hash check after this refuses it anyway.
    private fun holdsAnotherRecipe(store: RecipeFileStore, name: String, uuid: String): Boolean {
        val bytes = store.read(name) ?: return false
        val found = try {
            (RecipeYaml.load(bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")) as? Map<*, *>)
                ?.get("recipe_uuid")?.let { Orf.cleanNone(it) }?.let { Py.str(it) }
        } catch (e: Exception) {
            null
        }
        return found != null && found != uuid
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // Newest first, and past any failure, so one file that can't be restored doesn't strand the rest.
    private fun undoWrites(store: RecipeFileStore, before: List<Pair<String, ByteArray?>>, cause: Throwable) {
        for ((name, bytes) in before.asReversed()) {
            try {
                store.restore(name, bytes)
            } catch (e: Exception) {
                cause.addSuppressed(e)
            }
        }
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

    // The Pi's _write_recipe: find by uuid (then, for a file, by its name), replace the row and its children.
    // [adopt] says which row found by the file's name may be taken over (P7-R10d, P7-R10g): none in a moved library,
    // where a file with a new uuid is a new recipe, and never a row held from an accepted move.
    private suspend fun upsert(raw: String, parsed: Map<String, Any?>, file: FileStamp? = null, adopt: (Long) -> Boolean = { true }): Long {
        val uuid = Py.str(parsed["recipe_uuid"])
        val existing = dao.idForUuid(uuid) ?: file?.let { dao.idForFileName(it.name) }?.takeIf(adopt)
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
            fileName = file?.name,
            fileHash = file?.hash,
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

    /** Runs [block] holding the write lock on the repository's dispatcher: the desktop folder sync, so it never interleaves with a save. */
    internal suspend fun <T> exclusive(block: suspend () -> T): T = writeLock.withLock { withContext(dispatcher) { block() } }

    /** Returns once no save, delete or folder sync is running: the desktop waits on it before closing the database. */
    suspend fun awaitWrites() {
        exclusive {}
    }

    /** Indexes a recipe file as the folder sync read it. Call inside [exclusive] and a transaction. */
    internal suspend fun indexFile(raw: String, parsed: Map<String, Any?>, file: FileStamp, adopt: (Long) -> Boolean = { true }): Long =
        upsert(raw, parsed, file, adopt)

    /**
     * Removes a recipe's rows: its planned meals go with it, as in the Pi's sync when a recipe file goes.
     * Photos are not touched here (the folder sync keeps them, as recipe_sync.py does). Call inside a transaction.
     */
    internal suspend fun unindex(id: Long) {
        dao.deletePlanEntries(id)
        dao.deleteIngredients(id)
        dao.deleteSteps(id)
        dao.deleteRecipe(id)
    }

    /** The recipe's YAML as an editable map, or null if it doesn't exist. */
    suspend fun doc(id: Long): YamlMap? = docWithBase(id)?.first

    /**
     * The recipe's YAML as an editable map, with the hash of the file it was read from (null on Android,
     * where there is no file), or null if it doesn't exist. Pass the hash back to [save] as its baseHash.
     */
    suspend fun docWithBase(id: Long): Pair<YamlMap, String?>? = withContext(dispatcher) {
        val row = dao.recipe(id) ?: return@withContext null
        @Suppress("UNCHECKED_CAST")
        (RecipeYaml.load(row.rawYaml) as YamlMap) to row.fileHash
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

    /** app.py's recipe_set_category: both fields trimmed, and a blank one written as None, as the Pi does. */
    suspend fun setCategory(id: Long, category: String, subcategory: String) {
        writeLock.withLock {
            val doc = doc(id) ?: return
            doc["category"] = Py.strip(category).ifEmpty { "None" }
            doc["subcategory"] = Py.strip(subcategory).ifEmpty { "None" }
            saveAllLocked(listOf(doc))
        }
    }

    /** How many meals [id] is planned for; the delete confirmation says so. */
    fun plannedCount(id: Long): Flow<Int> = dao.observePlannedCount(id)

    /** Deletes the recipe and its photo files, unless another recipe uses the same photo. */
    suspend fun delete(id: Long): Unit = writeLock.withLock { withContext(dispatcher) { deleteLocked(id) } }

    private suspend fun deleteLocked(id: Long) {
        val row = dao.recipe(id) ?: return
        // P7-R10f: never the file of another recipe (its name now holds one, in a moved library).
        val store = files
        val fileName = row.fileName
        if (store != null && fileName != null && holdsAnotherRecipe(store, fileName, row.recipeUuid)) {
            throw IOException(FILE_HOLDS_ANOTHER_RECIPE)
        }
        // Desktop: the file goes to the Recycle Bin first, so a failure there leaves the recipe as it was.
        row.fileName?.let { name -> files?.trash(name) }
        db.inTransaction { unindex(id) }
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
