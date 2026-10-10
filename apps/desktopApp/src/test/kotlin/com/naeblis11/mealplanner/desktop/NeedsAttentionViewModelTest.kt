package com.naeblis11.mealplanner.desktop

import androidx.lifecycle.viewModelScope
import com.naeblis11.mealplanner.folder.HeldRecipes
import com.naeblis11.mealplanner.folder.RecipeFileProblem
import com.naeblis11.mealplanner.folder.RecipeFolderStatus
import com.naeblis11.mealplanner.folder.pointBackInstructions
import com.naeblis11.mealplanner.recipes.NeedsAttentionViewModel
import com.naeblis11.mealplanner.recipes.RemoveMissingAsk
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NeedsAttentionViewModelTest {
    /** One listed duplicate; [assignNewId] counts its calls, waits for [gate], then throws [failure] if set. */
    private class FakeFolder(val gate: CompletableDeferred<Unit> = CompletableDeferred(Unit), val failure: Exception? = null) : RecipeFolderStatus {
        val calls = AtomicInteger()
        override val problems = MutableStateFlow(listOf(RecipeFileProblem("b.yaml", RecipeFileProblem.Kind.DUPLICATE_ID, "Duplicate recipe_uuid dupe-1 (also used by a.yaml).")))
        override suspend fun assignNewId(fileName: String) {
            calls.incrementAndGet()
            gate.await()
            failure?.let { throw it }
        }

        val removals = AtomicInteger()
        val removed = mutableListOf<Set<Long>>()

        // What the next heldSummary() reports; a test changes it to stand for a file going missing later.
        @Volatile
        var held = HeldRecipes(setOf(1L, 2L), 3)

        override suspend fun confirmMassRemoval(ids: Set<Long>) {
            removals.incrementAndGet()
            synchronized(removed) { removed += ids }
            gate.await()
            failure?.let { throw it }
        }

        override suspend fun heldSummary() = held

        override fun openFolder() = true
    }

    private val created = mutableListOf<NeedsAttentionViewModel>()

    @After
    fun tearDown() {
        created.forEach { it.viewModelScope.cancel() }
    }

    private fun viewModel(folder: RecipeFolderStatus) = NeedsAttentionViewModel(folder).also { created += it }

    @Test
    fun aFileThatCantBeWrittenGivesAMessageUntilShown() = runBlocking {
        val vm = viewModel(FakeFolder(failure = IOException("Access is denied")))
        vm.assignNewId("b.yaml")
        assertEquals("Couldn't change b.yaml: Access is denied.", withTimeout(5_000) { vm.message.first { it != null } })
        withTimeout(5_000) { vm.busy.first { it.isEmpty() } }
        vm.messageShown()
        assertNull(vm.message.value)
    }

    @Test
    fun aDoubleTapAssignsOneNewId() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val folder = FakeFolder(gate = gate)
        val vm = viewModel(folder)
        vm.assignNewId("b.yaml")
        vm.assignNewId("b.yaml") // the second tap, while the first is still working
        assertEquals(setOf("b.yaml"), vm.busy.value)
        gate.complete(Unit)
        withTimeout(5_000) { vm.busy.first { it.isEmpty() } }
        assertEquals(1, folder.calls.get())
        assertNull(vm.message.value)
    }

    @Test
    fun keepRemovesNothingAndRemoveConfirmsOnce() = runBlocking {
        val folder = FakeFolder()
        val vm = viewModel(folder)
        // Asked with the numbers as they are when the dialog opens.
        vm.askRemoveMissing()
        val ask = withTimeout(5_000) { vm.removeAsk.first { it != null } }!!
        assertEquals(RemoveMissingAsk(ids = setOf(1L, 2L), plannedMeals = 3), ask)
        assertEquals(2, ask.recipes)
        vm.keepMissing()
        assertNull(vm.removeAsk.value)
        assertEquals(0, folder.removals.get())

        vm.askRemoveMissing()
        withTimeout(5_000) { vm.removeAsk.first { it != null } }
        // Another file goes missing (and is held) while the dialog is open: Remove is still about the 2 it counted.
        folder.held = HeldRecipes(setOf(1L, 2L, 7L), 4)
        vm.removeMissing()
        assertNull(vm.removeAsk.value)
        withTimeout(5_000) { vm.removing.first { !it } }
        assertEquals(1, folder.removals.get())
        assertEquals(listOf(setOf(1L, 2L)), folder.removed)
    }

    @Test
    fun removeWithNoConfirmationOpenRemovesNothing() = runBlocking {
        val folder = FakeFolder()
        val vm = viewModel(folder)
        vm.removeMissing()
        withTimeout(5_000) { vm.removing.first { !it } }
        assertEquals(0, folder.removals.get())
    }

    @Test
    fun aDoubleTapOnRemoveThemRemovesOnce() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val folder = FakeFolder(gate = gate)
        val vm = viewModel(folder)
        vm.askRemoveMissing()
        withTimeout(5_000) { vm.removeAsk.first { it != null } }
        vm.removeMissing()
        vm.removeMissing() // the second tap, while the first is still working
        assertEquals(true, vm.removing.value)
        gate.complete(Unit)
        withTimeout(5_000) { vm.removing.first { !it } }
        assertEquals(1, folder.removals.get())
        assertNull(vm.message.value)
    }

    @Test
    fun aMovedFolderIsConfirmedOnceOrPointedBack() = runBlocking {
        // P7-R10c: "Use the new folder" asks the folder once, however often it is tapped; "Point back" only says how.
        val gate = CompletableDeferred<Unit>()
        val uses = AtomicInteger()
        val moved = RecipeFileProblem("recipes", RecipeFileProblem.Kind.FOLDER, "moved", 2, movedFrom = "C:\\Old\\recipes")
        val folder = object : RecipeFolderStatus {
            override val problems = MutableStateFlow(listOf(moved))
            override suspend fun assignNewId(fileName: String) = Unit
            override suspend fun confirmMassRemoval(ids: Set<Long>) = Unit
            override suspend fun heldSummary() = HeldRecipes(emptySet(), 0)
            override fun openFolder() = true
            override suspend fun useNewFolder() {
                uses.incrementAndGet()
                gate.await()
            }
        }
        val vm = viewModel(folder)
        vm.useNewFolder()
        vm.useNewFolder()
        assertEquals(true, vm.usingNewFolder.value)
        gate.complete(Unit)
        withTimeout(5_000) { vm.usingNewFolder.first { !it } }
        assertEquals(1, uses.get())
        vm.pointBack(moved)
        assertEquals(pointBackInstructions("C:\\Old\\recipes"), vm.message.value)
        assertEquals(1, uses.get())
    }
}
