package com.naeblis11.mealplanner.desktop

import androidx.lifecycle.viewModelScope
import com.naeblis11.mealplanner.desktop.server.eventually
import com.naeblis11.mealplanner.recipes.SyncAttentionViewModel
import com.naeblis11.mealplanner.recipes.SyncRemoveAsk
import com.naeblis11.mealplanner.recipes.syncRemoveQuestion
import com.naeblis11.mealplanner.sync.HeldRemoval
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** LAN sync stage 2b (D11): held sync removals in Needs attention. */
class SyncAttentionViewModelTest {
    private val created = mutableListOf<SyncAttentionViewModel>()

    @After
    fun tearDown() {
        created.forEach { it.viewModelScope.cancel() }
    }

    private fun viewModel(attention: FakeSyncAttention) = SyncAttentionViewModel(attention).also { created += it }

    @Test
    fun removeAsksWithTheCountsThenRemovesOnce() = runBlocking<Unit> {
        val attention = FakeSyncAttention(listOf("Soup", "Stew"), meals = 3)
        val vm = viewModel(attention)
        vm.askRemove()
        val shown = setOf("id-Soup", "id-Stew")
        assertEquals(SyncRemoveAsk(2, 3, shown), withTimeout(5_000) { vm.ask.first { it != null } })
        assertEquals(listOf(shown), attention.mealsAsked.toList())
        vm.confirmRemove()
        vm.confirmRemove()
        eventually { attention.confirms.get() == 1 && !vm.busy.value }
        assertEquals(listOf(shown), attention.confirmed.toList())
        assertEquals(null, vm.ask.value)
        assertEquals(emptyList<HeldRemoval>(), vm.held.value)
    }

    @Test
    fun aRemovalHeldAfterTheQuestionWasAskedStaysHeld() = runBlocking<Unit> {
        val attention = FakeSyncAttention(listOf("Soup", "Stew"))
        val vm = viewModel(attention)
        vm.askRemove()
        withTimeout(5_000) { vm.ask.first { it != null } }
        // Another exchange holds one more while the question is open: Remove takes only the two the user saw.
        attention.held.value = attention.held.value + HeldRemoval("id-Pie", "Pie")
        vm.confirmRemove()
        eventually { attention.confirms.get() == 1 && !vm.busy.value }
        assertEquals(listOf(setOf("id-Soup", "id-Stew")), attention.confirmed.toList())
        assertEquals(listOf(HeldRemoval("id-Pie", "Pie")), vm.held.value)
    }

    @Test
    fun removeWhileKeepIsStillWorkingLeavesTheQuestionOpen() = runBlocking<Unit> {
        val attention = FakeSyncAttention(listOf("Soup"))
        val gate = CompletableDeferred<Unit>()
        attention.gate = gate
        val vm = viewModel(attention)
        vm.askRemove()
        val ask = withTimeout(5_000) { vm.ask.first { it != null } }
        vm.keep()
        eventually { vm.busy.value }
        // Busy: the tap does nothing, and the question stays for when it isn't.
        vm.confirmRemove()
        assertEquals(ask, vm.ask.value)
        assertEquals(0, attention.confirms.get())
        gate.complete(Unit)
        eventually { attention.keeps.get() == 1 && !vm.busy.value }
        assertEquals(ask, vm.ask.value)
    }

    @Test
    fun withoutAMealCountTheQuestionCountsRecipesOnly() = runBlocking<Unit> {
        val attention = FakeSyncAttention(listOf("Soup")).apply { failMeals = IOException("x") }
        val vm = viewModel(attention)
        vm.askRemove()
        val ask = withTimeout(5_000) { vm.ask.first { it != null } }!!
        assertEquals(SyncRemoveAsk(1, null, setOf("id-Soup")), ask)
        assertEquals("Remove 1 recipe from this PC? Its file goes to the Recycle Bin.", syncRemoveQuestion(ask.recipes, ask.plannedMeals))
    }

    @Test
    fun cancelRemovesNothing() = runBlocking<Unit> {
        val attention = FakeSyncAttention(listOf("Soup"))
        val vm = viewModel(attention)
        vm.askRemove()
        withTimeout(5_000) { vm.ask.first { it != null } }
        vm.cancelRemove()
        assertEquals(null, vm.ask.value)
        assertEquals(0, attention.confirms.get())
    }

    @Test
    fun keepKeepsWhatIsShown() {
        val attention = FakeSyncAttention(listOf("Soup", "Stew"))
        val vm = viewModel(attention)
        vm.keep()
        eventually { attention.keeps.get() == 1 && !vm.busy.value }
        assertEquals(listOf(setOf("id-Soup", "id-Stew")), attention.kept.toList())
        assertEquals(0, attention.confirms.get())
    }

    @Test
    fun theQuestionCountsPlannedMeals() {
        assertEquals(
            "Remove 2 recipes from this PC? 3 planned meals use them and will be removed too. Their files go to the Recycle Bin.",
            syncRemoveQuestion(2, 3),
        )
        assertEquals(
            "Remove 1 recipe from this PC? 1 planned meal uses it and will be removed too. Its file goes to the Recycle Bin.",
            syncRemoveQuestion(1, 1),
        )
    }
}
