package com.naeblis11.mealplanner.desktop

import androidx.lifecycle.viewModelScope
import com.naeblis11.mealplanner.desktop.server.eventually
import com.naeblis11.mealplanner.settings.DeviceRow
import com.naeblis11.mealplanner.settings.PAIRING_FAILED
import com.naeblis11.mealplanner.settings.PairingViewModel
import com.naeblis11.mealplanner.settings.REMOVE_FAILED
import com.naeblis11.mealplanner.settings.SyncServerState
import com.naeblis11.mealplanner.settings.SyncServerStatus
import java.io.IOException
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** LAN sync stage 2b (D10): Settings' "Phones and PCs" view model. */
class PairingViewModelTest {
    private val created = mutableListOf<PairingViewModel>()
    private val pixel = DeviceRow("0123456789abcdef0123456789abcdef", "Pixel", "phone", null)

    @After
    fun tearDown() {
        created.forEach { it.viewModelScope.cancel() }
    }

    private fun viewModel(controls: FakePairingControls) = PairingViewModel(controls).also { created += it }

    @Test
    fun pairOpensTheWindowOnceEvenTappedTwice() = runBlocking<Unit> {
        val controls = FakePairingControls().apply { gate = CountDownLatch(1) }
        val vm = viewModel(controls)
        vm.pair()
        vm.pair()
        controls.gate!!.countDown()
        val state = withTimeout(5_000) { vm.state.first { it.offer != null && !it.opening } }
        assertEquals("123456", state.offer!!.code)
        assertEquals(1, controls.opens.get())
    }

    @Test
    fun aFailedOpenSaysSo() = runBlocking<Unit> {
        val controls = FakePairingControls().apply { failOpen = IOException("x") }
        val vm = viewModel(controls)
        vm.pair()
        withTimeout(5_000) { vm.state.first { it.error == PAIRING_FAILED && !it.opening } }
    }

    @Test
    fun leavingSettingsClosesAnOpenWindowOnly() {
        val controls = FakePairingControls()
        val vm = viewModel(controls)
        vm.left()
        assertEquals(0, controls.closes.get())
        controls.offer.value = controls.next
        vm.left()
        assertEquals(1, controls.closes.get())
    }

    @Test
    fun aWindowStillOpeningWhenSettingsIsLeftClosesOnceOpen() = runBlocking<Unit> {
        val controls = FakePairingControls().apply { gate = CountDownLatch(1) }
        val vm = viewModel(controls)
        vm.shown()
        vm.pair()
        vm.left()
        assertEquals(0, controls.closes.get())
        controls.gate!!.countDown()
        // The effect, not the state: `opening` reaches the state asynchronously, so it may still read false here.
        eventually { controls.closes.get() == 1 && controls.offer.value == null }
        assertEquals(1, controls.opens.get())
    }

    @Test
    fun removeAsksFirst() = runBlocking<Unit> {
        val controls = FakePairingControls().apply { devices.value = listOf(pixel) }
        val vm = viewModel(controls)
        vm.askRemove(pixel)
        withTimeout(5_000) { vm.state.first { it.removeAsk == pixel } }
        vm.cancelRemove()
        withTimeout(5_000) { vm.state.first { it.removeAsk == null } }
        assertTrue(controls.removed.isEmpty())
        vm.askRemove(pixel)
        vm.confirmRemove()
        eventually { controls.removed.toList() == listOf(pixel.deviceId) }
        withTimeout(5_000) { vm.state.first { it.removeAsk == null && it.devices.isEmpty() } }
    }

    @Test
    fun aFailedRemoveSaysSo() = runBlocking<Unit> {
        val controls = FakePairingControls().apply {
            devices.value = listOf(pixel)
            failRemove = IOException("x")
        }
        val vm = viewModel(controls)
        vm.askRemove(pixel)
        vm.confirmRemove()
        withTimeout(5_000) { vm.state.first { it.error == REMOVE_FAILED } }
    }

    @Test
    fun theStateFollowsTheControls() = runBlocking<Unit> {
        val controls = FakePairingControls()
        val vm = viewModel(controls)
        controls.devices.value = listOf(pixel)
        controls.status.value = SyncServerStatus(SyncServerState.PORT_IN_USE, 5001)
        controls.certificateNotice.value = "remade"
        withTimeout(5_000) {
            vm.state.first { it.devices == listOf(pixel) && it.status.state == SyncServerState.PORT_IN_USE && it.certificateNotice == "remade" }
        }
    }
}
