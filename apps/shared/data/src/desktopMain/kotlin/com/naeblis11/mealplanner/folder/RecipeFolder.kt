package com.naeblis11.mealplanner.folder

import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.AppMetaEntity
import com.naeblis11.mealplanner.data.RecipeFileRow
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.data.inTransaction
import com.naeblis11.mealplanner.domain.Orf
import com.naeblis11.mealplanner.domain.OrfEditing
import com.naeblis11.mealplanner.domain.Py
import com.naeblis11.mealplanner.domain.RecipeFormatException
import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.YamlPatch
import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The desktop's recipe folder as the source of truth: recipe_sync.sync_recipes, ported. Every
 * `*.yaml` file (in any letter case; `.yml` is ignored) is indexed by its recipe_uuid. A file whose
 * bytes hash to the index's file_hash is skipped as unchanged, which also skips the app's own saves,
 * because RecipeRepository stores the hash of what it wrote. Files that can't be indexed go on
 * [problems]; nothing is guessed.
 *
 * Removal is careful, because unindexing a recipe also deletes its planned meals, which no file can bring back.
 * Additions and updates apply at once, but a recipe whose file is gone is only removed by a later sync, at least
 * [REMOVAL_DELAY_MILLIS] after the first one that missed it, that still finds it gone (R1); [onRemovalsPending]
 * asks the owner for that sync. And when most of the library is missing at once (more than half of the indexed
 * files and more than 3 of them, or every one of them: a library moved out in Explorer, a folder that came back
 * before its files, one being copied back in file by file), nothing is removed: the files are held, listed under
 * Needs attention, until they come back or the user chooses [confirmMassRemoval] (R2).
 */
class RecipeFolder(
    val dir: File,
    private val db: AppDatabase,
    private val recipes: RecipeRepository,
    private val newUuid: () -> String = { UUID.randomUUID().toString() },
    private val opener: (File) -> Unit = { Desktop.getDesktop().open(it) },
    /** Milliseconds that only go forward, for how long a removal has waited; tests pass their own. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Told after a sync that left removals waiting, so the owner syncs again in a few seconds (R1). */
    private val onRemovalsPending: () -> Unit = {},
    /**
     * Makes the folder on a first sync (nothing indexed from a file yet), the only time a sync may. The desktop makes
     * it through NIO, so a folder Windows refuses (Controlled folder access) is logged and then listed as missing.
     */
    private val makeDir: (File) -> Unit = { it.mkdirs() },
) : RecipeFolderStatus {
    /** What one sync did, for tests and the log. */
    data class SyncResult(val indexed: Int, val unchanged: Int, val removed: Int)

    private val _problems = MutableStateFlow<List<RecipeFileProblem>>(emptyList())
    override val problems: StateFlow<List<RecipeFileProblem>> = _problems.asStateFlow()

    // Unreadable amounts by file hash, so an unchanged file keeps its notes without being parsed again.
    private val amountNotes = HashMap<String, List<String>>()

    // R1: index rows whose file is gone, by row id, with when a sync first missed it. Read and replaced only under
    // recipes.exclusive (by syncLocked), and only once a sync's transaction has committed.
    private var pendingSince: Map<Long, Long> = emptyMap()

    // R2: index rows whose files went missing in a mass removal; kept until their files are back or the user confirms.
    private var heldRows: Set<Long> = emptySet()

    init {
        // P7-R10f: a held recipe (R2, or from a move) can't be saved; the repository asks under its write lock.
        recipes.isHeld = { id -> id in heldRows || id in moveHeldRows() }
    }

    /** True when the last sync left removals waiting for a later sync to confirm them (R1). */
    @Volatile
    var removalsPending: Boolean = false
        private set

    /** One listed file as the first pass read it. [uuid] is the recipe_uuid it holds now; null when it can't be read. */
    private sealed class Read(val file: File, val uuid: String?)

    /** Its bytes hash to its index row's file_hash: it holds that row's recipe, as indexed. */
    private class Unchanged(file: File, uuid: String, val hash: String, val bytes: ByteArray) : Read(file, uuid)

    private class Parsed(file: File, uuid: String, val hash: String, val raw: String, val parsed: Map<String, Any?>) : Read(file, uuid)

    private class Broken(file: File, val problem: RecipeFileProblem) : Read(file, null)

    /** Indexes the folder. Safe to call at any time: it waits for a save in progress. */
    suspend fun sync(): SyncResult = recipes.exclusive { syncLocked() }

    /**
     * "Remove them from the app" for files held back from a mass removal (R2): lists the folder again, under the
     * lock, and removes the held recipes among [ids] (those the confirmation counted) whose files are still gone.
     * One that came back meanwhile stays, and so does one that went (or was held) after the confirmation opened.
     */
    override suspend fun confirmMassRemoval(ids: Set<Long>) {
        recipes.exclusive { syncLocked(confirmHeld = ids) }
    }

    /**
     * "Use the new folder" (P7-R10e) only accepts the current folder as the library; it never removes anything. Every
     * row held because the folder changed moves into the R2 hold (even below its threshold, as they came from a move),
     * so they still need "Remove them from the app" and its confirmation with counts. A genuine R2 hold is kept as it
     * is. Does nothing when the stored folder is already this one.
     */
    override suspend fun useNewFolder() {
        recipes.exclusive {
            val current = canonicalDir()
            val stored = db.appMetaDao().get(LIBRARY_PATH_KEY)
            if (stored == null || stored.equals(current, ignoreCase = true)) return@exclusive
            db.inTransaction {
                putMoveHeldRows(moveHeldRows() + heldRows)
                db.appMetaDao().put(AppMetaEntity(LIBRARY_PATH_KEY, current))
            }
            syncLocked()
        }
    }

    // P7-R10e: the rows held since a move was accepted, by row id, in app_meta.
    private suspend fun moveHeldRows(): Set<Long> =
        db.appMetaDao().get(MOVE_HELD_KEY)?.split(',')?.mapNotNullTo(HashSet()) { it.trim().toLongOrNull() }.orEmpty()

    private suspend fun putMoveHeldRows(ids: Set<Long>) {
        db.appMetaDao().put(AppMetaEntity(MOVE_HELD_KEY, ids.sorted().joinToString(",")))
    }

    // The folder as the database remembers it (P7-R10c): its canonical path, or its absolute one if that can't be had.
    private fun canonicalDir(): String = try {
        dir.canonicalPath
    } catch (e: IOException) {
        dir.absolutePath
    }

    override suspend fun heldSummary(): HeldRecipes = recipes.exclusive {
        val ids = heldRows
        HeldRecipes(ids, if (ids.isEmpty()) 0 else db.mealPlanDao().countForRecipes(ids.toList()))
    }

    private suspend fun syncLocked(confirmHeld: Set<Long> = emptySet()): SyncResult {
        val rows = db.recipeDao().fileIndex()
        // The folder is made only while the index has no files. Once it has, a missing folder is one that went
        // away (an offline Documents folder, a deleted one), and an empty one made here would unindex everything.
        if (rows.none { it.fileName != null } && !dir.exists()) {
            try {
                makeDir(dir)
            } catch (e: IOException) {
                // The level the failure names too (a library path): which folder Windows refused.
                val at = (e as? java.nio.file.FileSystemException)?.file?.let { " at $it" }.orEmpty()
                System.err.println("Meal Planner: the recipe folder couldn't be made (${e.javaClass.simpleName}$at): ${dir.path}")
            }
        }
        // null is an I/O error (a redirected Documents folder that is offline, a file where the folder should be).
        // Read as empty, it would unindex every recipe and its planned meals, so the sync stops instead.
        val listed = if (dir.isDirectory) dir.listFiles { file -> file.isFile && file.name.endsWith(".yaml", ignoreCase = true) } else null
        if (listed == null) {
            // The watcher stops once its folder is gone; DesktopApp looks for the folder's return and watches it again.
            // Nothing pending or held changes: the folder can't say which files are there.
            val what = if (dir.exists()) "Can't read the recipe folder" else "The recipe folder is missing"
            val message = "$what: ${dir.path}. Meal Planner will pick it up again when it is back."
            _problems.value = listOf(RecipeFileProblem(dir.name, RecipeFileProblem.Kind.FOLDER, message))
            throw IOException(message)
        }
        // P7-R10c: the database remembers the folder it was made from. A different one (the library moved, or
        // MEAL_PLANNER_DATA_DIR now names another) removes nothing until the user confirms it: every file gone from
        // it is held, as R2 holds a mass removal. New and changed files are indexed as usual.
        val current = canonicalDir()
        val stored = db.appMetaDao().get(LIBRARY_PATH_KEY)
        val movedFrom = stored?.takeIf { !it.equals(current, ignoreCase = true) }
        // P7-R10e: rows held since "Use the new folder" accepted a moved folder; kept in app_meta so a restart can't
        // let them leave without the Remove confirmation.
        val moveHeld = moveHeldRows()
        // Sorted, so which file wins a recipe_uuid clash never depends on the file system's order.
        val files = listed.sortedBy { it.name }
        // Windows file names ignore letter case, so an index row's file is found that way: "soup.yaml"
        // renamed to "Soup.yaml" is the same file, still here, and keeps its row and planned meals.
        // Only "unchanged" wants the exact name, so such a rename is indexed again and the row takes the new name.
        val diskName = files.associate { key(it.name) to it.name }
        val onDisk = diskName.keys

        // Pass 1: what every file holds now. Nothing is written to the index yet.
        val rowByName = rows.mapNotNull { row -> row.fileName?.let { it to row } }.toMap()
        val reads = files.map { read(it, rowByName[it.name]) }
        val held = reads.associate { key(it.file.name) to it.uuid }

        // Pass 2: which file keeps each recipe_uuid. The index's file keeps it while it still holds it, or while
        // it can't be read (a broken edit mustn't let a copy take the id); otherwise the first file, sorted. So
        // swapped or rename-chained files each keep their own recipe, and only a real second copy is listed.
        val winner = HashMap<String, String>()
        for (row in rows) {
            val owner = row.fileName?.let(::key) ?: continue
            if (owner in onDisk && held[owner].let { it == null || it == row.recipeUuid }) winner[row.recipeUuid] = owner
        }
        for (read in reads) read.uuid?.let { winner.putIfAbsent(it, key(read.file.name)) }

        val problems = mutableListOf<RecipeFileProblem>()
        val pending = mutableListOf<Parsed>()
        var unchanged = 0
        for (read in reads) {
            val name = read.file.name
            if (read is Broken) {
                problems += read.problem
                continue
            }
            val uuid = read.uuid!!
            val owner = winner.getValue(uuid)
            if (owner != key(name)) {
                problems += RecipeFileProblem(name, RecipeFileProblem.Kind.DUPLICATE_ID, "Duplicate recipe_uuid $uuid (also used by ${diskName[owner]}).")
                continue
            }
            when (read) {
                is Unchanged -> {
                    unchanged++
                    problems += amountProblems(name, read.hash) { decode(read.bytes) }
                }
                is Parsed -> {
                    problems += amountProblems(name, read.hash) { read.raw }
                    pending += read
                }
                is Broken -> Unit
            }
        }

        // Recipes the index already has go first, so each takes its own row (by uuid) before a new uuid's lookup
        // by file name runs: a new file in a moved recipe's old name never adopts that recipe's row.
        val known = rows.mapTo(HashSet()) { it.recipeUuid }
        val ordered = pending.sortedBy { it.uuid !in known }
        var removed = 0
        var removals = Removals(emptyList(), emptyMap(), emptySet())
        db.inTransaction {
            // P7-R10d: while the folder differs (or rows from a move are still held) a file is matched by uuid only,
            // so a new uuid in a known file name is a new recipe, never a takeover of the held one's row and meals.
            // After acceptance (P7-R10g) only a row still held from the move is kept from that; a uuid changed or
            // blanked by hand in a present file keeps its row, as ever.
            val adopt: (Long) -> Boolean = if (movedFrom != null) { _ -> false } else { id -> id !in moveHeld }
            ordered.forEach { recipes.indexFile(it.raw, it.parsed, FileStamp(it.file.name, it.hash), adopt) }
            // A row is stale when its file now holds another recipe: it goes at once, as the file is there.
            // One edge is left: a recipe renamed AND broken in one go (c.yaml, unreadable) whose old name is
            // reused at once (a.yaml). Its row still names a.yaml, so a.yaml's recipe replaces it here, or takes
            // it over by file name when its uuid is new. The broken file is listed and its text is kept on disk.
            // A row whose file is gone is left to removals() (R1, R2).
            val uuidByFile = pending.associate { key(it.file.name) to it.uuid }
            val gone = mutableListOf<Long>()
            var fileRows = 0
            for (row in db.recipeDao().fileIndex()) {
                val name = row.fileName?.let(::key)
                val replaced = name != null && uuidByFile[name].let { it != null && it != row.recipeUuid }
                // Every file read counts here, unchanged ones too: two rows can share a name while one is held.
                val displaced = name != null && held[name].let { it != null && it != row.recipeUuid }
                // P7-R10g: while moved, or while rows from a move are held, a row that would be replaced is held too.
                if ((displaced || replaced) && (movedFrom != null || moveHeld.isNotEmpty())) {
                    // P7-R10d: its file name now holds another recipe in a moved folder: its own file is as good as
                    // gone, so it is held with the rest, never removed here.
                    fileRows++
                    gone += row.id
                } else if (name == null || replaced) {
                    recipes.unindex(row.id)
                    removed++
                } else {
                    fileRows++
                    if (name !in onDisk) gone += row.id
                }
            }
            removals = if (movedFrom != null) {
                Removals(emptyList(), emptyMap(), gone.toSet())
            } else {
                removals(gone, fileRows, confirmHeld, moveHeld)
            }
            removals.now.forEach { recipes.unindex(it) }
            removed += removals.now.size
            // The first time this database indexes a folder, it remembers which one.
            if (stored == null) db.appMetaDao().put(AppMetaEntity(LIBRARY_PATH_KEY, current))
            // A row from a move stays on the list only while it is held: back, or removed, it drops off.
            val stillMoveHeld = moveHeld.filterTo(HashSet()) { it in removals.held }
            if (stillMoveHeld != moveHeld) putMoveHeldRows(stillMoveHeld)
        }
        // Committed: only now does what waits and what is held change.
        pendingSince = removals.pending
        heldRows = removals.held
        removalsPending = pendingSince.isNotEmpty()
        val heldProblem = if (movedFrom != null) {
            RecipeFileProblem(dir.name, RecipeFileProblem.Kind.FOLDER, folderMovedMessage(movedFrom, current), heldRows.size, movedFrom)
        } else {
            heldRows.size.takeIf { it > 0 }?.let {
                RecipeFileProblem(dir.name, RecipeFileProblem.Kind.FOLDER, missingFilesMessage(it), missingFiles = it)
            }
        }
        _problems.value = listOfNotNull(heldProblem) + problems
        if (removalsPending) onRemovalsPending()
        return SyncResult(indexed = pending.size, unchanged = unchanged, removed = removed)
    }

    /** What a sync does with the rows whose files are gone: remove [now], wait on [pending], hold [held]. */
    private class Removals(val now: List<Long>, val pending: Map<Long, Long>, val held: Set<Long>)

    // R1 and R2 for the rows whose files are [gone] (row ids), out of [fileRows] indexed files. A row whose file is
    // back drops out of both. [confirmHeld] is the user's "Remove them from the app", with the rows its confirmation
    // counted: those still held and still gone go now; any other row stays held or waits as usual.
    private fun removals(gone: List<Long>, fileRows: Int, confirmHeld: Set<Long>, moveHeld: Set<Long> = emptySet()): Removals {
        val goneIds = gone.toSet()
        // Rows from an accepted move (P7-R10e) are held like R2's, whatever the threshold.
        val stillHeld = (heldRows + moveHeld).filterTo(HashSet()) { it in goneIds }
        val now = stillHeld.filter { it in confirmHeld }.toMutableList()
        stillHeld.removeAll(now.toSet())
        val rest = gone.filter { it !in now }
        // Held stays held (a folder refilling slowly never drops below the line and loses the rest), and a new
        // mass removal is held whole.
        if (stillHeld.isNotEmpty() || isMassRemoval(rest.size, fileRows - now.size)) {
            return Removals(now, emptyMap(), stillHeld + rest)
        }
        val time = clock()
        val pending = HashMap<Long, Long>()
        for (id in rest) {
            val since = pendingSince[id] ?: time
            if (time - since >= REMOVAL_DELAY_MILLIS) now += id else pending[id] = since
        }
        return Removals(now, pending, emptySet())
    }

    // R2: more than half of the indexed files and more than 3 of them, or every one of them (an emptied folder).
    private fun isMassRemoval(gone: Int, fileRows: Int): Boolean =
        gone > 0 && (gone == fileRows || (gone > MASS_REMOVAL_MIN && gone * 2 > fileRows))

    // Pass 1 for one file: unchanged (by hash, under its exact name), parsed, or broken. A file without a
    // recipe_uuid gets one here, written into it by a one-line patch; a new uuid can't clash with anything.
    private fun read(file: File, row: RecipeFileRow?): Read {
        val name = file.name
        return try {
            var bytes = file.readBytes()
            var hash = AtomicFiles.sha256(bytes)
            if (row != null && row.fileHash == hash) return Unchanged(file, row.recipeUuid, hash, bytes)
            var text = decode(bytes)
            val patched = withUuid(text)
                ?: return Broken(file, RecipeFileProblem(name, RecipeFileProblem.Kind.UNREADABLE, NO_UUID_LINE))
            if (patched != text) {
                bytes = patched.toByteArray(Charsets.UTF_8)
                AtomicFiles.write(file, bytes)
                hash = AtomicFiles.sha256(bytes)
                text = patched
            }
            val raw = text.removePrefix(BOM)
            val parsed = Orf.parse(raw)
            val uuid = parsed["recipe_uuid"]?.let { Py.str(it) }
                ?: return Broken(file, RecipeFileProblem(name, RecipeFileProblem.Kind.UNREADABLE, "It has no recipe_uuid, and one couldn't be added."))
            Parsed(file, uuid, hash, raw, parsed)
        } catch (e: CharacterCodingException) {
            Broken(file, RecipeFileProblem(name, RecipeFileProblem.Kind.UNREADABLE, "Couldn't read this file: it isn't UTF-8 text."))
        } catch (e: RecipeFormatException) {
            Broken(file, RecipeFileProblem(name, RecipeFileProblem.Kind.UNREADABLE, "Couldn't read this file: ${e.message}"))
        } catch (e: IOException) {
            Broken(file, RecipeFileProblem(name, RecipeFileProblem.Kind.UNREADABLE, "Couldn't read or update this file: ${e.message ?: e::class.simpleName}"))
        }
    }

    // _ensure_recipe_uuid (brief P2-R3, which the spec's "missing recipe_uuid goes to Needs attention" yields
    // to): a recipe mapping without a usable recipe_uuid gets one, written into its file as one line, never by
    // re-dumping the user's file. Anything that isn't a mapping is returned as it is, for Orf.parse to report.
    // Null when the patch doesn't take (a second top-level recipe_uuid line, or a quoted key the line patch
    // can't see): the file is then left alone, or every sync, and the watcher after it, would rewrite it again.
    private fun withUuid(text: String): String? {
        val loaded = try {
            RecipeYaml.load(text.removePrefix(BOM))
        } catch (e: RecipeFormatException) {
            return text
        }
        if (loaded !is Map<*, *> || Py.truthy(Orf.cleanNone(loaded["recipe_uuid"]))) return text
        return patchUuid(text, newUuid())
    }

    // The one-line patch, checked: [text] with [uuid] as its recipe_uuid, or null when the file wouldn't load
    // with that uuid afterwards (a second top-level recipe_uuid line, or a quoted key the line patch can't see).
    private fun patchUuid(text: String, uuid: String): String? {
        val patched = YamlPatch.patchField(text, "recipe_uuid", uuid)
        val check = try {
            RecipeYaml.load(patched.removePrefix(BOM))
        } catch (e: RecipeFormatException) {
            null
        }
        return if (check is Map<*, *> && check["recipe_uuid"] == uuid) patched else null
    }

    private fun amountProblems(name: String, hash: String, raw: () -> String): List<RecipeFileProblem> =
        amountNotes.getOrPut(hash) { unreadableAmounts(raw()) }
            .map { line -> RecipeFileProblem(name, RecipeFileProblem.Kind.AMOUNT, "Amount couldn't be read: $line") }

    // find_unparseable_amount_slots on the file as written: each amount the parser can't read, as its line.
    private fun unreadableAmounts(raw: String): List<String> {
        return try {
            @Suppress("UNCHECKED_CAST")
            val doc = RecipeYaml.load(raw.removePrefix(BOM)) as? Map<Any?, Any?> ?: return emptyList()
            OrfEditing.findUnparseableAmountSlots(doc).map { it.ingredientLine }
        } catch (e: RecipeFormatException) {
            emptyList()
        }
    }

    // UTF-8 or an error: a file in another encoding is listed, never read as replacement characters.
    private fun decode(bytes: ByteArray): String =
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    // A file name as the folder compares it: letter case ignored, as on Windows.
    private fun key(fileName: String): String = fileName.lowercase(Locale.ROOT)

    // recipe_sync.reassign_recipe_uuid, for a file the sync listed as a duplicate (and only such a file,
    // so a name can never reach outside the folder). Letter case is ignored, as Windows ignores it. When the
    // patch wouldn't take, the file is left alone and listed as needing a recipe_uuid line of its own.
    override suspend fun assignNewId(fileName: String) {
        val listed = problems.value.firstOrNull { key(it.fileName) == key(fileName) && it.canAssignNewId }
        require(listed != null) { "$fileName isn't a listed duplicate." }
        recipes.exclusive {
            val file = File(dir, listed.fileName)
            val patched = patchUuid(decode(file.readBytes()), newUuid())
            if (patched == null) {
                val problem = RecipeFileProblem(listed.fileName, RecipeFileProblem.Kind.UNREADABLE, NO_UUID_LINE)
                _problems.update { list -> list.map { if (it == listed) problem else it } }
            } else {
                AtomicFiles.write(file, patched.toByteArray(Charsets.UTF_8))
                syncLocked()
            }
        }
    }

    // Called straight from a button, so it never throws. It never makes the folder either (sync makes it on a
    // first run): an empty one made here, in place of one that went away, would unindex everything at the next
    // sync. A missing folder is already on the problems list, as the sync lists it.
    override fun openFolder(): Boolean {
        if (!dir.isDirectory) return false
        return try {
            opener(dir)
            true
        } catch (e: IllegalArgumentException) {
            false
        } catch (e: IOException) {
            false
        } catch (e: UnsupportedOperationException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    companion object {
        /** How long a recipe whose file is gone waits before a sync may remove it (R1). */
        const val REMOVAL_DELAY_MILLIS = 2_000L

        /** A removal of more files than this, and of more than half of them, is held for the user (R2). */
        const val MASS_REMOVAL_MIN = 3

        /** app_meta: the recipe folder this database was made from (P7-R10c). */
        const val LIBRARY_PATH_KEY = "library_path"

        /** app_meta: rows held since "Use the new folder" (P7-R10e), until removed with confirmation or back. */
        const val MOVE_HELD_KEY = "library_move_held"

        private const val BOM = "\uFEFF"
        private const val NO_UUID_LINE = "Couldn't add a recipe ID to this file; give it a recipe_uuid line of its own."
    }
}

/** The Needs attention message for [count] recipe files held back from a mass removal (R2). */
fun missingFilesMessage(count: Int): String =
    if (count == 1) {
        "1 recipe file is missing from the recipe folder. If you moved it, put it back. " +
            "If you deleted it on purpose, choose Remove it from the app."
    } else {
        "$count recipe files are missing from the recipe folder. If you moved them, put them back. " +
            "If you deleted them on purpose, choose Remove them from the app."
    }
