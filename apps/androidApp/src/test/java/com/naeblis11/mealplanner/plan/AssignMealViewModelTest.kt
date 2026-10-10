package com.naeblis11.mealplanner.plan

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.naeblis11.mealplanner.MainDispatcherRule
import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.MealPlanRepository
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.ServingsInput
import com.naeblis11.mealplanner.domain.YamlMap
import java.nio.file.Files
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AssignMealViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private lateinit var db: AppDatabase
    private lateinit var recipes: RecipeRepository
    private lateinit var plans: MealPlanRepository
    private val monday = LocalDate.of(2026, 9, 28)
    private val created = mutableListOf<AssignMealViewModel>()

    private fun newVm(slot: String): AssignMealViewModel = AssignMealViewModel(monday, slot, plans, recipes).also { created += it }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        recipes = RecipeRepository(db, Files.createTempDirectory("images").toFile(), dispatcher = Dispatchers.Unconfined)
        plans = MealPlanRepository(db, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        created.forEach { it.viewModelScope.cancel() }
        db.close()
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun recipe(name: String): Long = recipes.save(
        RecipeYaml.load("recipe_name: $name\ningredients:\n- Rice:\n    amounts:\n    - amount: 1\n      unit: cup\nsteps:\n- step: Cook.\n") as YamlMap,
    )

    @Test
    fun assignsWithOrWithoutServings() = runTest {
        val soup = recipe("Soup")
        val dinner = newVm("Dinner")
        dinner.assign(soup)
        dinner.assigned.first { it }
        assertNull(plans.assignment(monday, "Dinner")!!.servings)

        val lunch = newVm("Lunch")
        lunch.setServings("1.5")
        lunch.assign(soup)
        lunch.assigned.first { it }
        assertEquals("1 1/2", plans.assignment(monday, "Lunch")!!.servings)
    }

    @Test
    fun aSecondTapAfterTheSaveDoesNotOverwriteIt() = runTest {
        val soup = recipe("Soup")
        val stew = recipe("Stew")
        val vm = newVm("Dinner")
        vm.assign(soup)
        vm.assigned.first { it }
        vm.assign(stew)
        assertEquals(soup, plans.assignment(monday, "Dinner")!!.recipeId)
    }

    @Test
    fun unreadableServingsSaveNothing() = runTest {
        val soup = recipe("Soup")
        val vm = newVm("Dinner")
        vm.setServings("lots")
        vm.assign(soup)
        assertEquals(ServingsInput.HINT, vm.error.value)
        assertFalse(vm.assigned.value)
        assertNull(plans.assignment(monday, "Dinner"))
    }

    @Test
    fun changingAPlannedMealStartsFromItsServings() = runTest {
        plans.assign(monday, "Dinner", recipe("Soup"), "6")
        val vm = newVm("Dinner")
        assertEquals("6", vm.servings.first { it.isNotEmpty() })
    }

    @Test
    fun aRecipeDeletedMeanwhileIsAMessage() = runTest {
        val soup = recipe("Soup")
        recipes.delete(soup)
        val vm = newVm("Dinner")
        vm.assign(soup)
        assertEquals("That recipe no longer exists.", vm.error.first { it != null })
        assertFalse(vm.assigned.value)
    }

    @Test
    fun searchesTheLibrary() = runTest {
        recipe("Soup")
        recipe("Stew")
        val vm = newVm("Dinner")
        vm.setQuery("ste")
        assertEquals(listOf("Stew"), vm.results.first { it?.size == 1 }!!.map { it.name })
    }
}
