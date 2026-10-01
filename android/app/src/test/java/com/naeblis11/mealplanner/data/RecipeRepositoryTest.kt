package com.naeblis11.mealplanner.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.naeblis11.mealplanner.domain.RecipeFormatException
import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.YamlMap
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecipeRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var images: File
    private lateinit var repo: RecipeRepository
    private var uuids = 0

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        images = Files.createTempDirectory("images").toFile()
        repo = RecipeRepository(db, images, newUuid = { "uuid-${++uuids}" })
    }

    @After
    fun tearDown() {
        db.close()
        images.deleteRecursively()
    }

    @Suppress("UNCHECKED_CAST")
    private fun doc(yaml: String) = RecipeYaml.load(yaml) as YamlMap

    private val soup = """
        recipe_name: Soup
        category: Soups & Stews
        rating: 4
        yields:
        - servings: 4
        ingredients:
        - Stock:
            amounts:
            - amount: 250
              unit: ml
            substitutions:
            - Water:
                amounts:
                - amount: 1
                  unit: cup
        - Salt:
            amounts:
            - amount: to taste
              unit: ''
            section: Seasoning
        steps:
        - step: Simmer.
          notes:
          - gently
    """.trimIndent()

    @Test
    fun saveAssignsAUuidAndIndexesTheRecipe() {
        runBlocking {
            val recipe = doc(soup)
            val id = repo.save(recipe)

            assertEquals("uuid-1", recipe["recipe_uuid"])
            assertEquals("recipe_uuid", recipe.keys.first())
            val row = db.recipeDao().recipe(id)!!
            assertEquals("uuid-1", row.recipeUuid)
            assertEquals("Soup", row.name)
            assertEquals("Soups & Stews", row.category)
            assertEquals(4, row.rating)
            assertEquals("""[{"amount":4,"unit":"servings"}]""", row.yieldsJson)
            val ingredients = db.recipeDao().ingredients(id)
            assertEquals(listOf("Stock", "Salt"), ingredients.map { it.name })
            assertEquals("1 1/24" to "cup", ingredients[0].amount to ingredients[0].unit)
            assertEquals("Seasoning", ingredients[1].section)
            assertTrue(ingredients[0].substitutionsJson!!.contains("Water"))
            assertEquals(listOf("Simmer."), db.recipeDao().steps(id).map { it.stepText })
        }
    }

    @Test
    fun savingTheSameUuidUpdatesInPlace() {
        runBlocking {
            val recipe = doc(soup)
            val id = repo.save(recipe)
            recipe["recipe_name"] = "Better soup"
            assertEquals(id, repo.save(recipe))
            assertEquals(listOf("Better soup"), db.recipeDao().allRecipes().map { it.name })
            assertEquals(2, db.recipeDao().ingredients(id).size)
        }
    }

    @Test
    fun anInvalidRecipeWritesNothing() {
        runBlocking {
            val good = doc(soup)
            val bad = doc("recipe_name: Broken\ningredients: []\n")
            assertThrows(RecipeFormatException::class.java) { runBlocking { repo.saveAll(listOf(good, bad)) } }
            assertEquals(emptyList<RecipeEntity>(), db.recipeDao().allRecipes())
        }
    }

    @Test
    fun docRoundTripsTheSavedYaml() {
        runBlocking {
            val recipe = doc(soup)
            val id = repo.save(recipe)
            assertEquals(recipe, repo.doc(id))
        }
    }

    @Test
    fun deleteRemovesRowsAndPhotosNoOtherRecipeUses() {
        runBlocking {
            File(images, "a.jpg").writeText("x")
            File(images, "a_thumb.jpg").writeText("x")
            File(images, "shared.jpg").writeText("x")
            val one = repo.save(doc("recipe_name: One\nimage: a.jpg\ningredients: []\nsteps: []\n"))
            val two = repo.save(doc("recipe_name: Two\nimage: shared.jpg\ningredients: []\nsteps: []\n"))
            repo.save(doc("recipe_name: Three\nimage: shared.jpg\ningredients: []\nsteps: []\n"))

            repo.delete(one)
            repo.delete(two)

            assertNull(repo.doc(one))
            assertFalse(File(images, "a.jpg").exists())
            assertFalse(File(images, "a_thumb.jpg").exists())
            assertTrue(File(images, "shared.jpg").exists())
        }
    }

    @Test
    fun namesAndSearch() {
        runBlocking {
            repo.save(doc(soup))
            repo.save(doc("recipe_name: Cake\ningredients: []\nsteps: []\n"))
            assertEquals(setOf("soup", "cake"), repo.lowerCaseNames())
            assertEquals(listOf("Cake", "Soup"), repo.summaries("").first().map { it.name })
            assertEquals(listOf("Soup"), repo.summaries("season").first().map { it.name })
        }
    }

    @Test
    fun imageNameRules() {
        assertEquals("x_thumb.jpg", RecipeRepository.thumbName("x.jpg"))
        assertEquals("x.png", RecipeRepository.thumbName("x.png"))
        assertTrue(RecipeRepository.isSafeImageName("0a-b_c.jpg"))
        assertFalse(RecipeRepository.isSafeImageName("../x.jpg"))
        assertFalse(RecipeRepository.isSafeImageName(".."))
        assertFalse(RecipeRepository.isSafeImageName("a/b.jpg"))
    }
}
