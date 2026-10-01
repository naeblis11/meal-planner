package com.naeblis11.mealplanner.shopping

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.naeblis11.mealplanner.MainDispatcherRule
import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.MealPlanRepository
import com.naeblis11.mealplanner.data.PantryItemEntity
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.data.ShoppingItemEntity
import com.naeblis11.mealplanner.data.ShoppingRepository
import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.YamlMap
import java.nio.file.Files
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
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
class ShoppingViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private lateinit var db: AppDatabase
    private lateinit var recipes: RecipeRepository
    private lateinit var plans: MealPlanRepository
    private lateinit var shopping: ShoppingRepository
    private val today = LocalDate.of(2026, 10, 1)

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
        made.forEach { it.viewModelScope.cancel() }
        db.close()
    }

    private val made = mutableListOf<ShoppingViewModel>()

    private fun vm() = ShoppingViewModel(shopping) { today }.also { made += it }

    @Suppress("UNCHECKED_CAST")
    private suspend fun riceDinnerThisWeek() {
        val id = recipes.save(
            RecipeYaml.load("recipe_name: Rice bowl\ningredients:\n- Rice:\n    amounts:\n    - amount: 1\n      unit: cup\nsteps:\n- step: Cook.\n") as YamlMap,
        )
        plans.assign(LocalDate.of(2026, 9, 29), "Dinner", id, null)
    }

    @Test
    fun aTickShowsAtOnceIsWrittenStraightAwayAndTheLastTapWins() = runTest {
        shopping.addItem("Milk")
        shopping.addItem("Bread")
        val vm = vm()
        val items = vm.state.first { it?.needToBuy?.isNotEmpty() == true }!!.needToBuy.flatMap { it.items }
        val milk = items.single { it.name == "Milk" }
        val bread = items.single { it.name == "Bread" }

        vm.setChecked(milk, true)
        vm.state.first { s -> s!!.needToBuy.flatMap { it.items }.single { it.name == "Milk" }.checked }
        shopping.observe().first { rows -> rows.single { it.name == "Milk" }.checked }

        // Fast taps: written in tap order, so the database ends as the last tap left it.
        vm.setChecked(milk, false)
        vm.setChecked(milk, true)
        vm.setChecked(milk, false)
        vm.setChecked(bread, true) // queued behind Milk's writes
        shopping.observe().first { rows -> rows.single { it.name == "Bread" }.checked }
        assertFalse(shopping.observe().first().single { it.name == "Milk" }.checked)
        assertFalse(vm.state.first { s -> s!!.needToBuy.flatMap { it.items }.single { it.name == "Bread" }.checked }!!
            .needToBuy.flatMap { it.items }.single { it.name == "Milk" }.checked)
    }

    @Test
    fun addingByHandChecksTheAmountFirst() = runTest {
        val vm = vm()
        vm.addItem("Flour", "lots", "cup", "")
        assertEquals("Couldn't read the amount 'lots' -- try a number like 2, 1/2 or 1.5.", vm.error.value)
        assertTrue(shopping.observe().first().isEmpty())

        vm.addItem("Flour", "2", "cup", "")
        assertEquals("Added 'Flour' to your shopping list.", vm.message.first { it != null })
        vm.messageShown(vm.message.value!!)
        vm.addItem("flour", "1", "cup", "")
        assertEquals("Added more 'flour' to the one already on your list.", vm.message.first { it != null })
        vm.messageShown(vm.message.value!!)
        vm.addItem("Flour", "", "cup", "")
        assertEquals("'Flour' is already on your shopping list.", vm.message.first { it != null })
        vm.addItem(" ", "", "", "")
        assertEquals("Please enter an item name.", vm.error.value)
    }

    @Test
    fun addThisWeekAddsTheCurrentWeekAndKeepsTheKitchenApart() = runTest {
        riceDinnerThisWeek()
        shopping.addItem("Milk")
        db.pantryDao().insertOrIgnore(PantryItemEntity(name = "rice"))
        val vm = vm()
        vm.addThisWeek()
        assertEquals("Added the week's meals to your shopping list.", vm.message.first { it != null })
        val state = vm.state.first { it?.alreadyHave?.isNotEmpty() == true }!!
        assertEquals(listOf("Rice"), state.alreadyHave.map { it.name })
        assertEquals(listOf("Milk"), state.needToBuy.flatMap { it.items }.map { it.name })
    }

    @Test
    fun removeAndClear() = runTest {
        shopping.addItem("Milk")
        shopping.addItem("Bread")
        val vm = vm()
        val milk = vm.state.first { it?.needToBuy?.isNotEmpty() == true }!!.needToBuy.flatMap { it.items }.single { it.name == "Milk" }
        vm.remove(milk)
        assertEquals("Removed 'Milk' from your shopping list.", vm.message.first { it != null })
        vm.messageShown(vm.message.value!!)
        vm.clear()
        assertEquals("Cleared your shopping list.", vm.message.first { it != null })
        assertTrue(vm.state.first { it?.isEmpty == true }!!.isEmpty)
    }

    private fun vmOver(items: kotlinx.coroutines.flow.Flow<List<ShoppingItemEntity>>) =
        ShoppingViewModel(shopping, items) { today }.also { made += it }

    private fun renameShoppingTable(from: String, to: String) =
        db.openHelper.writableDatabase.execSQL("ALTER TABLE $from RENAME TO $to")

    @Test
    fun aTickNeverFlashesTheOldStateWhileTheDatabaseCatchesUp() = runTest {
        val milk = shopping.addItem("Milk")
        val rows = MutableStateFlow(shopping.observe().first())
        val vm = vmOver(rows)
        val seen = mutableListOf<ShoppingState?>()
        backgroundScope.launch(Dispatchers.Unconfined) { vm.state.collect { seen += it } }
        val before = seen.size

        vm.setChecked(rows.value.single(), true)
        // The write has landed, but the rows the screen watches have not re-emitted yet.
        assertTrue(shopping.observe().first().single().checked)
        assertTrue(seen.last()!!.needToBuy.flatMap { it.items }.single().checked)

        rows.value = shopping.observe().first()
        vm.state.first { s -> s!!.needToBuy.flatMap { it.items }.single().checked }
        val after = seen.drop(before).filterNotNull().flatMap { it.needToBuy.flatMap { g -> g.items } }
        assertTrue("an emission showed the old state: $after", after.all { it.checked })
        assertEquals(milk.id, rows.value.single().id)
    }

    @Test
    fun aFailedTickRevertsTheRowAndSaysSo() = runTest {
        shopping.addItem("Milk")
        val rows = MutableStateFlow(shopping.observe().first())
        val vm = vmOver(rows)
        val seen = mutableListOf<ShoppingState?>()
        backgroundScope.launch(Dispatchers.Unconfined) { vm.state.collect { seen += it } }
        renameShoppingTable("shopping_list_item", "away")
        vm.setChecked(rows.value.single(), true)
        // Room runs the write on its own thread: wait for the failure before putting the table back.
        assertEquals("That item could not be updated.", vm.message.first { it != null })
        renameShoppingTable("away", "shopping_list_item")
        vm.state.first { s -> !s!!.needToBuy.flatMap { it.items }.single().checked }
        assertFalse(seen.last()!!.needToBuy.flatMap { it.items }.single().checked)
        assertFalse(shopping.observe().first().single().checked)
    }

    @Test
    fun aFailedAddThisWeekReleasesTheButtonAndALaterTapRunsAgain() = runTest {
        riceDinnerThisWeek()
        val vm = vm()
        renameShoppingTable("shopping_list_item", "away")
        vm.addThisWeek()
        assertEquals(ShoppingMessages.UPDATE_FAILED, vm.error.first { it != null })
        vm.adding.first { !it }

        renameShoppingTable("away", "shopping_list_item")
        vm.addThisWeek()
        assertEquals("Added the week's meals to your shopping list.", vm.message.first { it != null })
        assertEquals(null, vm.error.value)
        assertEquals(listOf("Rice"), shopping.observe().first().map { it.name })
    }

    @Test
    fun aTickDoesNotFlashWhenAFreshQueryAlreadySeesTheWriteButTheLongLivedOneHasNot() = runTest {
        shopping.addItem("Milk")
        val gate = CompletableDeferred<Unit>()
        var subscriptions = 0
        // Room's race: a NEW query sees the committed write at once; the first (long-lived) one re-emits later.
        val items = flow {
            val n = ++subscriptions
            emit(shopping.observe().first())
            if (n == 1) {
                gate.await()
                emit(shopping.observe().first())
            } else {
                awaitCancellation()
            }
        }
        val vm = vmOver(items)
        val seen = mutableListOf<ShoppingState?>()
        backgroundScope.launch(Dispatchers.Unconfined) { vm.state.collect { seen += it } }
        val milk = vm.state.first { it != null }!!.needToBuy.flatMap { it.items }.single()
        val before = seen.size

        vm.setChecked(milk, true)
        assertTrue(shopping.observe().first().single().checked) // written
        gate.complete(Unit) // the long-lived subscription catches up
        vm.state.first { s -> s!!.needToBuy.flatMap { it.items }.single().checked }

        assertEquals(1, subscriptions)
        val after = seen.drop(before).filterNotNull().flatMap { it.needToBuy.flatMap { g -> g.items } }
        assertTrue("an emission showed the old state: $after", after.isNotEmpty() && after.all { it.checked })
    }

    @Test
    fun consumingAnOlderMessageLeavesANewerOne() = runTest {
        val vm = vm()
        vm.addItem("Salt", "", "", "")
        val first = vm.message.first { it != null }!!
        vm.addItem("Pepper", "", "", "")
        val second = vm.message.first { it != null && it != first }!!
        vm.messageShown(first)
        assertEquals(second, vm.message.value)
        vm.messageShown(second)
        assertEquals(null, vm.message.value)
    }
}
