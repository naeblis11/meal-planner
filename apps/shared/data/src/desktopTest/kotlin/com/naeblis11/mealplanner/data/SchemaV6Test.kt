package com.naeblis11.mealplanner.data

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Schema 6 (LAN sync, stage 1): each shopping row's sync id, a recipe's photo hash, and sync_change. */
class SchemaV6Test {
    private val dir: File = Files.createTempDirectory("mp-v6").toFile()
    private val db = AppDatabase.openAt(File(dir, "test.db"))

    @After
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    @Test
    fun everyNewShoppingRowGetsItsOwnSyncId() = runBlocking {
        val dao = db.shoppingDao()
        dao.insert(ShoppingItemEntity(name = "Eggs"))
        dao.insert(ShoppingItemEntity(name = "Eggs"))
        val ids = dao.allInIdOrder().map { it.syncId }
        assertEquals(2, ids.toSet().size)
        ids.forEach { assertTrue(it, Regex("[0-9a-f]{32}").matches(it)) }
    }

    @Test
    fun aNewRecipeHasNoPhotoHashAndANewDatabaseNoChanges() = runBlocking {
        val id = db.recipeDao().insertRecipe(
            RecipeEntity(
                recipeUuid = "u-1", name = "Soup", author = null, sourceAuthorsJson = null, sourceUrl = null,
                sourceBookJson = null, ovenTempJson = null, ovenFan = null, ovenTime = null, yieldsJson = null,
                notesJson = null, category = null, subcategory = null, imageFilename = null, rating = null,
                rawYaml = "recipe_name: Soup",
            ),
        )
        assertNull(db.recipeDao().recipe(id)!!.imageHash)
        assertEquals(emptyList<SyncChangeEntity>(), db.syncDao().changedSince(-1).filter { it.tableName != "recipe" })
    }
}
