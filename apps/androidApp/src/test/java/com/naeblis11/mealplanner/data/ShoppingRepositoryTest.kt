package com.naeblis11.mealplanner.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.YamlMap
import java.nio.file.Files
import java.time.LocalDate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShoppingRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var recipes: RecipeRepository
    private lateinit var plans: MealPlanRepository
    private lateinit var shopping: ShoppingRepository
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
    fun tearDown() = db.close()

    // Serves 4: 2 cup flour, 1 tsp salt.
    @Suppress("UNCHECKED_CAST")
    private suspend fun pancakes(): Long = recipes.save(
        RecipeYaml.load(
            "recipe_name: Pancakes\nyields:\n- servings: 4\ningredients:\n" +
                "- Flour:\n    amounts:\n    - amount: 2\n      unit: cup\n" +
                "- Salt:\n    amounts:\n    - amount: 1\n      unit: tsp\n" +
                "steps:\n- step: Mix.\n",
        ) as YamlMap,
    )

    private suspend fun rows() = db.shoppingDao().allInIdOrder()
        .map { listOf(it.name, it.amount, it.unit, it.aisle, it.inPantry.toString(), it.checked.toString()) }

    @Test
    fun addingAWeekScalesEachMealAndKeepsWhatIsAlreadyThere() = runTest {
        val towels = shopping.addItem("Paper towels").id
        shopping.setChecked(towels, true)
        val id = pancakes()
        plans.assign(monday, "Dinner", id, "8") // doubled
        plans.assign(monday.plusDays(1), "Breakfast", id, null)
        plans.assign(monday.plusDays(7), "Breakfast", id, null) // next week: not added

        assertEquals(2, shopping.addWeek(monday))

        assertEquals(
            listOf(
                listOf("Paper towels", null, null, "Uncategorized", "false", "true"), // "paper towel" is a keyword, but the guess doesn't match it against the plural
                listOf("Flour", "6", "cups", "Dry Goods & Pasta", "false", "false"),
                listOf("Salt", "3", "tsp", "Spices & Baking", "false", "false"),
            ),
            rows(),
        )
    }

    @Test
    fun addingARecipeFromItsPageScalesItAndMergesIntoTheList() = runTest {
        // Owner, 2026-10-09: "Add to shopping list" on a recipe works as one planned meal does with "Add this week".
        val id = pancakes()
        shopping.addItem("Flour", "1", "cup")
        PantryRepository(db, Dispatchers.Unconfined).stock("Salt")

        assertTrue(shopping.addRecipe(id, "8")) // doubled

        assertEquals(
            listOf(
                listOf("Flour", "5", "cups", "Dry Goods & Pasta", "false", "false"), // 1 already there + 4
                listOf("Salt", "2", "tsp", "Spices & Baking", "true", "false"), // on hand: marked, not bought
            ),
            rows(),
        )
    }

    @Test
    fun addingARecipeAsWrittenAndAGoneOne() = runTest {
        val id = pancakes()
        assertTrue(shopping.addRecipe(id, null))
        assertEquals(listOf("2", "1"), db.shoppingDao().allInIdOrder().map { it.amount })
        assertFalse(shopping.addRecipe(id + 999, null))
        assertEquals(2, db.shoppingDao().allInIdOrder().size)
    }

    @Test
    fun addingTheWeekAgainAddsMoreAndUnchecksTheRow() = runTest {
        plans.assign(monday, "Dinner", pancakes(), null)
        shopping.addWeek(monday)
        val flour = db.shoppingDao().byName("flour").single()
        shopping.setChecked(flour.id, true)

        shopping.addWeek(monday)

        val after = db.shoppingDao().item(flour.id)!!
        assertEquals("4" to "cups", after.amount to after.unit)
        assertFalse(after.checked)
        assertEquals(2, db.shoppingDao().allInIdOrder().size)
    }

    @Test
    fun aCrossedOutPantryItemIsNotOnHand() = runTest {
        db.pantryDao().insertOrIgnore(PantryItemEntity(name = "flour"))
        val salt = db.pantryDao().insertOrIgnore(PantryItemEntity(name = "salt"))
        db.pantryDao().markOut(salt)
        plans.assign(monday, "Dinner", pancakes(), null)

        shopping.addWeek(monday)

        assertTrue(db.shoppingDao().byName("Flour").single().inPantry)
        assertFalse(db.shoppingDao().byName("Salt").single().inPantry)
    }

    @Test
    fun aChosenAisleIsRememberedForTheIngredient() = runTest {
        plans.assign(monday, "Dinner", pancakes(), null)
        shopping.addWeek(monday)
        shopping.setAisle(db.shoppingDao().byName("Flour").single().id, " Bakery ")
        assertEquals("Bakery", db.shoppingDao().rememberedAisle("flour"))

        shopping.clear()
        assertEquals("Bakery", db.shoppingDao().item(shopping.addItem("FLOUR").id)!!.aisle)
        shopping.clear()
        shopping.addWeek(monday)
        assertEquals("Bakery", db.shoppingDao().byName("Flour").single().aisle)

        shopping.setAisle(db.shoppingDao().byName("Flour").single().id, "")
        assertEquals(null, db.shoppingDao().rememberedAisle("flour"))
    }

    @Test
    fun addingByHandAddsMergesOrSaysItIsThere() = runTest {
        val milk = shopping.addItem("  Milk ", "1", "qt")
        assertEquals(AddStatus.ADDED, milk.status)
        assertEquals("Dairy & Eggs", db.shoppingDao().item(milk.id)!!.aisle)

        shopping.setChecked(milk.id, true)
        assertEquals(AddItemResult(AddStatus.MERGED, milk.id), shopping.addItem("milk", "1", "qt"))
        val merged = db.shoppingDao().item(milk.id)!!
        assertEquals("2" to "qt", merged.amount to merged.unit)
        assertFalse(merged.checked)

        assertEquals(AddStatus.DUPLICATE, shopping.addItem("MILK").status)
        val pounds = shopping.addItem("Milk", "1", "lb") // can't combine with quarts
        assertEquals(AddStatus.ADDED, pounds.status)
        assertEquals("Snacks", shopping.addItem("Chips", aisle = "Snacks").let { db.shoppingDao().item(it.id)!!.aisle })
    }

    @Test
    fun removeAndClearAreTheOnlyWaysOff() = runTest {
        val a = shopping.addItem("Apples").id
        shopping.addItem("Bread")
        shopping.remove(a)
        assertEquals(listOf("Bread"), shopping.observe().first().map { it.name })
        shopping.clear()
        assertEquals(emptyList<ShoppingItemEntity>(), shopping.observe().first())
    }

    @Test
    fun anEmptyWeekChangesNothing() = runTest {
        shopping.addItem("Bread")
        assertEquals(0, shopping.addWeek(monday))
        assertEquals(listOf("Bread"), shopping.observe().first().map { it.name })
    }

    /** Queues work and lets the test run it newest first, so any unserialised write lands out of order. */
    private class NewestFirstDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) = synchronized(queue) { queue.addLast(block) }
        fun runNewest(): Boolean = synchronized(queue) { queue.removeLastOrNull() }?.also { it.run() } != null
    }

    @Test
    fun quickTapsApplyInTapOrder() = runTest {
        val id = shopping.addItem("Milk").id
        val gate = NewestFirstDispatcher()
        val tapping = ShoppingRepository(db, gate)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val first = scope.launch { tapping.setChecked(id, true) }
        val second = scope.launch { tapping.setChecked(id, false) }

        val deadline = System.nanoTime() + 10_000_000_000L
        while (!(first.isCompleted && second.isCompleted)) {
            check(System.nanoTime() < deadline) { "taps never finished" }
            if (!gate.runNewest()) Thread.sleep(1)
        }

        assertFalse(db.shoppingDao().item(id)!!.checked)
    }
}
