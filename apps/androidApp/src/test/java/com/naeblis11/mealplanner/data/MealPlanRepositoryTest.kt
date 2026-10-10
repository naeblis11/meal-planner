package com.naeblis11.mealplanner.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.YamlMap
import java.nio.file.Files
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
class MealPlanRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var recipes: RecipeRepository
    private lateinit var plans: MealPlanRepository
    private val monday = LocalDate.of(2026, 9, 28)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        recipes = RecipeRepository(db, Files.createTempDirectory("images").toFile(), dispatcher = Dispatchers.Unconfined)
        plans = MealPlanRepository(db, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() = db.close()

    @Suppress("UNCHECKED_CAST")
    private suspend fun recipe(name: String): Long = recipes.save(
        RecipeYaml.load("recipe_name: $name\ningredients:\n- Rice:\n    amounts:\n    - amount: 1\n      unit: cup\nsteps:\n- step: Cook.\n") as YamlMap,
    )

    @Test
    fun assignReplacesAndRemoves() = runTest {
        val soup = recipe("Soup")
        val stew = recipe("Stew")
        plans.assign(monday, "Dinner", soup, servings = "6")
        plans.assign(monday.plusDays(6), "Lunch", stew, servings = null)
        plans.assign(monday.plusDays(7), "Lunch", stew, servings = null) // next week

        assertEquals(
            listOf("2026-09-28 Dinner Soup 6", "2026-10-04 Lunch Stew null"),
            plans.observeWeek(monday).first().map { "${it.date} ${it.slot} ${it.recipeName} ${it.servings}" },
        )

        plans.assign(monday, "Dinner", stew, servings = " ")
        val dinner = plans.assignment(monday, "Dinner")!!
        assertEquals("Stew", dinner.recipeName)
        assertNull(dinner.servings)

        plans.unassign(monday, "Dinner")
        assertNull(plans.assignment(monday, "Dinner"))
    }

    @Test
    fun refusesAnUnknownSlotOrAMissingRecipe() = runTest {
        val soup = recipe("Soup")
        val slot = assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { plans.assign(monday, "Brunch", soup, null) } }
        assertEquals("Invalid slot: 'Brunch'. Must be one of Breakfast, Lunch, Dinner.", slot.message)
        val missing = assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { plans.assign(monday, "Lunch", 999L, null) } }
        assertEquals("That recipe no longer exists.", missing.message)
    }

    @Test
    fun deletingARecipeTakesItOffThePlan() = runTest {
        val soup = recipe("Soup")
        val stew = recipe("Stew")
        plans.assign(monday, "Dinner", soup, null)
        plans.assign(monday.plusDays(1), "Lunch", soup, null)
        plans.assign(monday.plusDays(2), "Lunch", stew, null)
        assertEquals(2, recipes.plannedCount(soup).first())

        recipes.delete(soup)

        assertEquals(listOf("Stew"), plans.observeWeek(monday).first().map { it.recipeName })
        assertEquals(0, recipes.plannedCount(soup).first())
    }

    @Test
    fun savingARecipeAgainKeepsItPlanned() = runTest {
        val soup = recipe("Soup")
        plans.assign(monday, "Dinner", soup, null)
        val doc = recipes.doc(soup)!!
        doc["recipe_name"] = "Better soup"
        recipes.save(doc)
        assertEquals("Better soup", plans.assignment(monday, "Dinner")!!.recipeName)
    }
}
