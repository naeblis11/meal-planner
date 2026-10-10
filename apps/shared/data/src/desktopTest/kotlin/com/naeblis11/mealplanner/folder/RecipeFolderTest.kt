package com.naeblis11.mealplanner.folder

import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.FILE_HOLDS_ANOTHER_RECIPE
import com.naeblis11.mealplanner.data.MealPlanEntity
import com.naeblis11.mealplanner.data.RECIPE_HELD
import com.naeblis11.mealplanner.data.RecipeEntity
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.data.missingFileMessage
import com.naeblis11.mealplanner.data.openAt
import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.YamlMap
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeFolderTest {
    private val root: File = Files.createTempDirectory("mp-folder").toFile()
    private val dir = File(root, "recipes").apply { mkdirs() }
    private val images = File(root, "recipe-images").apply { mkdirs() }
    private val db = AppDatabase.openAt(File(root, "test.db"))
    private var appUuids = 0
    private var fileUuids = 0
    private val recipes = RecipeRepository(db, images, newUuid = { "app-${++appUuids}" }, files = FolderFileStore(dir, moveToTrash = { false }))
    private val opened = mutableListOf<File>()

    // The folder's clock, moved on by hand, so a removal's wait (R1) needs no real sleep.
    private var now = 1_000_000L
    private var pendingCalls = 0
    private val folder = RecipeFolder(
        dir, db, recipes, newUuid = { "uuid-${++fileUuids}" }, opener = { opened += it },
        clock = { now }, onRemovalsPending = { pendingCalls++ },
    )

    private fun later() {
        now += RecipeFolder.REMOVAL_DELAY_MILLIS
    }

    @After
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    private fun recipe(name: String, uuid: String? = null, amount: String = "1") = buildString {
        if (uuid != null) append("recipe_uuid: $uuid\n")
        append("recipe_name: $name\n")
        append("ingredients:\n- Salt:\n    amounts:\n    - amount: $amount\n      unit: tsp\n")
        append("steps:\n- step: Stir.\n")
    }

    private fun write(name: String, text: String) = File(dir, name).writeText(text)

    private fun sync(): RecipeFolder.SyncResult = runBlocking { folder.sync() }

    private fun names(): List<String> = runBlocking { recipes.allRecipes().map { it.name } }

    private fun row(uuid: String): RecipeEntity = runBlocking { recipes.allRecipes().single { it.recipeUuid == uuid } }

    private fun problems(): List<RecipeFileProblem> = folder.problems.value

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun indexesNewFilesAndRecordsTheirNamesAndHashes() {
        write("soup.yaml", recipe("Soup", "u-soup"))
        write("stew.yaml", recipe("Stew", "u-stew"))
        assertEquals(RecipeFolder.SyncResult(indexed = 2, unchanged = 0, removed = 0), sync())
        assertEquals(listOf("Soup", "Stew"), names())
        val soup = row("u-soup")
        assertEquals("soup.yaml", soup.fileName)
        assertEquals(sha256(File(dir, "soup.yaml").readBytes()), soup.fileHash)
        assertEquals(listOf("Salt"), runBlocking { db.recipeDao().ingredients(soup.id) }.map { it.name })
        assertEquals(emptyList<RecipeFileProblem>(), problems())
    }

    @Test
    fun aSecondSyncWithNoChangesIsUnchanged() {
        write("soup.yaml", recipe("Soup", "u-soup"))
        sync()
        assertEquals(RecipeFolder.SyncResult(indexed = 0, unchanged = 1, removed = 0), sync())
    }

    @Test
    fun anEditedFileIsReindexedKeepingItsId() {
        write("soup.yaml", recipe("Soup", "u-soup"))
        sync()
        val before = row("u-soup").id
        write("soup.yaml", recipe("Soup v2", "u-soup"))
        assertEquals(RecipeFolder.SyncResult(indexed = 1, unchanged = 0, removed = 0), sync())
        assertEquals(before, row("u-soup").id)
        assertEquals(listOf("Soup v2"), names())
    }

    @Test
    fun aDeletedFileTakesItsPlanRowsButNotItsPhotos() {
        write("soup.yaml", recipe("Soup", "u-soup") + "image: u-soup.jpg\n")
        // Another recipe stays: with every file gone the removal would be held for the user (R2).
        write("stew.yaml", recipe("Stew", "u-stew"))
        File(images, "u-soup.jpg").writeText("photo")
        File(images, "u-soup_thumb.jpg").writeText("thumb")
        sync()
        val id = row("u-soup").id
        runBlocking { db.mealPlanDao().put(MealPlanEntity(date = "2026-10-05", slot = "Dinner", recipeId = id, servings = null)) }
        File(dir, "soup.yaml").delete()
        // R1: the first sync to miss it only notes it, and asks for a later look.
        assertEquals(RecipeFolder.SyncResult(indexed = 0, unchanged = 1, removed = 0), sync())
        assertEquals(listOf("Soup", "Stew"), names())
        assertEquals(id, planned())
        assertTrue(folder.removalsPending)
        assertEquals(1, pendingCalls)
        // Too soon: still waiting.
        now += RecipeFolder.REMOVAL_DELAY_MILLIS - 1
        assertEquals(0, sync().removed)
        assertEquals(listOf("Soup", "Stew"), names())
        // Still gone at the re-check: removed, with its planned meal.
        now += 1
        assertEquals(RecipeFolder.SyncResult(indexed = 0, unchanged = 1, removed = 1), sync())
        assertEquals(listOf("Stew"), names())
        assertFalse(folder.removalsPending)
        assertEquals(emptyList<RecipeFileProblem>(), problems())
        assertNull(runBlocking { db.mealPlanDao().assignment("2026-10-05", "Dinner") })
        // recipe_sync.py doesn't delete photos either: they may be the only copy.
        assertTrue(File(images, "u-soup.jpg").isFile)
        assertTrue(File(images, "u-soup_thumb.jpg").isFile)
    }

    @Test
    fun anUnreadableFileIsListedAndTheRestStillIndexed() {
        write("bad.yaml", "recipe_uuid: u-bad\nsteps: []\ningredients: []\n")
        write("soup.yaml", recipe("Soup", "u-soup"))
        assertEquals(1, sync().indexed)
        val problem = problems().single()
        assertEquals("bad.yaml" to RecipeFileProblem.Kind.UNREADABLE, problem.fileName to problem.kind)
        assertTrue(problem.message, "recipe_name" in problem.message)
    }

    @Test
    fun anEditThatBreaksAFileKeepsTheOldRecipe() {
        write("soup.yaml", recipe("Soup", "u-soup"))
        sync()
        write("soup.yaml", "recipe_uuid: u-soup\nrecipe_name: [unclosed\n")
        sync()
        assertEquals(listOf("Soup"), names())
        assertEquals(RecipeFileProblem.Kind.UNREADABLE, problems().single().kind)
    }

    @Test
    fun aRenameKeepsTheIdAndThePlannedMeals() {
        write("soup.yaml", recipe("Soup", "u-soup"))
        sync()
        val id = row("u-soup").id
        runBlocking { db.mealPlanDao().put(MealPlanEntity(date = "2026-10-05", slot = "Dinner", recipeId = id, servings = null)) }
        File(dir, "soup.yaml").renameTo(File(dir, "tomato-soup.yaml"))
        assertEquals(RecipeFolder.SyncResult(indexed = 1, unchanged = 0, removed = 0), sync())
        val renamed = row("u-soup")
        assertEquals(id to "tomato-soup.yaml", renamed.id to renamed.fileName)
        assertEquals(id, runBlocking { db.mealPlanDao().assignment("2026-10-05", "Dinner") }!!.recipeId)
    }

    @Test
    fun aMissingUuidIsWrittenBackByALinePatch() {
        write("soup.yaml", "recipe_uuid: None\n# my note, kept\n" + recipe("Soup"))
        sync()
        // One line changed; the comment and everything else are as the user wrote them.
        assertEquals("recipe_uuid: uuid-1\n# my note, kept\n" + recipe("Soup"), File(dir, "soup.yaml").readText())
        assertEquals(sha256(File(dir, "soup.yaml").readBytes()), row("uuid-1").fileHash)
        assertEquals(RecipeFolder.SyncResult(indexed = 0, unchanged = 1, removed = 0), sync())
    }

    @Test
    fun aBlankedUuidIsWrittenAgainAndKeepsTheRow() {
        write("a.yaml", "recipe_uuid: None\n" + recipe("A"))
        sync()
        val id = row("uuid-1").id
        write("a.yaml", "recipe_uuid: None\n" + recipe("A"))
        sync()
        assertEquals(id, row("uuid-2").id)
        assertEquals(listOf("A"), names())
    }

    @Test
    fun aDuplicateUuidInOneSyncKeepsTheFirstFile() {
        write("a.yaml", recipe("Recipe A", "dupe-1"))
        write("b.yaml", recipe("Recipe B", "dupe-1"))
        assertEquals(1, sync().indexed)
        assertEquals(listOf("Recipe A"), names())
        val problem = problems().single()
        assertEquals("b.yaml" to RecipeFileProblem.Kind.DUPLICATE_ID, problem.fileName to problem.kind)
        assertTrue(problem.message, "dupe-1" in problem.message && "a.yaml" in problem.message)
        assertTrue(problem.canAssignNewId)
    }

    @Test
    fun aDuplicateOfAnIndexedFileStillOnDiskIsTheOneListed() {
        write("b.yaml", recipe("Recipe B", "dupe-1"))
        sync()
        write("a.yaml", recipe("Recipe A", "dupe-1")) // sorts first, but b.yaml already holds the id
        sync()
        assertEquals(listOf("Recipe B"), names())
        assertEquals("a.yaml", problems().single().fileName)
    }

    @Test
    fun assignNewIdKeepsBothRecipes() {
        write("a.yaml", recipe("Recipe A", "dupe-1"))
        write("b.yaml", recipe("Recipe B", "dupe-1"))
        sync()
        runBlocking { folder.assignNewId("b.yaml") }
        assertEquals(listOf("Recipe A", "Recipe B"), names())
        assertEquals(emptyList<RecipeFileProblem>(), problems())
        assertEquals(recipe("Recipe B", "uuid-1"), File(dir, "b.yaml").readText())
    }

    @Test
    fun assignNewIdOnlyFixesAListedDuplicate() {
        write("soup.yaml", recipe("Soup", "u-soup"))
        sync()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { folder.assignNewId("soup.yaml") } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { folder.assignNewId("../outside.yaml") } }
        assertEquals(recipe("Soup", "u-soup"), File(dir, "soup.yaml").readText())
    }

    @Test
    fun aCrlfFileWithAByteOrderMarkIsIndexedAndPatchedInPlace() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val crlf = recipe("Crlf Soup").replace("\n", "\r\n")
        File(dir, "crlf.yaml").writeBytes(bom + crlf.toByteArray())
        sync()
        assertEquals(listOf("Crlf Soup"), names())
        assertArrayEquals(bom + ("recipe_uuid: uuid-1\r\n" + crlf).toByteArray(), File(dir, "crlf.yaml").readBytes())
        assertEquals(emptyList<RecipeFileProblem>(), problems())
    }

    @Test
    fun anUppercaseYamlExtensionIsIndexed() {
        write("SOUP.YAML", recipe("Soup", "u-soup"))
        sync()
        assertEquals("SOUP.YAML", row("u-soup").fileName)
    }

    @Test
    fun aYmlFileIsIgnored() {
        write("soup.yml", recipe("Soup"))
        assertEquals(RecipeFolder.SyncResult(indexed = 0, unchanged = 0, removed = 0), sync())
        assertEquals(emptyList<String>(), names())
        assertEquals(emptyList<RecipeFileProblem>(), problems())
        assertEquals(recipe("Soup"), File(dir, "soup.yml").readText())
    }

    @Test
    fun theAppsOwnSaveIsNotIndexedAgain() {
        @Suppress("UNCHECKED_CAST")
        val doc = RecipeYaml.load(recipe("Soup")) as YamlMap
        runBlocking { recipes.save(doc) }
        assertEquals(RecipeFolder.SyncResult(indexed = 0, unchanged = 1, removed = 0), sync())
    }

    @Test
    fun amountsAreNeverListedAsNeedingAttention() {
        // The Pi's library has "salt and pepper", "oregano" with no amount, which read fine; a constant warning the
        // owner can't clear helps no one. The recipe editor and the import review highlight an amount they can't read.
        write("soup.yaml", recipe("Soup", "u-soup", amount = "a pinch"))
        write(
            "alfredo.yaml",
            "recipe_uuid: u-alfredo\nrecipe_name: Alfredo\ningredients:\n" +
                "- garlic powder:\n    amounts:\n    - amount: ''\n      unit: ''\n" +
                "- heavy whipping cream:\n" +
                "steps:\n- step: Stir.\n",
        )
        sync()
        assertEquals(listOf("Alfredo", "Soup"), names())
        assertEquals(emptyList<RecipeFileProblem>(), problems())
        sync() // unchanged now: still nothing listed
        assertEquals(emptyList<RecipeFileProblem>(), problems())
    }

    @Test
    fun aFileOverwrittenWithAGoneRecipeTakesItsPlace() {
        write("soup.yaml", recipe("Soup", "u-soup"))
        write("stew.yaml", recipe("Stew", "u-stew"))
        sync()
        val soupId = row("u-soup").id
        File(dir, "soup.yaml").delete()
        write("stew.yaml", recipe("Soup", "u-soup"))
        sync()
        val soup = row("u-soup")
        assertEquals(soupId to "stew.yaml", soup.id to soup.fileName)
        assertEquals(listOf("Soup"), names())
    }

    @Test
    fun aFolderThatCantBeReadIsNeverTreatedAsEmpty() {
        write("soup.yaml", recipe("Soup", "u-soup"))
        sync()
        val id = row("u-soup").id
        runBlocking { db.mealPlanDao().put(MealPlanEntity(date = "2026-10-05", slot = "Dinner", recipeId = id, servings = null)) }
        // A path that is a file, like a Documents folder that has gone away: listFiles() gives null.
        val notAFolder = File(root, "not-a-folder").apply { writeText("x") }
        val broken = RecipeFolder(notAFolder, db, recipes, opener = {})

        assertThrows(IOException::class.java) { runBlocking { broken.sync() } }

        assertEquals(listOf("Soup"), names())
        assertEquals(id, runBlocking { db.mealPlanDao().assignment("2026-10-05", "Dinner") }!!.recipeId)
        assertEquals(
            listOf(
                RecipeFileProblem(
                    "not-a-folder",
                    RecipeFileProblem.Kind.FOLDER,
                    "Can't read the recipe folder: ${notAFolder.path}. Meal Planner will pick it up again when it is back.",
                ),
            ),
            broken.problems.value,
        )
    }

    @Test
    fun aUuidThatCantBeAddedLeavesTheFileAlone() {
        // A second, blank top-level recipe_uuid wins on load, so the patched first line never takes.
        // Writing anyway would rewrite the file on every sync, and the watcher would sync again.
        val text = "recipe_uuid: None\n" + recipe("Soup") + "recipe_uuid:\n"
        write("soup.yaml", text)
        sync()
        sync()
        assertEquals(text, File(dir, "soup.yaml").readText())
        assertEquals(emptyList<String>(), names())
        assertEquals(
            listOf(
                RecipeFileProblem(
                    "soup.yaml",
                    RecipeFileProblem.Kind.UNREADABLE,
                    "Couldn't add a recipe ID to this file; give it a recipe_uuid line of its own.",
                ),
            ),
            problems(),
        )
    }

    @Test
    fun openFolderOpensTheRecipesFolder() {
        assertEquals(true, folder.openFolder())
        assertEquals(listOf(dir), opened)
    }

    @Test
    fun openFolderNeverThrowsWhenItCantOpen() {
        for (failure in listOf(IllegalArgumentException("gone"), IOException("no handler"), UnsupportedOperationException("headless"), SecurityException("denied"))) {
            val failing = RecipeFolder(dir, db, recipes, opener = { throw failure })
            assertEquals(failure.toString(), false, failing.openFolder())
        }
    }

    @Test
    fun openFolderOnAMissingFolderOpensNothing() {
        write("soup.yaml", recipe("Soup", "u-soup"))
        sync()
        assertTrue(dir.deleteRecursively())
        assertEquals(false, folder.openFolder())
        assertEquals(emptyList<File>(), opened)
        assertTrue(!dir.exists())
    }

    // Two steps, through a name of its own: a one-step rename that only changes letter case is
    // not one Java promises on Windows.
    private fun renameTo(from: String, to: String) {
        val step = File(dir, "renaming.tmp")
        assertTrue(File(dir, from).renameTo(step))
        assertTrue(step.renameTo(File(dir, to)))
        assertEquals(listOf(to), dir.list()!!.filter { it.equals(to, ignoreCase = true) })
    }

    private fun plan(id: Long, slot: String = "Dinner") = runBlocking {
        db.mealPlanDao().put(MealPlanEntity(date = "2026-10-05", slot = slot, recipeId = id, servings = null))
    }

    private fun planned(slot: String = "Dinner"): Long? = runBlocking { db.mealPlanDao().assignment("2026-10-05", slot) }?.recipeId

    @Test
    fun swappedFilesKeepTheirRecipesAndPlannedMeals() {
        write("a.yaml", recipe("A", "u-a"))
        write("b.yaml", recipe("B", "u-b"))
        sync()
        val idA = row("u-a").id
        val idB = row("u-b").id
        plan(idA, "Dinner")
        plan(idB, "Lunch")
        val a = File(dir, "a.yaml").readText()
        val b = File(dir, "b.yaml").readText()
        write("a.yaml", b)
        write("b.yaml", a)
        assertEquals(RecipeFolder.SyncResult(indexed = 2, unchanged = 0, removed = 0), sync())
        assertEquals(emptyList<RecipeFileProblem>(), problems())
        assertEquals(idA to "b.yaml", row("u-a").let { it.id to it.fileName })
        assertEquals(idB to "a.yaml", row("u-b").let { it.id to it.fileName })
        assertEquals(idA, planned("Dinner"))
        assertEquals(idB, planned("Lunch"))
        // An edit of A in the app now goes to A's file, and B's is left alone.
        runBlocking { recipes.setRating(idA, 4) }
        assertTrue("rating: 4" in File(dir, "b.yaml").readText())
        assertEquals(b, File(dir, "a.yaml").readText())
    }

    @Test
    fun aRenameChainKeepsBothRecipes() {
        write("chili.yaml", recipe("Chili", "u-1"))
        write("chili-2.yaml", recipe("Chili", "u-2"))
        sync()
        val id1 = row("u-1").id
        val id2 = row("u-2").id
        plan(id1, "Dinner")
        plan(id2, "Lunch")
        assertTrue(File(dir, "chili.yaml").renameTo(File(dir, "chili-old.yaml")))
        assertTrue(File(dir, "chili-2.yaml").renameTo(File(dir, "chili.yaml")))
        assertEquals(0, sync().removed)
        assertEquals(id1 to "chili-old.yaml", row("u-1").let { it.id to it.fileName })
        assertEquals(id2 to "chili.yaml", row("u-2").let { it.id to it.fileName })
        assertEquals(id1, planned("Dinner"))
        assertEquals(id2, planned("Lunch"))
        assertEquals(emptyList<RecipeFileProblem>(), problems())
    }

    @Test
    fun aNewFileInAMovedRecipesOldNameGetsARowOfItsOwn() {
        write("a.yaml", recipe("A", "u-a"))
        sync()
        val idA = row("u-a").id
        plan(idA)
        assertTrue(File(dir, "a.yaml").renameTo(File(dir, "c.yaml")))
        write("a.yaml", recipe("New"))
        sync()
        assertEquals(idA to "c.yaml", row("u-a").let { it.id to it.fileName })
        assertEquals(idA, planned())
        val fresh = row("uuid-1")
        assertTrue(fresh.id != idA)
        assertEquals("a.yaml", fresh.fileName)
        assertEquals(listOf("A", "New"), names())
    }

    @Test
    fun aFolderThatWentAwayIsNeverRecreatedEmpty() {
        write("soup.yaml", recipe("Soup", "u-soup"))
        sync()
        val id = row("u-soup").id
        plan(id)
        assertTrue(dir.deleteRecursively())
        assertThrows(IOException::class.java) { sync() }
        assertTrue(!dir.exists())
        assertEquals(listOf("Soup"), names())
        assertEquals(id, planned())
        assertEquals(
            listOf(
                RecipeFileProblem(
                    "recipes",
                    RecipeFileProblem.Kind.FOLDER,
                    "The recipe folder is missing: ${dir.path}. Meal Planner will pick it up again when it is back.",
                ),
            ),
            problems(),
        )
        // Open recipe folder on that screen: nothing to open, and no throw (it is a button click).
        assertEquals(false, folder.openFolder())
        assertTrue(!dir.exists())
        assertEquals(emptyList<File>(), opened)
    }

    private fun storedPath(): String? = runBlocking { db.appMetaDao().get(RecipeFolder.LIBRARY_PATH_KEY) }

    @Test
    fun theFirstIndexStoresTheFoldersPath() {
        // P7-R10c: the database remembers which folder it indexes.
        assertNull(storedPath())
        write("soup.yaml", recipe("Soup", "u-soup"))
        sync()
        assertEquals(dir.canonicalPath, storedPath())
    }

    @Test
    fun theSameFolderAsStoredBehavesAsBefore() {
        listOf("a", "b", "c").forEach { write("$it.yaml", recipe(it.uppercase(), "u-$it")) }
        sync()
        assertTrue(File(dir, "a.yaml").delete())
        sync()
        later()
        sync()
        // R1 as ever: one file of three, gone twice over the wait, leaves.
        assertEquals(listOf("B", "C"), names())
        assertEquals(emptyList<RecipeFileProblem>(), problems())
        assertEquals(dir.canonicalPath, storedPath())
    }

    @Test
    fun aMovedFolderRemovesNothingUntilTheNewOneIsConfirmed() {
        // P7-R10c: five recipes indexed from one folder; the app then finds another (60% overlap, plus a new one).
        val old = (1..5).map { n -> "r$n.yaml".also { write(it, recipe("R$n", "u-$n")) } }
        sync()
        val r5 = row("u-5").id
        plan(r5)
        val moved = File(root, "elsewhere/recipes").apply { mkdirs() }
        old.take(3).forEach { File(dir, it).copyTo(File(moved, it)) }
        File(moved, "new.yaml").writeText(recipe("New", "u-new"))
        val other = RecipeFolder(moved, db, recipes, clock = { now })
        runBlocking { other.sync() }
        repeat(3) {
            later()
            runBlocking { other.sync() }
        }
        // The new one is in; nothing went, however long, and the planned meal stays.
        assertEquals(listOf("New", "R1", "R2", "R3", "R4", "R5"), names())
        assertEquals(r5, planned())
        val problem = other.problems.value.single()
        assertEquals(
            "The recipe folder changed from ${dir.canonicalPath} to ${moved.canonicalPath}. Nothing is removed until you confirm.",
            problem.message,
        )
        assertEquals(dir.canonicalPath, problem.movedFrom)
        assertEquals(2, problem.missingFiles)
        assertTrue(problem.canUseNewFolder)
        assertFalse(problem.canRemoveMissing)
        // "Remove them from the app" can't reach past it either.
        runBlocking { other.confirmMassRemoval(setOf(r5)) }
        assertEquals(6, names().size)

        // P7-R10e: Use the new folder only accepts the folder. The two missing recipes move into the R2 hold (though
        // two of six is below its threshold), so they and R5's planned meal stay, however long, until Remove is
        // confirmed with the usual counts. That survives a restart.
        runBlocking { other.useNewFolder() }
        assertEquals(moved.canonicalPath, storedPath())
        repeat(3) {
            later()
            runBlocking { other.sync() }
        }
        val restarted = RecipeFolder(moved, db, recipes, clock = { now })
        later()
        runBlocking { restarted.sync() }
        assertEquals(listOf("New", "R1", "R2", "R3", "R4", "R5"), names())
        assertEquals(r5, planned())
        val held = restarted.problems.value.single()
        assertEquals(missingFilesMessage(2), held.message)
        assertTrue(held.canRemoveMissing)
        val summary = runBlocking { restarted.heldSummary() }
        assertEquals(setOf(row("u-4").id, r5), summary.ids)
        assertEquals(1, summary.plannedMeals)
        runBlocking { restarted.confirmMassRemoval(summary.ids) }
        assertEquals(listOf("New", "R1", "R2", "R3"), names())
        assertNull(planned())
        assertEquals(emptyList<RecipeFileProblem>(), restarted.problems.value)
    }

    @Test
    fun useTheNewFolderOnTheStoredFolderDoesNothingAndKeepsAnR2Hold() {
        // P7-R10e: a genuine mass-removal hold is never cleared by it, and on the stored folder it is a no-op.
        (1..5).forEach { n -> write("r$n.yaml", recipe("R$n", "u-$n")) }
        sync()
        (1..4).forEach { n -> assertTrue(File(dir, "r$n.yaml").delete()) }
        sync()
        assertEquals(listOf(missingFilesMessage(4)), problems().map { it.message })
        runBlocking { folder.useNewFolder() }
        later()
        sync()
        assertEquals(listOf(missingFilesMessage(4)), problems().map { it.message })
        assertEquals(5, names().size)
        assertEquals(dir.canonicalPath, storedPath())
    }

    // A folder elsewhere for the P7-R10d cases: the database was made from [dir].
    private fun movedFolder(): File = File(root, "elsewhere/recipes").apply { mkdirs() }

    @Test
    fun whileMovedASameNamedFileWithANewIdIsANewRecipe() {
        // P7-R10d a: the new folder's soup.yaml is another recipe; it never takes over the old one's row or meals.
        write("soup.yaml", recipe("Soup", "u-soup"))
        sync()
        val soup = row("u-soup").id
        plan(soup)
        val moved = movedFolder()
        File(moved, "soup.yaml").writeText(recipe("Other Soup", "u-other"))
        val other = RecipeFolder(moved, db, recipes, clock = { now })
        repeat(2) {
            runBlocking { other.sync() }
            later()
        }
        assertEquals(listOf("Other Soup", "Soup"), names())
        assertEquals(soup, row("u-soup").id)
        assertTrue(row("u-other").id != soup)
        assertEquals(soup, planned())
        assertEquals(1, other.problems.value.single().missingFiles)
    }

    @Test
    fun whileMovedAFileNowHoldingAnotherKnownRecipeHoldsTheFirst() {
        // P7-R10d b: in the new folder a.yaml holds recipe B; recipe A (indexed from a.yaml) is held, not removed.
        write("a.yaml", recipe("A", "u-a"))
        write("b.yaml", recipe("B", "u-b"))
        sync()
        val a = row("u-a").id
        plan(a)
        val moved = movedFolder()
        File(moved, "a.yaml").writeText(recipe("B", "u-b"))
        val other = RecipeFolder(moved, db, recipes, clock = { now })
        repeat(2) {
            runBlocking { other.sync() }
            later()
        }
        assertEquals(listOf("A", "B"), names())
        assertEquals(a, row("u-a").id)
        assertEquals(a, planned())
        assertEquals("a.yaml", row("u-b").fileName)
        assertEquals(1, other.problems.value.single().missingFiles)
        // Accepted: A joins the hold and still waits for Remove.
        runBlocking { other.useNewFolder() }
        later()
        runBlocking { other.sync() }
        assertEquals(listOf("A", "B"), names())
        assertEquals(setOf(a), runBlocking { other.heldSummary() }.ids)
    }

    // P7-R10g: a move accepted with [held] still waiting (in the old folder only); [kept] is in both folders.
    private fun acceptedMove(kept: String, held: String): Pair<RecipeFolder, File> {
        write("$kept.yaml", recipe(kept.uppercase(), "u-$kept"))
        write("$held.yaml", recipe(held.uppercase(), "u-$held"))
        sync()
        val moved = movedFolder()
        File(dir, "$kept.yaml").copyTo(File(moved, "$kept.yaml"))
        val other = RecipeFolder(moved, db, recipes, clock = { now })
        runBlocking {
            other.sync()
            other.useNewFolder()
        }
        assertEquals(setOf(row("u-$held").id), runBlocking { other.heldSummary() }.ids)
        return other to moved
    }

    @Test
    fun afterAnAcceptedMoveAUuidChangedOrBlankedByHandStillKeepsItsRow() {
        // P7-R10g: only the move-held row is kept from adoption by file name; a present recipe keeps its row and meal.
        val (other, moved) = acceptedMove(kept = "p", held = "g")
        val p = row("u-p").id
        plan(p)
        File(moved, "p.yaml").writeText(recipe("P", "u-p2"))
        runBlocking { other.sync() }
        assertEquals(p, row("u-p2").id)
        assertEquals(p, planned())
        File(moved, "p.yaml").writeText("recipe_uuid: None\n" + recipe("P"))
        runBlocking { other.sync() }
        // The sync writes a new recipe_uuid into the file and keeps the row.
        assertFalse(File(moved, "p.yaml").readText().startsWith("recipe_uuid: None"))
        assertEquals(p, runBlocking { recipes.allRecipes().single { it.name == "P" } }.id)
        assertEquals(p, planned())
        assertEquals(listOf("G", "P"), names())
    }

    @Test
    fun afterAnAcceptedMoveAHeldRowsFileNameIsNeverAdopted() {
        // P7-R10g: a new file in the held recipe's old name is a new recipe; the held one and its meal stay held.
        val (other, moved) = acceptedMove(kept = "p", held = "g")
        val g = row("u-g").id
        plan(g)
        File(moved, "g.yaml").writeText(recipe("New G", "u-new"))
        runBlocking { other.sync() }
        assertTrue(row("u-new").id != g)
        assertEquals(g, row("u-g").id)
        assertEquals(g, planned())
        assertEquals(setOf(g), runBlocking { other.heldSummary() }.ids)
    }

    /** The moved library as the desktop has it: its own repository writing into it, and its folder sync. */
    private class Moved(val folder: RecipeFolder, val recipes: RecipeRepository, val dir: File)

    // P7-R10f: recipe A was indexed from a.yaml; in a moved folder a.yaml holds recipe B, so A is held. [gone] adds a
    // recipe that isn't in the moved folder at all.
    private fun displacedA(gone: Boolean = false): Moved {
        write("a.yaml", recipe("A", "u-a"))
        write("b.yaml", recipe("B", "u-b"))
        if (gone) write("gone.yaml", recipe("Gone", "u-gone"))
        sync()
        val moved = movedFolder()
        File(moved, "a.yaml").writeText(recipe("B", "u-b"))
        val movedRecipes = RecipeRepository(db, images, files = FolderFileStore(moved, moveToTrash = { false }))
        val other = RecipeFolder(moved, db, movedRecipes, clock = { now })
        runBlocking { other.sync() }
        return Moved(other, movedRecipes, moved)
    }

    @Test
    fun aRatingOnAHeldRecipeNeverOverwritesTheRecipeNowInItsFile() {
        val moved = displacedA()
        val a = row("u-a").id
        val file = File(moved.dir, "a.yaml")
        val before = file.readBytes()
        val refused = assertThrows(IOException::class.java) { runBlocking { moved.recipes.setRating(a, 5) } }
        assertEquals(FILE_HOLDS_ANOTHER_RECIPE, refused.message)
        assertArrayEquals(before, file.readBytes())
        assertNull(row("u-a").rating)
        // Nor a category, nor a delete (which would send B's file to the Recycle Bin).
        assertEquals(FILE_HOLDS_ANOTHER_RECIPE, assertThrows(IOException::class.java) { runBlocking { moved.recipes.setCategory(a, "Soups", "") } }.message)
        assertEquals(FILE_HOLDS_ANOTHER_RECIPE, assertThrows(IOException::class.java) { runBlocking { moved.recipes.delete(a) } }.message)
        assertArrayEquals(before, file.readBytes())
        assertEquals(listOf("A", "B"), names())
    }

    @Test
    fun anEditOfAHeldRecipeNeverOverwritesTheRecipeNowInItsFile() {
        val moved = displacedA()
        val a = row("u-a").id
        val file = File(moved.dir, "a.yaml")
        val before = file.readBytes()
        val (doc, base) = runBlocking { moved.recipes.docWithBase(a)!! }
        doc["recipe_name"] = "A edited"
        val refused = assertThrows(IOException::class.java) { runBlocking { moved.recipes.save(doc, base) } }
        assertEquals(FILE_HOLDS_ANOTHER_RECIPE, refused.message)
        assertArrayEquals(before, file.readBytes())
        assertEquals(listOf("A", "B"), names())
    }

    @Test
    fun aRecipeHeldFromAMoveCantBeSavedAndOthersSaveAsBefore() {
        // P7-R10f: a recipe held because it is missing from the moved folder is refused; B, in it, saves as ever.
        val moved = displacedA(gone = true)
        val gone = row("u-gone").id
        val before = moved.dir.list()!!.sorted()
        assertEquals(RECIPE_HELD, assertThrows(IOException::class.java) { runBlocking { moved.recipes.setRating(gone, 3) } }.message)
        assertEquals(before, moved.dir.list()!!.sorted())
        runBlocking { moved.recipes.setRating(row("u-b").id, 4) }
        assertEquals(4, row("u-b").rating)
        assertTrue(File(moved.dir, "a.yaml").readText().contains("rating: 4"))
    }

    @Test
    fun aFirstSyncMakesTheFolderOnlyThroughMakeDirAndABlockedOneIsListedMissing() {
        // P7-R10: Controlled folder access refuses the folder; the sync makes it only through makeDir, and says so.
        assertTrue(dir.delete())
        val asked = mutableListOf<File>()
        val blocked = RecipeFolder(dir, db, recipes, makeDir = { asked += it; throw java.nio.file.AccessDeniedException(it.path) })
        assertThrows(IOException::class.java) { runBlocking { blocked.sync() } }
        assertEquals(listOf(dir), asked)
        assertFalse(dir.exists())
        assertEquals(
            listOf("The recipe folder is missing: ${dir.path}. Meal Planner will pick it up again when it is back."),
            blocked.problems.value.map { it.message },
        )
        // Once an indexed file exists the folder is never made again, whatever makeDir would do.
        val made = RecipeFolder(dir, db, recipes, makeDir = { asked += it; it.mkdirs() })
        runBlocking { made.sync() }
        assertTrue(dir.isDirectory)
        write("soup.yaml", recipe("Soup", "u-soup"))
        runBlocking { made.sync() }
        assertTrue(dir.deleteRecursively())
        asked.clear()
        assertThrows(IOException::class.java) { runBlocking { made.sync() } }
        assertEquals(emptyList<File>(), asked)
        assertFalse(dir.exists())
        assertEquals(listOf("Soup"), names())
    }

    @Test
    fun aSaveOverAHandEditIndexedWhileEditingIsRefused() {
        write("soup.yaml", recipe("Soup", "u-soup"))
        sync()
        val id = row("u-soup").id
        val (doc, base) = runBlocking { recipes.docWithBase(id)!! }
        // The editor is open; a hand edit lands and the watcher's sync indexes it.
        val handEdit = recipe("Soup", "u-soup", amount = "2")
        write("soup.yaml", handEdit)
        sync()

        doc["recipe_name"] = "Soup From The Form"
        val e = assertThrows(IOException::class.java) { runBlocking { recipes.save(doc, base) } }
        assertEquals(
            "This recipe was changed outside the app while you were editing. Close it and open it again to see the change.",
            e.message,
        )
        assertEquals(handEdit, File(dir, "soup.yaml").readText())
        assertEquals(sha256(handEdit.toByteArray()), row("u-soup").fileHash)
        assertEquals(listOf("Soup"), names())

        // Opened again, it saves.
        val (fresh, current) = runBlocking { recipes.docWithBase(id)!! }
        fresh["recipe_name"] = "Soup From The Form"
        runBlocking { recipes.save(fresh, current) }
        assertEquals(listOf("Soup From The Form"), names())
    }

    @Test
    fun aSaveOfARecipeRemovedWhileEditingIsRefused() {
        write("soup.yaml", recipe("Soup", "u-soup"))
        // Another recipe stays: with every file gone the removal would be held for the user (R2).
        write("stew.yaml", recipe("Stew", "u-stew"))
        sync()
        val (doc, base) = runBlocking { recipes.docWithBase(row("u-soup").id)!! }
        assertTrue(File(dir, "soup.yaml").delete())
        sync()
        // Waiting to be removed: the save never brings the file back.
        val e = assertThrows(IOException::class.java) { runBlocking { recipes.save(doc, base) } }
        assertEquals(missingFileMessage("soup.yaml"), e.message)
        assertEquals(listOf("stew.yaml"), dir.list()!!.toList())
        assertEquals(listOf("Soup", "Stew"), names())
        // Removed: the form's save is refused as a change made outside the app.
        later()
        sync()
        assertEquals(listOf("Stew"), names())
        assertThrows(IOException::class.java) { runBlocking { recipes.save(doc, base) } }
        assertEquals(listOf("stew.yaml"), dir.list()!!.toList())
    }

    // Five recipes, the first two planned, as a library to move out and back.
    private fun library(): Map<String, ByteArray> {
        val names = listOf("a", "b", "c", "d", "e")
        names.forEach { write("$it.yaml", recipe(it.uppercase(), "u-$it")) }
        sync()
        plan(row("u-a").id, "Dinner")
        plan(row("u-b").id, "Lunch")
        return names.associate { "$it.yaml" to File(dir, "$it.yaml").readBytes() }
    }

    private fun held(count: Int) =
        listOf(RecipeFileProblem("recipes", RecipeFileProblem.Kind.FOLDER, missingFilesMessage(count), missingFiles = count))

    @Test
    fun aFolderRefilledSlowlyLosesNothing() {
        val files = library()
        val a = row("u-a").id
        val b = row("u-b").id
        // Moved out in Explorer; something that isn't a recipe file stays behind.
        files.keys.forEach { assertTrue(File(dir, it).delete()) }
        write("notes.txt", "not a recipe")
        assertEquals(0, sync().removed)
        assertEquals(held(5), problems())
        assertTrue(held(5).single().canRemoveMissing)
        assertFalse(folder.removalsPending)
        later()
        assertEquals(0, sync().removed)

        // Copied back one file at a time, a sync after each, long apart: nothing goes, even once fewer than half are
        // missing, until all are back.
        for ((index, name) in files.keys.withIndex()) {
            File(dir, name).writeBytes(files.getValue(name))
            later()
            assertEquals(0, sync().removed)
            assertEquals(listOf("A", "B", "C", "D", "E"), names())
            assertEquals(a, planned("Dinner"))
            assertEquals(b, planned("Lunch"))
            val missing = files.size - index - 1
            assertEquals(if (missing == 0) emptyList() else held(missing), problems())
        }
        assertFalse(folder.removalsPending)
    }

    @Test
    fun anAppSaveWhileTheFolderIsEmptyKeepsTheOldRecipes() {
        val files = library()
        files.keys.forEach { assertTrue(File(dir, it).delete()) }
        // A new recipe saved in the app before any sync has seen the folder empty.
        @Suppress("UNCHECKED_CAST")
        val pie = RecipeYaml.load(recipe("Pie")) as YamlMap
        runBlocking { recipes.save(pie) }
        assertEquals(0, sync().removed)
        assertEquals(listOf("A", "B", "C", "D", "E", "Pie"), names())
        assertEquals(held(5), problems())
        // And another while the hold is up; later syncs still remove nothing.
        @Suppress("UNCHECKED_CAST")
        val tart = RecipeYaml.load(recipe("Tart")) as YamlMap
        runBlocking { recipes.save(tart) }
        later()
        assertEquals(0, sync().removed)
        assertEquals(listOf("A", "B", "C", "D", "E", "Pie", "Tart"), names())
        assertEquals(row("u-a").id, planned("Dinner"))
        assertEquals(row("u-b").id, planned("Lunch"))
        // A held recipe can't be saved into the empty folder either.
        val edit = runBlocking { recipes.doc(row("u-c").id)!! }.apply { this["recipe_name"] = "C Changed" }
        assertThrows(IOException::class.java) { runBlocking { recipes.save(edit) } }
        assertFalse(File(dir, "c.yaml").exists())
    }

    @Test
    fun confirmingAMassRemovalRemovesOnlyTheFilesStillMissing() {
        val files = library()
        val a = row("u-a").id
        files.keys.forEach { assertTrue(File(dir, it).delete()) }
        sync()
        assertEquals(held(5), problems())
        // What the confirmation says: 5 recipes, and the 2 planned meals that use them.
        val summary = runBlocking { folder.heldSummary() }
        assertEquals(5 to 2, summary.ids.size to summary.plannedMeals)
        // a.yaml is put back after the list was shown, before any sync sees it.
        File(dir, "a.yaml").writeBytes(files.getValue("a.yaml"))
        runBlocking { folder.confirmMassRemoval(summary.ids) }
        assertEquals(listOf("A"), names())
        assertEquals(a, planned("Dinner"))
        assertNull(planned("Lunch"))
        assertEquals(emptyList<RecipeFileProblem>(), problems())
        assertEquals(HeldRecipes(emptySet(), 0), runBlocking { folder.heldSummary() })
    }

    @Test
    fun confirmingRemovesOnlyTheRecipesCountedWhenTheDialogOpened() {
        library()
        write("f.yaml", recipe("F", "u-f"))
        sync()
        val f = row("u-f").id
        plan(f, "Breakfast")
        listOf("a", "b", "c", "d", "e").forEach { assertTrue(File(dir, "$it.yaml").delete()) }
        sync()
        assertEquals(held(5), problems())
        val summary = runBlocking { folder.heldSummary() }
        assertEquals(5, summary.ids.size)
        // f.yaml goes missing after the dialog opened, and the watcher's sync holds it with the rest: "Remove" was
        // about the 5 the dialog counted, not this one.
        assertTrue(File(dir, "f.yaml").delete())
        sync()
        assertEquals(held(6), problems())
        runBlocking { folder.confirmMassRemoval(summary.ids) }
        assertEquals(listOf("F"), names())
        assertEquals(f, planned("Breakfast"))
        assertEquals(held(1), problems())
        assertEquals(HeldRecipes(setOf(f), 1), runBlocking { folder.heldSummary() })
    }

    @Test
    fun aFewFilesDeletedAtOnceGoAfterTheWaitButMostAreHeld() {
        val files = library()
        // 2 of 5: not most of the library, so they go once the wait is over.
        assertTrue(File(dir, "d.yaml").delete())
        assertTrue(File(dir, "e.yaml").delete())
        sync()
        assertEquals(listOf("A", "B", "C", "D", "E"), names())
        later()
        assertEquals(2, sync().removed)
        assertEquals(listOf("A", "B", "C"), names())
        // A file back before its wait is over is kept, and its wait starts again if it goes again.
        assertTrue(File(dir, "c.yaml").delete())
        sync()
        File(dir, "c.yaml").writeBytes(files.getValue("c.yaml"))
        later()
        assertEquals(0, sync().removed)
        assertTrue(File(dir, "c.yaml").delete())
        assertEquals(0, sync().removed)
        File(dir, "c.yaml").writeBytes(files.getValue("c.yaml"))
        sync()
        assertFalse(folder.removalsPending)
        // Every file of a small library at once: held too (R2).
        listOf("a.yaml", "b.yaml", "c.yaml").forEach { assertTrue(File(dir, it).delete()) }
        sync()
        later()
        assertEquals(0, sync().removed)
        assertEquals(held(3), problems())
        assertEquals(row("u-a").id, planned("Dinner"))
    }

    @Test
    fun aFirstRunWithAnEmptyFolderIsFine() {
        assertEquals(RecipeFolder.SyncResult(indexed = 0, unchanged = 0, removed = 0), sync())
        assertEquals(emptyList<RecipeFileProblem>(), problems())
        assertFalse(folder.removalsPending)
        write("soup.yaml", recipe("Soup", "u-soup"))
        assertEquals(1, sync().indexed)
        assertEquals(listOf("Soup"), names())
    }

    @Test
    fun aSaveWhileTheFolderIsMissingNeverRecreatesIt() {
        write("soup.yaml", recipe("Soup", "u-soup"))
        write("stew.yaml", recipe("Stew", "u-stew"))
        sync()
        val soup = row("u-soup").id
        val stew = row("u-stew").id
        plan(soup, "Lunch")
        plan(stew, "Dinner")
        val saved = File(root, "saved").also { dir.copyRecursively(it) }
        assertTrue(dir.deleteRecursively())

        val edit = runBlocking { recipes.doc(soup)!! }.apply { this["recipe_name"] = "Soup Changed" }
        val e = assertThrows(IOException::class.java) { runBlocking { recipes.save(edit) } }
        // A recipe already in the app says its file is missing; only a new one says the folder is.
        assertEquals(missingFileMessage("soup.yaml"), e.message)
        @Suppress("UNCHECKED_CAST")
        val pie = RecipeYaml.load(recipe("Pie")) as YamlMap
        val fresh = assertThrows(IOException::class.java) { runBlocking { recipes.save(pie) } }
        assertEquals("The recipe folder is missing: ${dir.path}", fresh.message)
        assertTrue(!dir.exists())

        // The folder comes back (a drive reconnected): nothing was lost meanwhile.
        saved.copyRecursively(dir)
        sync()
        assertEquals(listOf("Soup", "Stew"), names())
        assertEquals(soup, planned("Lunch"))
        assertEquals(stew, planned("Dinner"))
        assertEquals(emptyList<RecipeFileProblem>(), problems())
    }

    @Test
    fun aMissingFolderIsCreatedWhileTheIndexHasNoFiles() {
        assertTrue(dir.deleteRecursively())
        assertEquals(RecipeFolder.SyncResult(indexed = 0, unchanged = 0, removed = 0), sync())
        assertTrue(dir.isDirectory)
    }

    @Test
    fun assignNewIdThatWouldNotTakeLeavesTheFileAlone() {
        write("a.yaml", recipe("Recipe A", "dupe-1"))
        // A second top-level recipe_uuid wins on load, so a patched first line never takes.
        val text = recipe("Recipe B", "dupe-1") + "recipe_uuid: dupe-1\n"
        write("b.yaml", text)
        sync()
        runBlocking { folder.assignNewId("b.yaml") }
        assertEquals(text, File(dir, "b.yaml").readText())
        assertEquals(listOf("Recipe A"), names())
        val problem = problems().single()
        assertEquals("b.yaml" to RecipeFileProblem.Kind.UNREADABLE, problem.fileName to problem.kind)
        assertTrue(problem.message, "recipe_uuid" in problem.message)
    }

    @Test
    fun assignNewIdIgnoresLetterCase() {
        write("a.yaml", recipe("Recipe A", "dupe-1"))
        write("b.yaml", recipe("Recipe B", "dupe-1"))
        sync()
        runBlocking { folder.assignNewId("B.yaml") }
        assertEquals(listOf("Recipe A", "Recipe B"), names())
        assertEquals(recipe("Recipe B", "uuid-1"), File(dir, "b.yaml").readText())
    }

    @Test
    fun aLetterCaseRenameKeepsTheIdAndThePlannedMeals() {
        write("soup.yaml", recipe("Soup", "u-soup"))
        sync()
        val id = row("u-soup").id
        plan(id)
        renameTo("soup.yaml", "Soup.yaml")
        assertEquals(RecipeFolder.SyncResult(indexed = 1, unchanged = 0, removed = 0), sync())
        val renamed = row("u-soup")
        assertEquals(id to "Soup.yaml", renamed.id to renamed.fileName)
        assertEquals(id, planned())
        assertEquals(1, runBlocking { recipes.allRecipes() }.size)
        assertEquals(RecipeFolder.SyncResult(indexed = 0, unchanged = 1, removed = 0), sync())
    }

    @Test
    fun aLetterCaseRenameThatBreaksTheFileKeepsTheOldRecipe() {
        write("soup.yaml", recipe("Soup", "u-soup"))
        sync()
        val id = row("u-soup").id
        plan(id)
        renameTo("soup.yaml", "Soup.yaml")
        write("Soup.yaml", "recipe_uuid: u-soup\nrecipe_name: [unclosed\n")
        assertEquals(0, sync().removed)
        assertEquals(id, row("u-soup").id)
        assertEquals(id, planned())
        assertEquals("Soup.yaml" to RecipeFileProblem.Kind.UNREADABLE, problems().single().let { it.fileName to it.kind })
    }

    @Test
    fun aUuidChangedByHandKeepsTheRowOfItsFile() {
        // recipe_sync.py's lookup by file_path: a new uuid in a file the index knows takes over that file's row.
        write("soup.yaml", recipe("Soup", "u-soup"))
        sync()
        val id = row("u-soup").id
        plan(id)
        write("soup.yaml", recipe("Soup", "u-other"))
        assertEquals(RecipeFolder.SyncResult(indexed = 1, unchanged = 0, removed = 0), sync())
        assertEquals(id, row("u-other").id)
        assertEquals(listOf("Soup"), names())
        assertEquals(id, planned())
    }

    @Test
    fun aUuidChangedByHandInALetterCaseRenamedFileKeepsItsRow() {
        write("soup.yaml", recipe("Soup", "u-soup"))
        sync()
        val id = row("u-soup").id
        plan(id)
        renameTo("soup.yaml", "Soup.yaml")
        write("Soup.yaml", recipe("Soup", "u-other"))
        assertEquals(RecipeFolder.SyncResult(indexed = 1, unchanged = 0, removed = 0), sync())
        val soup = row("u-other")
        assertEquals(id to "Soup.yaml", soup.id to soup.fileName)
        assertEquals(id, planned())
    }

    @Test
    fun aCopyNeverTakesTheIdOfALetterCaseRenamedOriginal() {
        write("soup.yaml", recipe("Soup", "u-soup"))
        sync()
        val id = row("u-soup").id
        renameTo("soup.yaml", "Soup.yaml")
        write("B-copy.yaml", recipe("Soup copy", "u-soup")) // sorts before Soup.yaml
        sync()
        val soup = row("u-soup")
        assertEquals(id to "Soup.yaml", soup.id to soup.fileName)
        assertEquals(listOf("Soup"), names())
        val problem = problems().single()
        assertEquals("B-copy.yaml" to RecipeFileProblem.Kind.DUPLICATE_ID, problem.fileName to problem.kind)
        assertTrue(problem.message, "also used by Soup.yaml" in problem.message) // the name on disk now
    }

    @Test
    fun aNewRecipeNeverTakesTheRowOfAGoneFileInAnotherCase() {
        write("Soup.yaml", recipe("Soup", "u-soup"))
        sync()
        val id = row("u-soup").id
        plan(id)
        File(dir, "Soup.yaml").delete() // gone, and not synced yet
        @Suppress("UNCHECKED_CAST")
        val doc = RecipeYaml.load(recipe("Soup")) as YamlMap
        val newId = runBlocking { recipes.save(doc) }
        assertTrue(newId != id)
        assertEquals("soup-2.yaml", row("app-1").fileName)
        assertEquals(id, row("u-soup").id)
        assertEquals(id, planned())
    }
}
