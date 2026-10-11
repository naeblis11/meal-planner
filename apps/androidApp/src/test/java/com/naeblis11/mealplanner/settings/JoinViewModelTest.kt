package com.naeblis11.mealplanner.settings

import androidx.lifecycle.viewModelScope
import com.naeblis11.mealplanner.MainDispatcherRule
import com.naeblis11.mealplanner.sync.FakeSyncControls
import com.naeblis11.mealplanner.sync.FoundPc
import com.naeblis11.mealplanner.sync.SyncMessages
import com.naeblis11.mealplanner.sync.WrongCodeException
import com.naeblis11.mealplanner.sync.WrongPcException
import java.io.IOException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** LAN sync stage 2c (D3): Settings' Join a PC. */
class JoinViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val created = mutableListOf<JoinViewModel>()
    private val kitchen = FoundPc("KITCHEN", "192.0.2.20", 5001)
    private val office = FoundPc("OFFICE", "192.0.2.30", 5001)

    @After
    fun tearDown() {
        created.forEach { it.viewModelScope.cancel() }
    }

    private fun viewModel(controls: FakeSyncControls) = JoinViewModel(controls).also { created += it }

    private fun JoinViewModel.await(predicate: (JoinState) -> Boolean): JoinState =
        runBlocking { withTimeout(5_000) { state.first(predicate) } }

    @Test
    fun aScannedCodePairs() {
        val controls = FakeSyncControls(pcName = null)
        val vm = viewModel(controls)
        vm.scanned("mealplanner://pair?v=1")
        vm.await { it.status.paired && it.busy == null && it.error == null }
        assertEquals(listOf("scanned mealplanner://pair?v=1"), controls.calls)
    }

    @Test
    fun aWrongCodeIsSaidPlainly() {
        val controls = FakeSyncControls(pcName = null).apply { pairFailure = WrongCodeException() }
        val vm = viewModel(controls)
        vm.scanned("x")
        vm.await { it.error == SyncMessages.WRONG_CODE && it.busy == null }
    }

    @Test
    fun aWrongPcIsNamedByHowThePhoneTriedToJoin() {
        val controls = FakeSyncControls(pcName = null).apply {
            pairFailure = WrongPcException()
            found = listOf(kitchen)
        }
        val vm = viewModel(controls)
        vm.scanned("x")
        vm.await { it.error == SyncMessages.WRONG_PC_SCANNED }
        vm.openTyping()
        vm.submitCode("123456")
        vm.await { it.error == SyncMessages.WRONG_PC_TYPED && it.typing }
    }

    @Test
    fun aTypedCodeWithOnePcPairsAtOnce() {
        val controls = FakeSyncControls(pcName = null).apply { found = listOf(kitchen) }
        val vm = viewModel(controls)
        vm.openTyping()
        vm.submitCode("123456")
        vm.await { !it.typing && it.status.paired && it.busy == null }
        assertEquals(listOf("find", "typed 123456 192.0.2.20"), controls.calls)
    }

    @Test
    fun severalPcsAreOfferedByName() {
        val controls = FakeSyncControls(pcName = null).apply { found = listOf(kitchen, office) }
        val vm = viewModel(controls)
        vm.openTyping()
        vm.submitCode("123456")
        vm.await { it.choices == listOf(kitchen, office) && it.busy == null }
        vm.choose(office)
        vm.await { !it.typing && it.status.pcName == "OFFICE" }
        assertEquals("typed 123456 192.0.2.30", controls.calls.last())
    }

    @Test
    fun noPcFoundSaysSoAndKeepsTheDialog() {
        val controls = FakeSyncControls(pcName = null)
        val vm = viewModel(controls)
        vm.openTyping()
        vm.submitCode("123456")
        vm.await { it.error == SyncMessages.NO_PC_FOUND && it.typing && it.busy == null }
    }

    @Test
    fun aCodeThatIsntSixDigitsIsIgnored() {
        val controls = FakeSyncControls(pcName = null)
        val vm = viewModel(controls)
        vm.openTyping()
        vm.submitCode("12345")
        vm.submitCode("12a456")
        assertTrue(controls.calls.isEmpty())
    }

    @Test
    fun leaveAsksFirst() {
        val controls = FakeSyncControls(pcName = "KITCHEN")
        val vm = viewModel(controls)
        vm.askLeave()
        vm.await { it.leaveAsk }
        vm.cancelLeave()
        vm.await { !it.leaveAsk }
        assertTrue(controls.calls.isEmpty())
        vm.askLeave()
        vm.confirmLeave()
        vm.await { !it.status.paired && it.busy == null && !it.leaveAsk }
        assertEquals(listOf("leave"), controls.calls)
    }

    @Test
    fun aFailedLeaveSaysSo() {
        val controls = FakeSyncControls(pcName = "KITCHEN").apply { leaveFailure = IOException("x") }
        val vm = viewModel(controls)
        vm.askLeave()
        vm.confirmLeave()
        vm.await { it.error == SyncMessages.LEAVE_FAILED && it.busy == null }
    }

    @Test
    fun scanningUnavailableAndTheWifiAreSaid() {
        val controls = FakeSyncControls(pcName = null)
        val vm = viewModel(controls)
        assertFalse(vm.state.value.scanUnavailable)
        vm.scanUnavailable()
        vm.await { it.scanUnavailable }
        vm.recheckWifi()
        assertEquals(listOf("wifi"), controls.calls)
        controls.onWifi.value = false
        vm.await { !it.onWifi }
    }

    @Test
    fun theTypedCodeIsForgottenOnceTheDialogIsCancelled() {
        val controls = FakeSyncControls(pcName = null).apply { found = listOf(kitchen, office) }
        val vm = viewModel(controls)
        vm.openTyping()
        vm.submitCode("123456")
        vm.await { it.choices.isNotEmpty() && it.busy == null }
        vm.cancelTyping()
        vm.await { !it.typing }
        vm.choose(office)
        assertEquals(listOf("find"), controls.calls)
        assertEquals(null, vm.state.value.busy)
    }

    @Test
    fun theTypedCodeIsForgottenWhenNoPcIsFound() {
        val controls = FakeSyncControls(pcName = null)
        val vm = viewModel(controls)
        vm.openTyping()
        vm.submitCode("123456")
        vm.await { it.error == SyncMessages.NO_PC_FOUND && it.busy == null }
        vm.choose(kitchen)
        assertEquals(listOf("find"), controls.calls)
    }

    @Test
    fun theTypedCodeIsForgottenAfterAFailedPairing() {
        val controls = FakeSyncControls(pcName = null).apply {
            found = listOf(kitchen, office)
            pairFailure = WrongCodeException()
        }
        val vm = viewModel(controls)
        vm.openTyping()
        vm.submitCode("123456")
        vm.await { it.choices.isNotEmpty() && it.busy == null }
        vm.choose(kitchen)
        vm.await { it.error == SyncMessages.WRONG_CODE && it.busy == null }
        controls.pairFailure = null
        vm.choose(office)
        assertEquals(listOf("find"), controls.calls)
        assertEquals(null, vm.state.value.busy)
    }

    @Test
    fun nothingPairsOrLeavesWhileASyncRuns() {
        // Task 5's note: pairing and Leave are not offered while a sync (or a Leave) holds `syncing`.
        val controls = FakeSyncControls(pcName = null).apply { found = listOf(kitchen) }
        val vm = viewModel(controls)
        controls.syncing.value = true
        vm.await { it.syncing }
        vm.scanned("mealplanner://pair?v=1")
        vm.openTyping()
        vm.submitCode("123456")
        vm.choose(kitchen)
        controls.status.value = controls.status.value.copy(pcName = "KITCHEN")
        vm.askLeave()
        vm.confirmLeave()
        assertTrue(controls.calls.isEmpty())
        assertEquals(null, vm.state.value.busy)
        controls.syncing.value = false
        vm.await { !it.syncing }
    }
}
