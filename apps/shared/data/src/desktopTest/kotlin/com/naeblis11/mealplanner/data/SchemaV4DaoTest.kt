package com.naeblis11.mealplanner.data

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaV4DaoTest {
    private val dir: File = Files.createTempDirectory("mp-v4").toFile()
    private val db = AppDatabase.openAt(File(dir, "test.db"))

    @After
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    private fun recipe(uuid: String, file: String?) = RecipeEntity(
        recipeUuid = uuid, name = "Soup", author = null, sourceAuthorsJson = null, sourceUrl = null, sourceBookJson = null,
        ovenTempJson = null, ovenFan = null, ovenTime = null, yieldsJson = null, notesJson = null, category = null,
        subcategory = null, imageFilename = null, rating = null, rawYaml = "recipe_name: Soup",
        fileName = file, fileHash = file?.let { "h-$it" },
    )

    @Test
    fun theFileColumnsFindARecipeByItsFile() = runBlocking {
        val id = db.recipeDao().insertRecipe(recipe("u-1", "soup.yaml"))
        val phoneStyle = db.recipeDao().insertRecipe(recipe("u-2", null))
        assertEquals(id, db.recipeDao().idForFileName("soup.yaml"))
        assertNull(db.recipeDao().idForFileName("stew.yaml"))
        assertEquals("soup.yaml", db.recipeDao().fileNameForUuid("u-1"))
        assertNull(db.recipeDao().fileNameForUuid("u-2"))
        assertEquals(listOf("soup.yaml"), db.recipeDao().fileNames())
        assertEquals(
            listOf(RecipeFileRow(id, "u-1", "soup.yaml", "h-soup.yaml"), RecipeFileRow(phoneStyle, "u-2", null, null)),
            db.recipeDao().fileIndex(),
        )
    }

    @Test
    fun appMetaKeepsOneValuePerKey() = runBlocking {
        assertNull(db.appMetaDao().get("legacy_import"))
        db.appMetaDao().put(AppMetaEntity("legacy_import", "started"))
        db.appMetaDao().put(AppMetaEntity("legacy_import", "done"))
        assertEquals("done", db.appMetaDao().get("legacy_import"))
    }

    @Test
    fun anAisleIsOnlyAddedWhenItsNameIsNew() = runBlocking {
        assertTrue(db.shoppingDao().insertAisleOrIgnore(IngredientAisleEntity(name = "Milk", aisle = "Dairy & Eggs")) > 0)
        assertEquals(-1L, db.shoppingDao().insertAisleOrIgnore(IngredientAisleEntity(name = "milk", aisle = "Bakery")))
        assertEquals("Dairy & Eggs", db.shoppingDao().rememberedAisle("MILK"))
    }
}
