package com.naeblis11.mealplanner.pantry

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.naeblis11.mealplanner.MainDispatcherRule
import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.PantryRepository
import com.naeblis11.mealplanner.data.ShoppingRepository
import com.naeblis11.mealplanner.domain.PantryDates
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PantryViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private lateinit var db: AppDatabase
    private lateinit var pantry: PantryRepository
    private lateinit var shopping: ShoppingRepository
    private val created = mutableListOf<PantryViewModel>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        pantry = PantryRepository(db, Dispatchers.Unconfined) { LocalDate.of(2026, 10, 1) }
        shopping = ShoppingRepository(db, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        created.forEach { it.viewModelScope.cancel() }
        db.close()
    }

    private fun newVm() = PantryViewModel(pantry, shopping).also { created += it }

    @Test
    fun addsAndSaysWhenItIsAlreadyThere() = runTest {
        val vm = newVm()
        vm.add("Salt", "")
        assertEquals("Added 'Salt' to your pantry.", vm.message.first { it != null })
        vm.messageShown(vm.message.value!!)
        vm.add("salt", "")
        assertEquals("'Salt' is already in your pantry.", vm.message.first { it != null })
        vm.add(" ", "")
        assertEquals("Please enter an ingredient name.", vm.error.value)
    }

    @Test
    fun crossingAnItemOutMovesItToRemoved() = runTest {
        pantry.add("Salt", "Spices & Baking")
        pantry.add("Rice")
        val vm = newVm()
        val before = vm.state.first { it?.onHand?.size == 2 }!!
        assertEquals(listOf("Spices & Baking", "Uncategorized"), before.onHand.map { it.aisle })

        vm.setOnHand(before.onHand[0].items.single(), false)
        assertEquals("Removed 'Salt' from your pantry.", vm.message.first { it != null })
        val after = vm.state.first { it?.removed?.size == 1 }!!
        assertEquals(listOf("Salt"), after.removed.map { it.name })
        assertEquals(listOf("Uncategorized"), after.onHand.map { it.aisle })
    }

    @Test
    fun aBadDateChangesNeitherTheDateNorTheAisle() = runTest {
        pantry.add("Salt", addedOn = "2026-09-01")
        val vm = newVm()
        val salt = vm.state.first { it?.onHand?.isNotEmpty() == true }!!.onHand.single().items.single()

        vm.save(salt, "Bulk", "01/09/2026")
        assertEquals(PantryDates.HINT, vm.error.value)
        val unchanged = pantry.observe().first().single()
        assertEquals(null, unchanged.aisle)
        assertEquals("2026-09-01", unchanged.addedOn)

        vm.save(salt, "Bulk", "")
        pantry.observe().first { it.single().aisle == "Bulk" && it.single().addedOn == null }
        assertEquals(null, vm.error.value)
    }

    @Test
    fun aStapleGoesOnTheShoppingListOnce() = runTest {
        pantry.add("Salt", "Spices & Baking")
        val vm = newVm()
        val salt = vm.state.first { it?.onHand?.isNotEmpty() == true }!!.onHand.single().items.single()

        vm.addToShoppingList(salt)
        assertEquals("Added 'Salt' to your shopping list.", vm.message.first { it != null })
        vm.messageShown(vm.message.value!!)
        vm.addToShoppingList(salt)
        assertEquals("'Salt' is already on your shopping list.", vm.message.first { it != null })
        assertEquals(listOf("Spices & Baking"), shopping.observe().first().map { it.aisle })
    }

    @Test
    fun consumingAnOlderMessageLeavesANewerOne() = runTest {
        val vm = newVm()
        vm.add("Salt", "")
        val first = vm.message.first { it != null }!!
        vm.add("Pepper", "")
        val second = vm.message.first { it != null && it != first }!!
        vm.messageShown(first)
        assertEquals(second, vm.message.value)
        vm.messageShown(second)
        assertEquals(null, vm.message.value)
    }
}
