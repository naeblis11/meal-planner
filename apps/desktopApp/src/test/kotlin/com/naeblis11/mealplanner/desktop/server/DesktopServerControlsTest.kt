package com.naeblis11.mealplanner.desktop.server

import androidx.lifecycle.viewModelScope
import com.naeblis11.mealplanner.settings.AlexaViewModel
import com.naeblis11.mealplanner.settings.COPY_FAILED
import com.naeblis11.mealplanner.settings.ServerState
import com.naeblis11.mealplanner.settings.ServerStatus
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** P4-R6 on the desktop: Create a token saves it, then the server moves onto the home network; Copy uses the clipboard. */
class DesktopServerControlsTest {
    private val dir: File = Files.createTempDirectory("mp-controls").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine = FakeEngine()
    private val token = ApiToken(SecretsFile(File(dir, ".env")))
    private val server = MealPlannerServer(token, ServerRoutes(), engine, log = {})

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun aNewTokenIsSavedAndTheServerMovesOntoTheHomeNetwork() {
        server.start(scope)
        val controls = DesktopServerControls(server, token, clipboard = {})
        val made = controls.createToken()
        // The messages never print the token.
        assertTrue("the token is not 64 hex digits", Regex("[0-9a-f]{64}").matches(made))
        assertTrue("the secrets file doesn't hold the new token", SecretsFile(File(dir, ".env")).read()[ApiToken.KEY] == made)
        assertEquals(listOf(MealPlannerServer.LOOPBACK, MealPlannerServer.ALL_INTERFACES), engine.startedHosts())
        assertEquals(ServerStatus(ServerState.LISTENING, MealPlannerServer.PORT, onLan = true, tokenConfigured = true), controls.status.value)
    }

    @Test
    fun aMadeTokenWaitsToBeShownOnce() {
        // Kept here, which outlives Settings, so a Settings closed during the create can't lose it.
        server.start(scope)
        val controls = DesktopServerControls(server, token, clipboard = {})
        val made = controls.createToken()
        assertTrue(controls.pendingReveal.waiting.value)
        assertTrue("the pending token is not the one made", controls.pendingReveal.take() == made)
        assertNull(controls.pendingReveal.take())
        assertFalse(controls.pendingReveal.waiting.value)
    }

    @Test
    fun copyGoesToTheClipboard() {
        val copied = mutableListOf<String>()
        DesktopServerControls(server, token, clipboard = { copied += it }).copy("meal_planner_auth: \"Bearer x\"")
        assertEquals(listOf("meal_planner_auth: \"Bearer x\""), copied)
    }

    @Test
    fun aClipboardThatRefusesIsSaidOnScreen() = runBlocking {
        server.start(scope)
        val controls = DesktopServerControls(server, token, clipboard = { throw IllegalStateException("cannot open system clipboard") })
        val vm = AlexaViewModel(controls)
        try {
            vm.createToken()
            withTimeout(5_000) { vm.state.first { it.newToken != null && !it.busy } }
            vm.copyToken()
            assertEquals(COPY_FAILED, withTimeout(5_000) { vm.state.first { it.error != null } }.error)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun theClipboardIsClearedOnlyWhileItHoldsTheLine() {
        var board: String? = "meal_planner_auth: \"Bearer x\""
        val controls = DesktopServerControls(server, token, clipboard = { board = it }, readClipboard = { board })
        controls.clearClipboard("meal_planner_auth: \"Bearer x\"")
        assertEquals("", board)
        board = "a shopping list"
        controls.clearClipboard("meal_planner_auth: \"Bearer x\"")
        assertEquals("a shopping list", board)
        // A clipboard that can't be read is left alone.
        DesktopServerControls(server, token, clipboard = { board = it }, readClipboard = { throw IllegalStateException("busy") })
            .clearClipboard("a shopping list")
        assertEquals("a shopping list", board)
    }

    @Test
    fun aTokenThatCantBeSavedLogsItsClassOnly() {
        val blocker = File(dir, "not-a-folder").apply { writeText("a file where the folder should be") }
        val unsaved = ApiToken(SecretsFile(File(blocker, ".env")), log = {})
        val logs = CopyOnWriteArrayList<String>()
        val controls = DesktopServerControls(MealPlannerServer(unsaved, ServerRoutes(), FakeEngine(), log = {}), unsaved, clipboard = {}, log = { logs += it })
        val thrown = assertThrows(IOException::class.java) { controls.createToken() }
        assertEquals(listOf("Meal Planner: couldn't save the Alexa token: ${thrown.javaClass.simpleName}"), logs.toList())
        assertFalse(controls.pendingReveal.waiting.value)
    }

    @Test
    fun theTrayHearsOfAPortInUseOnceARun() {
        val notice = PortNotice()
        assertFalse(notice.take(ServerStatus(ServerState.STARTING, 5000)))
        assertTrue(notice.take(ServerStatus(ServerState.PORT_IN_USE, 5000)))
        assertFalse(notice.take(ServerStatus(ServerState.PORT_IN_USE, 5000)))
    }
}
