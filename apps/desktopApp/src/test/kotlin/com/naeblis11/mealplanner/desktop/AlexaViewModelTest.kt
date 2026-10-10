package com.naeblis11.mealplanner.desktop

import androidx.lifecycle.viewModelScope
import com.naeblis11.mealplanner.settings.AlexaViewModel
import com.naeblis11.mealplanner.settings.COPY_FAILED
import com.naeblis11.mealplanner.settings.PendingReveal
import com.naeblis11.mealplanner.settings.ServerState
import com.naeblis11.mealplanner.settings.ServerStatus
import com.naeblis11.mealplanner.settings.TOKEN_NOT_SAVED
import com.naeblis11.mealplanner.settings.secretsLine
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P4-R6: Create a token shows it once, with the line for Home Assistant's secrets.yaml and a Copy. */
class AlexaViewModelTest {
    private val created = mutableListOf<AlexaViewModel>()
    private val listening = ServerStatus(ServerState.LISTENING, 5000)

    @After
    fun tearDown() {
        created.forEach { it.viewModelScope.cancel() }
    }

    private fun viewModel(controls: FakeControls) = AlexaViewModel(controls).also { created += it }

    private suspend fun AlexaViewModel.shownToken(): String? = withTimeout(5_000) { state.first { it.newToken != null && !it.busy } }.newToken

    @Test
    fun aNewTokenIsShownOnceAndTheServerIsOnTheHomeNetwork() = runBlocking {
        val controls = FakeControls(listening)
        val vm = viewModel(controls)
        vm.createToken()
        val shown = withTimeout(5_000) { vm.state.first { it.newToken != null } }
        assertEquals("abc123", shown.newToken)
        assertTrue(shown.status.tokenConfigured && shown.status.onLan)
        vm.tokenDone()
        assertNull(withTimeout(5_000) { vm.state.first { it.newToken == null } }.newToken)
        assertEquals(1, controls.created)
        assertFalse(controls.pendingReveal.waiting.value)
    }

    @Test
    fun copyPutsTheSecretsLineOnTheClipboard() = runBlocking {
        val controls = FakeControls(listening)
        val vm = viewModel(controls)
        vm.createToken()
        withTimeout(5_000) { vm.state.first { it.newToken != null } }
        vm.copyToken()
        assertEquals(listOf(secretsLine("abc123")), controls.copied.toList())
        assertEquals("meal_planner_auth: \"Bearer abc123\"", secretsLine("abc123"))
        assertTrue(withTimeout(5_000) { vm.state.first { it.copied } }.copied)
    }

    @Test
    fun aCopyTheClipboardRefusesSaysToSelectTheLine() = runBlocking {
        val controls = FakeControls(listening).apply { copyFails = IllegalStateException("clipboard busy") }
        val vm = viewModel(controls)
        vm.createToken()
        vm.shownToken()
        vm.copyToken()
        val failed = withTimeout(5_000) { vm.state.first { it.error != null } }
        assertEquals(COPY_FAILED, failed.error)
        assertFalse(failed.copied)
        // The line stays on screen to be selected.
        assertEquals("abc123", failed.newToken)
    }

    @Test
    fun doneClearsACopyFailureWithTheLine() = runBlocking {
        // The banner is about the line on screen; once the line is gone it would only mislead.
        val controls = FakeControls(listening).apply { copyFails = IllegalStateException("clipboard busy") }
        val vm = viewModel(controls)
        vm.createToken()
        vm.shownToken()
        vm.copyToken()
        withTimeout(5_000) { vm.state.first { it.error != null } }
        vm.tokenDone()
        val done = withTimeout(5_000) { vm.state.first { it.newToken == null } }
        assertNull(done.error)
    }

    @Test
    fun doneClearsTheClipboardOnlyWhileItHoldsTheLine() = runBlocking {
        val controls = FakeControls(listening)
        val vm = viewModel(controls)
        vm.createToken()
        vm.shownToken()
        vm.copyToken()
        vm.tokenDone()
        assertEquals("", controls.clipboard)

        val other = FakeControls(listening)
        val again = viewModel(other)
        again.createToken()
        again.shownToken()
        again.copyToken()
        // Something else copied since: Done leaves it.
        other.clipboard = "a shopping list"
        again.tokenDone()
        assertEquals("a shopping list", other.clipboard)
    }

    @Test
    fun aTokenThatCantBeSavedSaysSo() = runBlocking {
        val controls = FakeControls(listening).apply { failWith = IOException("disk full") }
        val vm = viewModel(controls)
        vm.createToken()
        val failed = withTimeout(5_000) { vm.state.first { it.error != null } }
        assertEquals(TOKEN_NOT_SAVED, failed.error)
        assertNull(failed.newToken)
        assertFalse(failed.busy)
    }

    @Test
    fun aSecondCreateWhileOneIsUnderWayIsIgnored() = runBlocking {
        val controls = FakeControls(listening).apply { gate = CountDownLatch(1) }
        val vm = viewModel(controls)
        vm.createToken()
        assertTrue(controls.entered.await(5, TimeUnit.SECONDS))
        withTimeout(5_000) { vm.state.first { it.busy } }
        vm.createToken()
        controls.gate!!.countDown()
        assertEquals("abc123", vm.shownToken())
        assertEquals(1, controls.created)
    }

    @Test
    fun aTokenSetUpCanBeReplacedByANewOne() = runBlocking {
        // Make a new token: a lost token is never a dead end.
        val controls = FakeControls(listening.copy(onLan = true, tokenConfigured = true)).apply { created = 1 }
        val vm = viewModel(controls)
        vm.createToken()
        assertEquals("def456", vm.shownToken())
        assertEquals(2, controls.created)
    }

    @Test
    fun aCreateInterruptedByClosingSettingsIsShownOnceByTheNext() = runBlocking {
        val controls = FakeControls(listening).apply { gate = CountDownLatch(1) }
        val first = viewModel(controls)
        first.createToken()
        assertTrue(controls.entered.await(5, TimeUnit.SECONDS))
        first.viewModelScope.cancel()
        controls.gate!!.countDown()
        withTimeout(5_000) { controls.pendingReveal.waiting.first { it } }
        assertNull(first.state.value.newToken)

        val second = viewModel(controls)
        assertEquals("abc123", withTimeout(5_000) { second.state.first { it.newToken != null } }.newToken)
        second.tokenDone()
        assertFalse(controls.pendingReveal.waiting.value)
        val third = viewModel(controls)
        assertNull(third.state.value.newToken)
    }

    @Test
    fun leavingSettingsForgetsTheShownToken() = runBlocking {
        val controls = FakeControls(listening)
        val vm = viewModel(controls)
        vm.createToken()
        vm.shownToken()
        vm.left()
        assertNull(withTimeout(5_000) { vm.state.first { it.newToken == null } }.newToken)
        vm.shown()
        assertNull(vm.state.value.newToken)
        assertFalse(controls.pendingReveal.waiting.value)
    }

    @Test
    fun aCreateThatFinishesWhileSettingsIsAwayIsShownOnItsReturn() = runBlocking {
        val controls = FakeControls(listening).apply { gate = CountDownLatch(1) }
        val vm = viewModel(controls)
        vm.createToken()
        assertTrue(controls.entered.await(5, TimeUnit.SECONDS))
        withTimeout(5_000) { vm.state.first { it.busy } }
        vm.left()
        controls.gate!!.countDown()
        withTimeout(5_000) { vm.state.first { !it.busy } }
        assertTrue(controls.pendingReveal.waiting.value)
        assertNull(vm.state.value.newToken)
        vm.shown()
        assertEquals("abc123", vm.shownToken())
        assertFalse(controls.pendingReveal.waiting.value)
    }

    @Test
    fun theStateNeverPrintsTheToken() = runBlocking {
        // A state that reaches a log or a test failure says only whether a token is on screen.
        val controls = FakeControls(listening)
        val vm = viewModel(controls)
        vm.createToken()
        val shown = withTimeout(5_000) { vm.state.first { it.newToken != null } }
        assertFalse("the state's text shows the token", "abc123" in shown.toString())
        assertTrue("the state's text doesn't say a token is shown", "newToken=hidden" in shown.toString())
        val waiting = PendingReveal().apply { offer("abc123") }
        assertFalse("the pending reveal's text shows the token", "abc123" in waiting.toString())
    }
}
