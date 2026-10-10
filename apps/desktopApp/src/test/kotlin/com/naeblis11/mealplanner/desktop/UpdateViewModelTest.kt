package com.naeblis11.mealplanner.desktop

import androidx.lifecycle.viewModelScope
import com.naeblis11.mealplanner.settings.UpdateViewModel
import com.naeblis11.mealplanner.update.UpdateControls
import com.naeblis11.mealplanner.update.UpdateOffer
import com.naeblis11.mealplanner.update.UpdatePhase
import com.naeblis11.mealplanner.update.UpdateStatus
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UpdateViewModelTest {
    private val created = mutableListOf<UpdateViewModel>()
    private val offer = UpdateOffer("1.0.1", "release-2", "MealPlanner-1.0.1.msi", 1_000, "a".repeat(64))

    // Unconfined, so the view model reacts to each status change before the test's next line: nothing to wait for.
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        created.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    private fun viewModel(controls: UpdateControls) = UpdateViewModel(controls, ZoneOffset.UTC).also { created += it }

    @Test
    fun onThePcInstallAsksBeforeClosing() = runBlocking {
        val controls = FakeUpdateControls(UpdateStatus(offered = true, offer = offer, installClosesApp = true))
        val vm = viewModel(controls)
        vm.install()
        withTimeout(5_000) { vm.state.first { it.confirming } }
        assertEquals(emptyList<String>(), controls.calls)
        vm.cancelInstall()
        withTimeout(5_000) { vm.state.first { !it.confirming } }
        vm.install()
        vm.confirmInstall()
        assertEquals(listOf("install"), controls.calls)
        withTimeout(5_000) { vm.state.first { !it.confirming } }
        Unit
    }

    @Test
    fun onAPhoneInstallGoesStraightToAndroid() = runBlocking {
        val controls = FakeUpdateControls(UpdateStatus(offered = true, offer = offer, installClosesApp = false))
        val vm = viewModel(controls)
        vm.install()
        assertEquals(listOf("install"), controls.calls)
        assertFalse(vm.state.value.confirming)
    }

    @Test
    fun lastCheckedIsShownInLocalTime() = runBlocking {
        val nineFortyOneOnTheSecond = 86_400_000L + 9 * 3_600_000L + 41 * 60_000L
        val vm = viewModel(FakeUpdateControls(UpdateStatus(offered = true, lastChecked = nineFortyOneOnTheSecond)))
        assertEquals("2 Jan 1970 at 09:41", withTimeout(5_000) { vm.state.first { it.lastCheckedText != null } }.lastCheckedText)
        assertEquals("1 Jan 1970 at 00:00", UpdateViewModel.lastCheckedText(0, ZoneOffset.UTC))
    }

    @Test
    fun aConfirmAfterTheOfferWentBusyInstallsNothing() = runBlocking {
        val controls = FakeUpdateControls(UpdateStatus(offered = true, offer = offer, installClosesApp = true))
        val vm = viewModel(controls)
        vm.install()
        withTimeout(5_000) { vm.state.first { it.confirming } }
        controls.flow.value = controls.flow.value.copy(phase = UpdatePhase.CHECKING)
        vm.confirmInstall()
        assertEquals(emptyList<String>(), controls.calls)
    }

    @Test
    fun aFinishedCheckDoesNotBringBackAStaleQuestion() = runBlocking {
        val controls = FakeUpdateControls(UpdateStatus(offered = true, offer = offer, installClosesApp = true))
        val vm = viewModel(controls)
        vm.install()
        withTimeout(5_000) { vm.state.first { it.confirming } }
        controls.flow.value = controls.flow.value.copy(phase = UpdatePhase.CHECKING)
        withTimeout(5_000) { vm.state.first { !it.confirming } }
        controls.flow.value = controls.flow.value.copy(phase = UpdatePhase.IDLE)
        assertFalse(vm.state.value.confirming)
    }
}
