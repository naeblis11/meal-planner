package com.naeblis11.mealplanner.data

import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.YamlMap
import com.naeblis11.mealplanner.folder.FolderFileStore
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** app.py's recipe_set_category: both fields trimmed, a blank one written as None. */
class RecipeCategoryTest {
    private val dir: File = Files.createTempDirectory("mp-category").toFile()
    private val db = AppDatabase.openAt(File(dir, "test.db"))
    private val recipes = RecipeRepository(db, File(dir, "images").apply { mkdirs() })

    @After
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    @Suppress("UNCHECKED_CAST")
    private fun soupDoc(): YamlMap =
        RecipeYaml.load("recipe_name: Soup\ningredients:\n- Stock:\n    amounts:\n    - amount: 2\n      unit: cup\nsteps:\n- step: Simmer.\n") as YamlMap

    private suspend fun soup(): Long = recipes.save(soupDoc())

    @Test
    fun theCategoryIsWrittenTrimmed() = runBlocking {
        val id = soup()
        recipes.setCategory(id, "  Soups & Stews ", " Beef ")
        val row = db.recipeDao().recipe(id)!!
        assertEquals("Soups & Stews", row.category)
        assertEquals("Beef", row.subcategory)
    }

    @Test
    fun aBlankCategoryIsWrittenAsNoneAsOnTheServer() = runBlocking {
        val id = soup()
        recipes.setCategory(id, "Soups & Stews", "Beef")
        recipes.setCategory(id, " ", "")
        val doc = recipes.doc(id)!!
        assertEquals("None", doc["category"])
        assertEquals("None", doc["subcategory"])
    }

    @Test
    fun onTheDesktopTheCategoryIsWrittenToTheFileAndNeverToAMissingOne() = runBlocking {
        // The desktop's locked save path: the file first, and a recipe whose file is gone is refused.
        val folder = File(dir, "recipes").apply { mkdirs() }
        val desktop = RecipeRepository(db, File(dir, "images"), files = FolderFileStore(folder, moveToTrash = null))
        val id = desktop.save(soupDoc())
        val file = File(folder, "soup.yaml")
        desktop.setCategory(id, "Soups & Stews", "Beef")
        val text = file.readText()
        assertTrue(text, text.contains("category: Soups & Stews"))
        assertTrue(text, text.contains("subcategory: Beef"))

        assertTrue(file.delete())
        assertThrows(IOException::class.java) { runBlocking { desktop.setCategory(id, "Salads", "") } }
        assertFalse(file.exists())
        assertEquals("Soups & Stews", db.recipeDao().recipe(id)!!.category)
    }
}
