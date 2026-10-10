package com.naeblis11.mealplanner.data

import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.YamlMap
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteException
import com.naeblis11.mealplanner.folder.FolderFileStore
import com.naeblis11.mealplanner.folder.LIBRARY_BLOCKED_MESSAGE
import com.naeblis11.mealplanner.folder.RecipeFileStore
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** RecipeRepository on the desktop: the recipe folder is the source of truth, so a save writes the file first. */
class RecipeRepositoryFilesTest {
    private val root: File = Files.createTempDirectory("mp-repo-files").toFile()
    private val dir = File(root, "recipes").apply { mkdirs() }
    private val images = File(root, "recipe-images")
    private val db = AppDatabase.openAt(File(root, "test.db"))
    private val trashed = mutableListOf<String>()
    private var uuids = 0
    private val repo = RecipeRepository(
        db,
        images,
        newUuid = { "uuid-${++uuids}" },
        files = FolderFileStore(dir, moveToTrash = { file -> trashed += file.name; file.delete() }),
    )

    @After
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    @Suppress("UNCHECKED_CAST")
    private fun doc(name: String) = RecipeYaml.load(
        "recipe_name: $name\ningredients:\n- Salt:\n    amounts:\n    - amount: 1\n      unit: tsp\nsteps:\n- step: Stir.\n",
    ) as YamlMap

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Every file in the folder with its bytes, to compare before and after a failed save. */
    private fun folder(): Map<String, List<Byte>> = dir.listFiles()!!.associate { it.name to it.readBytes().toList() }

    /** A FolderFileStore whose [failOn]th write (counting from 1) throws instead of writing. */
    private class FailingStore(dir: File, private val failOn: Int) : RecipeFileStore {
        private val inner = FolderFileStore(dir, moveToTrash = null)
        private var writes = 0

        override fun exists(fileName: String) = inner.exists(fileName)
        override fun read(fileName: String) = inner.read(fileName)
        override fun restore(fileName: String, bytes: ByteArray?) = inner.restore(fileName, bytes)
        override fun trash(fileName: String) = inner.trash(fileName)
        override fun write(fileName: String, text: String): String {
            if (++writes == failOn) throw IOException("disk full")
            return inner.write(fileName, text)
        }
    }

    @Test
    fun aFileThatCannotBeWrittenIsNeverIndexed() = runBlocking {
        // File first, then index: a save whose file write fails adds no row.
        val failing = RecipeRepository(db, images, newUuid = { "uuid-x" }, files = FailingStore(dir, failOn = 1))
        assertThrows(IOException::class.java) { runBlocking { failing.save(doc("Soup")) } }
        assertEquals(emptyList<RecipeEntity>(), db.recipeDao().allRecipes())
        assertEquals(emptyMap<String, List<Byte>>(), folder())
    }

    @Test
    fun aFailedWriteUndoesTheFilesTheSaveAlreadyWrote() = runBlocking {
        val soupId = repo.save(doc("Soup"))
        val folderBefore = folder()
        val rowsBefore = db.recipeDao().allRecipes()
        val failing = RecipeRepository(db, images, newUuid = { "uuid-x" }, files = FailingStore(dir, failOn = 2))
        val soup = repo.doc(soupId)!!.apply { this["recipe_name"] = "Soup Changed" }
        // Cake's file is written (created), then Soup's write fails.
        assertThrows(IOException::class.java) { runBlocking { failing.saveAll(listOf(doc("Cake"), soup)) } }
        assertEquals(folderBefore, folder())
        assertEquals(rowsBefore, db.recipeDao().allRecipes())
    }

    @Test
    fun aFailedIndexUndoesEveryFileTheSaveWrote() = runBlocking {
        val soupId = repo.save(doc("Soup"))
        val folderBefore = folder()
        val rowsBefore = db.recipeDao().allRecipes()
        // Any insert of a recipe named Boom aborts, like a constraint would.
        db.useWriterConnection { connection ->
            connection.usePrepared(
                "CREATE TRIGGER boom BEFORE INSERT ON recipe WHEN NEW.name = 'Boom' BEGIN SELECT RAISE(ABORT, 'boom'); END",
            ) { it.step() }
        }
        val soup = repo.doc(soupId)!!.apply { this["recipe_name"] = "Soup Changed" }
        // Both files are written (soup.yaml overwritten, boom.yaml created), then the index transaction fails: the
        // trigger's own failure reaches the caller, not one from the undo or a library-write message.
        val failed = assertThrows(SQLiteException::class.java) { runBlocking { repo.saveAll(listOf(soup, doc("Boom"))) } }
        assertTrue(failed.message, failed.message!!.contains("boom"))
        assertEquals(folderBefore, folder())
        assertEquals(rowsBefore, db.recipeDao().allRecipes())
        // The index still matches the restored file.
        assertEquals(sha256(File(dir, "soup.yaml").readBytes()), db.recipeDao().recipe(soupId)!!.fileHash)
    }

    @Test
    fun aCancelRightAfterTheIndexCommitsKeepsTheSavedFile() = runBlocking {
        // A window closed mid-save: the cancel surfaces just after the index committed. The file must stay
        // as saved; undoing it would leave a row that no longer matches its file, and the edit would be lost.
        val id = repo.save(doc("Soup"))
        val edit = repo.doc(id)!!.apply { this["recipe_name"] = "Soup Changed" }
        val job = launch(Dispatchers.IO, start = CoroutineStart.LAZY) { repo.save(edit) }
        repo.afterCommit = {
            job.cancel()
            yield() // throws the CancellationException, as a cancelled resume would
        }
        job.join()
        repo.afterCommit = {}
        assertTrue(job.isCancelled)
        val row = db.recipeDao().recipe(id)!!
        val file = File(dir, "soup.yaml")
        assertTrue(row.rawYaml.contains("recipe_name: Soup Changed\n"))
        assertEquals(row.rawYaml, file.readText())
        assertEquals(sha256(file.readBytes()), row.fileHash)
    }

    @Test
    fun aNewRecipeIsWrittenToItsSlugFileThenIndexed() = runBlocking {
        val id = repo.save(doc("Tomato Soup"))
        val file = File(dir, "tomato-soup.yaml")
        val row = db.recipeDao().recipe(id)!!
        assertEquals(file.readText(), row.rawYaml)
        assertTrue(row.rawYaml.startsWith("recipe_uuid: uuid-1\nrecipe_name: Tomato Soup\n"))
        assertEquals("tomato-soup.yaml", row.fileName)
        assertEquals(sha256(file.readBytes()), row.fileHash)
    }

    @Test
    fun aSecondRecipeWithTheSameNameGetsTheNextFreeName() = runBlocking {
        repo.save(doc("Soup"))
        val second = repo.save(doc("Soup"))
        assertEquals("soup-2.yaml", db.recipeDao().recipe(second)!!.fileName)
        assertEquals(listOf("soup-2.yaml", "soup.yaml"), dir.list()!!.sorted())
    }

    @Test
    fun aHandWrittenFileIsNeverOverwrittenByANewRecipe() = runBlocking {
        File(dir, "soup.yaml").writeText("hand written\n")
        val id = repo.save(doc("Soup"))
        assertEquals("soup-2.yaml", db.recipeDao().recipe(id)!!.fileName)
        assertEquals("hand written\n", File(dir, "soup.yaml").readText())
    }

    @Test
    fun anEditRewritesTheSameFile() = runBlocking {
        val id = repo.save(doc("Soup"))
        repo.setRating(id, 4)
        val row = db.recipeDao().recipe(id)!!
        val file = File(dir, "soup.yaml")
        assertEquals("soup.yaml", row.fileName)
        assertTrue(file.readText().contains("rating: 4\n"))
        assertEquals(sha256(file.readBytes()), row.fileHash)
        assertEquals(listOf("soup.yaml"), dir.list()!!.toList())
    }

    @Test
    fun aFileChangedOutsideTheAppIsNeverOverwritten() = runBlocking {
        // A hand edit the folder sync hasn't picked up yet (the watcher waits a moment) is not lost to a save.
        val id = repo.save(doc("Soup"))
        val file = File(dir, "soup.yaml")
        file.writeText(file.readText() + "# a hand edit\n")
        val folderBefore = folder()
        val rowsBefore = db.recipeDao().allRecipes()
        val e = assertThrows(IOException::class.java) { runBlocking { repo.setRating(id, 4) } }
        assertEquals("soup.yaml was changed outside the app; it will reload in a moment. Try again after it does.", e.message)
        // In a batch, nothing is written: the new recipe's file written before it is taken back.
        val soup = repo.doc(id)!!
        assertThrows(IOException::class.java) { runBlocking { repo.saveAll(listOf(doc("Cake"), soup)) } }
        assertEquals(folderBefore, folder())
        assertEquals(rowsBefore, db.recipeDao().allRecipes())
    }

    @Test
    fun savingAnExistingRecipeTwiceKeepsItsFileNameAndHash() = runBlocking {
        // An update rebuilds the whole row, so it must never write the file columns back as null.
        val id = repo.save(doc("Soup"))
        val first = repo.doc(id)!!
        first["recipe_name"] = "Soup Again"
        repo.save(first)
        val second = repo.doc(id)!!
        second["recipe_name"] = "Soup Once More"
        repo.save(second)
        val row = db.recipeDao().recipe(id)!!
        val file = File(dir, "soup.yaml")
        assertEquals("soup.yaml", row.fileName)
        assertTrue(file.readText().contains("recipe_name: Soup Once More\n"))
        assertEquals(sha256(file.readBytes()), row.fileHash)
        assertEquals(listOf("soup.yaml"), dir.list()!!.toList())
    }

    @Test
    fun deletingMovesTheFileToTheRecycleBin() = runBlocking {
        val id = repo.save(doc("Soup"))
        repo.delete(id)
        assertEquals(listOf("soup.yaml"), trashed)
        assertFalse(File(dir, "soup.yaml").exists())
        assertNull(db.recipeDao().recipe(id))
    }

    @Test
    fun withoutAFileStoreNothingIsWritten() = runBlocking {
        // Android's repository: Room is the source of truth and there is no folder.
        val phone = RecipeRepository(db, images, newUuid = { "phone-1" })
        val row = db.recipeDao().recipe(phone.save(doc("Soup")))!!
        assertNull(row.fileName)
        assertNull(row.fileHash)
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun withoutAFileStoreTheEditBaseIsNullAndNeverStopsASave() = runBlocking {
        // Android: no file, so no hash to compare; the base-hash check never fires there.
        val phone = RecipeRepository(db, images, newUuid = { "phone-1" })
        val id = phone.save(doc("Soup"))
        val (edit, base) = phone.docWithBase(id)!!
        assertNull(base)
        edit["recipe_name"] = "Soup Changed"
        assertEquals(id, phone.save(edit, base))
        assertEquals("Soup Changed", db.recipeDao().recipe(id)!!.name)
    }

    @Test
    fun aPhotoOrImportWriteThatFailsSaysWhyWithoutAPath() {
        // P7-R10b: refused by Controlled folder access, it is the block and turns the notice on; any other failure
        // says why, with no path but the library folder's name (M1).
        var told = 0
        val desktop = RecipeRepository(db, images, files = FolderFileStore(dir, moveToTrash = null), onLibraryBlocked = { told++ })
        assertEquals(LIBRARY_BLOCKED_MESSAGE, desktop.libraryWriteMessage(java.nio.file.AccessDeniedException("C:\\x")))
        assertEquals(LIBRARY_BLOCKED_MESSAGE, desktop.libraryWriteMessage(IOException("Could not save a.jpg.", IOException("C:\\x\\a.jpg.tmp (Access is denied)"))))
        assertEquals(2, told)
        assertEquals(
            "Couldn't save to Documents\\Meal Planner: There is not enough space on the disk",
            desktop.libraryWriteMessage(IOException("C:\\Users\\someone\\x\\a.jpg.tmp (There is not enough space on the disk)")),
        )
        assertEquals(2, told)
        // The phone has no library folder: nothing there is ever the library's to explain.
        assertNull(RecipeRepository(db, images, onLibraryBlocked = { told++ }).libraryWriteMessage(java.nio.file.AccessDeniedException("x")))
        assertEquals(2, told)
    }
}
