package com.naeblis11.mealplanner.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecipeDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: RecipeDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.recipeDao()
    }

    @After
    fun tearDown() = db.close()

    private fun recipe(uuid: String, name: String, category: String? = null, image: String? = null) = RecipeEntity(
        recipeUuid = uuid, name = name, author = null, sourceAuthorsJson = null, sourceUrl = null,
        sourceBookJson = null, ovenTempJson = null, ovenFan = null, ovenTime = null, yieldsJson = null,
        notesJson = null, category = category, subcategory = null, imageFilename = image, rating = null,
        rawYaml = "recipe_name: $name\n",
    )

    private fun ingredient(recipeId: Long, order: Int, name: String, section: String? = null) = RecipeIngredientEntity(
        recipeId = recipeId, orderNum = order, name = name, usdaNum = null, amount = "1", unit = "cup",
        section = section, amountsJson = "[]", processingJson = null, notesJson = null, substitutionsJson = null,
    )

    @Test
    fun insertsAndReadsARecipeWithItsRows() {
        runBlocking {
            val id = dao.insertRecipe(recipe("u1", "Soup"))
            dao.insertIngredients(listOf(ingredient(id, 1, "Salt"), ingredient(id, 0, "Stock")))
            dao.insertSteps(listOf(RecipeStepEntity(recipeId = id, orderNum = 0, stepText = "Simmer.", stepNotesJson = null, haccpJson = null)))

            assertEquals(id, dao.idForUuid("u1"))
            assertEquals("Soup", dao.recipe(id)!!.name)
            assertEquals(listOf("Stock", "Salt"), dao.ingredients(id).map { it.name })
            assertEquals(listOf("Simmer."), dao.steps(id).map { it.stepText })
        }
    }

    @Test
    fun recipeUuidIsUnique() {
        runBlocking {
            dao.insertRecipe(recipe("u1", "Soup"))
            assertThrows(Exception::class.java) { runBlocking { dao.insertRecipe(recipe("u1", "Other")) } }
        }
    }

    @Test
    fun deletingARecipeRemovesItsRows() {
        runBlocking {
            val id = dao.insertRecipe(recipe("u1", "Soup"))
            dao.insertIngredients(listOf(ingredient(id, 0, "Stock")))
            dao.deleteIngredients(id)
            dao.deleteSteps(id)
            dao.deleteRecipe(id)
            assertNull(dao.recipe(id))
            assertEquals(emptyList<RecipeIngredientEntity>(), dao.ingredients(id))
            assertNull(dao.idForUuid("u1"))
        }
    }

    @Test
    fun searchMatchesNameCategoryIngredientsAndSections() {
        runBlocking {
            val soup = dao.insertRecipe(recipe("u1", "Soup", category = "Soups & Stews"))
            val cake = dao.insertRecipe(recipe("u2", "Cake", category = "Desserts"))
            dao.insertIngredients(listOf(ingredient(cake, 0, "Butter", section = "Frosting"), ingredient(cake, 1, "Flour")))

            assertEquals(listOf("Cake", "Soup"), dao.observeSummaries().first().map { it.name })
            assertEquals(listOf("Cake"), dao.search("%frost%").first().map { it.name })
            assertEquals(listOf("Soup"), dao.search("%stew%").first().map { it.name })
            assertEquals(listOf("Cake"), dao.search("%flour%").first().map { it.name })
            assertEquals(soup, dao.search("%soup%").first().single().id)
        }
    }

    @Test
    fun countsRecipesSharingAPhoto() {
        runBlocking {
            dao.insertRecipe(recipe("u1", "A", image = "p.jpg"))
            dao.insertRecipe(recipe("u2", "B", image = "p.jpg"))
            assertEquals(2, dao.countImageUsers("p.jpg"))
            assertEquals(listOf("A", "B"), dao.names().sorted())
        }
    }
}
