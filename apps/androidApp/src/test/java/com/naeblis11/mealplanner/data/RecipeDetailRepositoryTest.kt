package com.naeblis11.mealplanner.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.YamlMap
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecipeDetailRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: RecipeRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = RecipeRepository(db, Files.createTempDirectory("images").toFile())
    }

    @After
    fun tearDown() = db.close()

    @Suppress("UNCHECKED_CAST")
    private fun doc(yaml: String) = RecipeYaml.load(yaml) as YamlMap

    @Test
    fun observeDetailFollowsSaves() {
        runBlocking {
            val recipe = doc("recipe_name: Soup\ningredients:\n- Salt:\nsteps:\n- step: Stir.\n")
            val id = repo.save(recipe)
            assertEquals(listOf("Salt"), repo.observeDetail(id).first()!!.ingredients.map { it.name })
            recipe["recipe_name"] = "Better soup"
            repo.save(recipe)
            assertEquals("Better soup", repo.observeDetail(id).first()!!.recipe.name)
            repo.delete(id)
            assertNull(repo.observeDetail(id).first())
        }
    }

    @Test
    fun savingAnotherRecipeDoesNotReEmitAnIdenticalDetail() {
        runBlocking {
            val a = repo.save(doc("recipe_name: Soup\ningredients:\n- Salt:\nsteps:\n- step: Stir.\n"))
            val seen = async(Dispatchers.Default) {
                withTimeoutOrNull(1_500) { repo.observeDetail(a).take(2).toList() }
            }
            withContext(Dispatchers.Default) { delay(300) }
            repo.save(doc("recipe_name: Stew\ningredients: []\nsteps: []\n"))
            assertNull("an equal detail was emitted twice", seen.await())
        }
    }

    @Test
    fun namesExceptLeavesOutTheRecipeBeingEdited() {
        runBlocking {
            val soup = repo.save(doc("recipe_name: Soup\ningredients: []\nsteps: []\n"))
            repo.save(doc("recipe_name: Stew\ningredients: []\nsteps: []\n"))
            assertEquals(setOf("stew"), repo.lowerCaseNamesExcept(soup))
            assertEquals(setOf("soup", "stew"), repo.lowerCaseNamesExcept(null))
        }
    }
}
