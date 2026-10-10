package com.naeblis11.mealplanner.plan

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.naeblis11.mealplanner.MainDispatcherRule
import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.MealPlanRepository
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.data.ShoppingRepository
import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.YamlMap
import com.naeblis11.mealplanner.shopping.ShoppingMessages
import com.naeblis11.mealplanner.shopping.ShoppingViewModel
import java.nio.file.Files
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MealPlanViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private lateinit var db: AppDatabase
    private lateinit var recipes: RecipeRepository
    private lateinit var plans: MealPlanRepository
    private lateinit var shopping: ShoppingRepository
    private val created = mutableListOf<MealPlanViewModel>()
    private val shoppingVms = mutableListOf<ShoppingViewModel>()
    private val today = LocalDate.of(2026, 10, 1)
    private val monday = LocalDate.of(2026, 9, 28)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        recipes = RecipeRepository(db, Files.createTempDirectory("images").toFile(), dispatcher = Dispatchers.Unconfined)
        plans = MealPlanRepository(db, Dispatchers.Unconfined)
        shopping = ShoppingRepository(db, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        created.forEach { it.viewModelScope.cancel() }
        shoppingVms.forEach { it.viewModelScope.cancel() }
        db.close()
    }

    private fun vm(handle: SavedStateHandle = SavedStateHandle()) =
        MealPlanViewModel(plans, shopping, handle, today = { today }).also { created += it }

    @Suppress("UNCHECKED_CAST")
    private suspend fun recipe(name: String): Long = recipes.save(
        RecipeYaml.load("recipe_name: $name\ningredients:\n- Rice:\n    amounts:\n    - amount: 1\n      unit: cup\nsteps:\n- step: Cook.\n") as YamlMap,
    )

    @Test
    fun startsOnThisWeekAndRemembersTheWeekShown() = runTest {
        val handle = SavedStateHandle()
        val first = vm(handle)
        assertEquals(monday, first.weekStart.value)
        first.nextWeek()
        assertEquals(LocalDate.of(2026, 10, 5), first.weekStart.value)

        // Process death: a new ViewModel with the saved state shows the same week.
        val restored = vm(SavedStateHandle(mapOf(MealPlanViewModel.WEEK_KEY to handle.get<String>(MealPlanViewModel.WEEK_KEY))))
        assertEquals(LocalDate.of(2026, 10, 5), restored.weekStart.value)

        first.previousWeek()
        first.previousWeek()
        assertEquals(LocalDate.of(2026, 9, 21), first.weekStart.value)
        first.thisWeek()
        assertEquals(monday, first.weekStart.value)
    }

    @Test
    fun showsTheWeekAndRemovesAMeal() = runTest {
        plans.assign(monday, "Dinner", recipe("Soup"), "6")
        val vm = vm()
        val week = vm.week.first { it?.days?.get(0)?.slots?.get(2)?.meal != null }!!
        assertEquals("Sep 28 \u2013 Oct 4", week.label)
        assertTrue(week.days[3].isToday)
        assertEquals("6", week.days[0].slots[2].meal!!.servings)

        vm.remove(monday, "Dinner")
        vm.week.first { it?.days?.get(0)?.slots?.get(2)?.meal == null }
    }

    @Test
    fun addsTheWeekShownToTheShoppingList() = runTest {
        plans.assign(monday.plusDays(7), "Dinner", recipe("Soup"), null)
        val vm = vm()
        vm.addWeekToShoppingList()
        assertEquals("Nothing is planned for that week yet.", vm.message.first { it != null }!!.text)
        vm.messageShown(vm.message.value!!)
        vm.adding.first { !it }

        vm.nextWeek()
        vm.addWeekToShoppingList()
        assertEquals("Added the week's meals to your shopping list.", vm.message.first { it != null }!!.text)
        assertEquals(listOf("Rice"), shopping.observe().first().map { it.name })
    }

    @Test
    fun aSecondTapWhileAnAddRunsIsIgnored() = runTest {
        val gate = CompletableDeferred<Int>()
        var calls = 0
        val vm = MealPlanViewModel(plans, shopping, SavedStateHandle(), { today }) {
            calls++
            gate.await()
        }.also { created += it }

        vm.addWeekToShoppingList()
        vm.addWeekToShoppingList()
        assertEquals(1, calls)
        assertTrue(vm.adding.value)

        gate.completeExceptionally(IllegalStateException("disk full"))
        vm.adding.first { !it }
        assertEquals(ShoppingMessages.UPDATE_FAILED, vm.error.value)
        assertFalse(vm.adding.value)

        vm.addWeekToShoppingList()
        assertEquals(2, calls)
    }

    @Test
    fun consumingAnOlderMessageLeavesANewerOne() = runTest {
        plans.assign(monday.plusDays(7), "Dinner", recipe("Soup"), null)
        val vm = vm()
        vm.addWeekToShoppingList()
        val first = vm.message.first { it != null }!!
        vm.adding.first { !it }
        vm.nextWeek()
        vm.addWeekToShoppingList()
        val second = vm.message.first { it != null && it != first }!!
        vm.messageShown(first)
        assertEquals(second, vm.message.value)
        vm.messageShown(second)
        assertEquals(null, vm.message.value)
    }

    @Test
    fun theSameWordsTwiceAreTwoMessages() = runTest {
        val vm = vm()
        vm.addWeekToShoppingList()
        val first = vm.message.first { it != null }!!
        vm.adding.first { !it }
        vm.addWeekToShoppingList() // the same empty week: the same words
        val second = vm.message.first { it != null && it != first }!!
        assertEquals(first.text, second.text)

        // Consuming the first must not swallow the second, although they read the same.
        vm.messageShown(first)
        assertEquals(second, vm.message.value)
        vm.messageShown(second)
        assertEquals(null, vm.message.value)
    }

    @Test
    fun aRestoredWeekAlwaysStartsOnMonday() = runTest {
        val vm = vm(SavedStateHandle(mapOf(MealPlanViewModel.WEEK_KEY to "2026-10-07"))) // a Wednesday
        assertEquals(LocalDate.of(2026, 10, 5), vm.weekStart.value)
    }

    @Test
    fun whileTheCalendarAddsAWeekTheShoppingListsAddIsOffAndIgnored() = runTest {
        plans.assign(monday, "Dinner", recipe("Soup"), null)
        val gate = CompletableDeferred<Int>()
        val calendar = MealPlanViewModel(plans, shopping, SavedStateHandle(), { today }) { gate.await() }
            .also { created += it }
        val list = ShoppingViewModel(shopping) { today }.also { shoppingVms += it }

        calendar.addWeekToShoppingList()
        assertTrue(calendar.adding.value)
        assertTrue(list.adding.value)
        list.addThisWeek()
        assertEquals(emptyList<String>(), shopping.observe().first().map { it.name })

        gate.complete(1)
        list.adding.first { !it }
        assertFalse(calendar.adding.value)
        // Ignored, not queued: nothing was added once the calendar's add finished.
        assertEquals(emptyList<String>(), shopping.observe().first().map { it.name })
        assertEquals(null, list.message.value)

        // Free again: the shopping list's own tap now runs.
        list.addThisWeek()
        assertEquals(listOf("Rice"), shopping.observe().first().map { it.name })
    }

    @Test
    fun aCancelledAddReleasesTheButtonEverywhere() = runTest {
        val calendar = MealPlanViewModel(plans, shopping, SavedStateHandle(), { today }) { CompletableDeferred<Int>().await() }
            .also { created += it }
        calendar.addWeekToShoppingList()
        assertTrue(shopping.adding.value)
        calendar.viewModelScope.cancel()
        assertFalse(shopping.adding.value)
    }
}
