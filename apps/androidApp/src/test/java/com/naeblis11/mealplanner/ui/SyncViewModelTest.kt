package com.naeblis11.mealplanner.ui

import androidx.lifecycle.viewModelScope
import com.naeblis11.mealplanner.MainDispatcherRule
import com.naeblis11.mealplanner.sync.FakeSyncControls
import com.naeblis11.mealplanner.sync.PossibleDuplicate
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** LAN sync stage 2c (D4, D5): the Sync button's view model. */
class SyncViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val created = mutableListOf<SyncViewModel>()
    private val soup = PossibleDuplicate("Soup", 1L, 2L)
    private val chili = PossibleDuplicate("Chili", 3L, 4L)

    @After
    fun tearDown() {
        created.forEach { it.viewModelScope.cancel() }
    }

    private fun viewModel(controls: FakeSyncControls) = SyncViewModel(controls).also { created += it }

    private fun SyncViewModel.await(predicate: (SyncBarState) -> Boolean): SyncBarState =
        runBlocking { withTimeout(5_000) { state.first(predicate) } }

    @Test
    fun theFirstTapAsksMergeOrReplace() {
        val controls = FakeSyncControls(firstChoice = true)
        val vm = viewModel(controls)
        vm.tap()
        vm.await { it.askFirst }
        assertTrue(controls.calls.isEmpty())
        vm.merge()
        vm.await { !it.askFirst }
        assertEquals(listOf("sync MERGE"), controls.calls)
    }

    @Test
    fun laterTapsSyncAtOnce() {
        val controls = FakeSyncControls(firstChoice = false)
        viewModel(controls).tap()
        assertEquals(listOf("sync -"), controls.calls)
    }

    @Test
    fun aTapWhileSyncingOrUnpairedDoesNothing() {
        val busy = FakeSyncControls(firstChoice = false).apply { syncing.value = true }
        viewModel(busy).tap()
        val unpaired = FakeSyncControls(pcName = null, firstChoice = false)
        viewModel(unpaired).tap()
        assertTrue(busy.calls.isEmpty())
        assertTrue(unpaired.calls.isEmpty())
    }

    @Test
    fun replaceWaitsForTheBackupFile() {
        val controls = FakeSyncControls(firstChoice = true)
        val vm = viewModel(controls)
        vm.tap()
        vm.await { it.askFirst }
        vm.replaceChosen()
        vm.await { !it.askFirst }
        // The picker is open: nothing is replaced until the user has chosen where the backup goes.
        assertTrue(controls.calls.isEmpty())
        vm.backupChosen({ ByteArrayOutputStream() }, {})
        assertEquals(listOf("replace"), controls.calls)
    }

    @Test
    fun cancelLeavesEverythingAsItWas() {
        val controls = FakeSyncControls(firstChoice = true)
        val vm = viewModel(controls)
        vm.tap()
        vm.await { it.askFirst }
        vm.cancelFirst()
        vm.await { !it.askFirst }
        assertTrue(controls.calls.isEmpty())
    }

    @Test
    fun theOutcomeIsShownOnce() {
        val controls = FakeSyncControls()
        val vm = viewModel(controls)
        controls.outcome.value = "Sent 1 change, got 2"
        vm.await { it.message == "Sent 1 change, got 2" }
        vm.messageShown("Sent 1 change, got 2")
        vm.await { it.message == null }
    }

    @Test
    fun duplicatesShowUntilSettledOrPutOff() {
        val controls = FakeSyncControls()
        val vm = viewModel(controls)
        controls.duplicates.value = listOf(soup, chili)
        vm.await { it.showDuplicates && it.duplicates == listOf(soup, chili) }
        vm.keepPcs(soup)
        vm.await { it.duplicates == listOf(chili) }
        vm.later()
        vm.await { !it.showDuplicates && it.duplicates == listOf(chili) }
        // A new Merge brings the list back.
        vm.merge()
        vm.await { it.showDuplicates }
        assertEquals(listOf("keepPcs Soup", "sync MERGE"), controls.calls)
    }
}
