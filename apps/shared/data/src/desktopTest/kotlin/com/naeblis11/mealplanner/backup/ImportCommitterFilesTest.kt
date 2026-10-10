package com.naeblis11.mealplanner.backup

import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.FILE_HOLDS_ANOTHER_RECIPE
import com.naeblis11.mealplanner.data.RECIPE_HELD
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.data.openAt
import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.YamlMap
import com.naeblis11.mealplanner.folder.FolderFileStore
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The import's Confirm on the desktop, where the recipe folder is the source of truth: a save the repository refuses
 * before writing (P7-R10f: a held recipe, a file that now holds another recipe) leaves the photo folder and the recipe
 * folder exactly as they were.
 */
class ImportCommitterFilesTest {
    private val root: File = Files.createTempDirectory("mp-import-files").toFile()
    private val dir = File(root, "recipes").apply { mkdirs() }
    private val images = File(root, "recipe-images").apply { mkdirs() }
    private val staging = File(root, "staging").apply { mkdirs() }
    private val db = AppDatabase.openAt(File(root, "test.db"))
    private var uuids = 0
    private val ids = { "uuid-${++uuids}" }
    private val repo = RecipeRepository(db, images, ids, files = FolderFileStore(dir, moveToTrash = { false }))
    private val committer = ImportCommitter(repo, images, ids)

    @After
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    @Suppress("UNCHECKED_CAST")
    private fun doc(yaml: String) = RecipeYaml.load(yaml) as YamlMap

    private fun recipeYaml(name: String, uuid: String, image: String? = null) =
        "recipe_uuid: $uuid\nrecipe_name: $name\n" + (image?.let { "image: $it\n" } ?: "") +
            "ingredients:\n- Rice:\n    amounts:\n    - amount: '1'\n      unit: cup\nsteps:\n- step: Cook.\n"

    // Photos are files in the staging folder, as ImportReader leaves them.
    private fun bundle(vararg yamls: String, images: Map<String, ByteArray>) = ImportBundle(
        "test.zip",
        yamls.map { y -> doc(y).let { IncomingRecipe(it["recipe_name"] as String, it) } },
        images.mapValues { (name, bytes) -> File(staging, name).apply { writeBytes(bytes) } },
        emptyList(),
    )

    /** Every file in [folder] with its bytes, to compare before and after a refused import. */
    private fun contents(folder: File): Map<String, List<Byte>> = folder.listFiles()!!.associate { it.name to it.readBytes().toList() }

    /** Soup (uuid known, photo.jpg) in the library, and an import that updates it with a new photo and adds Rice with its own. */
    private suspend fun stagedUpdateOfSoupAndNewRice(): StagedImport {
        File(images, "photo.jpg").writeBytes(byteArrayOf(9))
        repo.save(doc(recipeYaml("Soup", uuid = "known", image = "photo.jpg")))
        return ImportStager.stage(
            bundle(
                // Rice first, so its recipe file is written before Soup's save is refused, and must be undone.
                recipeYaml("Rice", uuid = "rice", image = "r.jpg"),
                recipeYaml("Soup", uuid = "known", image = "photo.jpg"),
                images = mapOf("r.jpg" to byteArrayOf(2), "photo.jpg" to byteArrayOf(1)),
            ),
            repo,
        )
    }

    private val decisions = listOf(ImportDecision(0, ImportAction.IMPORT, "Rice"), ImportDecision(1, ImportAction.UPDATE, "Soup"))

    private fun confirmRefused(staged: StagedImport, message: String) {
        val refused = assertThrows(IOException::class.java) { runBlocking { committer.confirm(staged, decisions) } }
        assertEquals(message, refused.message)
    }

    @Test
    fun aSaveRefusedForAHeldRecipeRollsBackThePhotosAndTheRecipeFiles() = runBlocking {
        val staged = stagedUpdateOfSoupAndNewRice()
        val folderBefore = contents(dir)
        // The folder sync holds Soup (its file went missing, or the library moved): no save to it, P7-R10f.
        repo.isHeld = { true }

        confirmRefused(staged, RECIPE_HELD)

        assertArrayEquals(byteArrayOf(9), File(images, "photo.jpg").readBytes())
        assertEquals(setOf("photo.jpg"), images.list()!!.toSet())
        assertEquals(folderBefore, contents(dir))
        assertEquals(listOf("Soup"), repo.allRecipes().map { it.name })
    }

    @Test
    fun aSaveRefusedBecauseTheFileHoldsAnotherRecipeRollsBackThePhotosAndTheRecipeFiles() = runBlocking {
        val staged = stagedUpdateOfSoupAndNewRice()
        // soup.yaml now holds another recipe (a moved library, P7-R10f): never written over.
        val other = recipeYaml("Other", uuid = "other")
        File(dir, "soup.yaml").writeText(other)
        val folderBefore = contents(dir)

        confirmRefused(staged, FILE_HOLDS_ANOTHER_RECIPE)

        assertArrayEquals(byteArrayOf(9), File(images, "photo.jpg").readBytes())
        assertEquals(setOf("photo.jpg"), images.list()!!.toSet())
        assertEquals(folderBefore, contents(dir))
        assertEquals(other, File(dir, "soup.yaml").readText())
        assertEquals(listOf("Soup"), repo.allRecipes().map { it.name })
    }
}
