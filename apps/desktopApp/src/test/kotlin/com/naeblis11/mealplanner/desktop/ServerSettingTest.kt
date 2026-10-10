package com.naeblis11.mealplanner.desktop

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.desktop.peers.PeerMessages
import com.naeblis11.mealplanner.settings.ALEXA_OFF
import com.naeblis11.mealplanner.settings.ALEXA_ON
import com.naeblis11.mealplanner.settings.AlexaState
import com.naeblis11.mealplanner.settings.BackupState
import com.naeblis11.mealplanner.settings.COPY_FAILED
import com.naeblis11.mealplanner.settings.CREATE_TOKEN
import com.naeblis11.mealplanner.settings.CalendarSetupState
import com.naeblis11.mealplanner.settings.NEW_TOKEN
import com.naeblis11.mealplanner.settings.NEW_TOKEN_WARNING
import com.naeblis11.mealplanner.settings.PeerNotice
import com.naeblis11.mealplanner.settings.SERVER_STARTING
import com.naeblis11.mealplanner.settings.ServerState
import com.naeblis11.mealplanner.settings.ServerStatus
import com.naeblis11.mealplanner.settings.SettingsScreen
import com.naeblis11.mealplanner.settings.portInUseNotice
import com.naeblis11.mealplanner.settings.secretsLine
import com.naeblis11.mealplanner.settings.serverListening
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** P4-R6: Settings' "Chrome extension and Alexa" panel. */
class ServerSettingTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(
        state: AlexaState,
        onCreate: () -> Unit = {},
        onCopy: () -> Unit = {},
        onDone: () -> Unit = {},
        peerNotice: PeerNotice? = null,
    ) = compose.showAt(600.dp) {
        SettingsScreen(
            backup = BackupState.Idle,
            calendar = CalendarSetupState(),
            version = "dev",
            onBack = {},
            onExport = {},
            onImport = {},
            onDismissBackup = {},
            onSetUpCalendar = {},
            onChooseCalendar = {},
            onCancelChoosing = {},
            onOpenAppSettings = {},
            server = state,
            onCreateToken = onCreate,
            onCopyToken = onCopy,
            onTokenDone = onDone,
            peerNotice = peerNotice,
        )
    }

    @Test
    fun onThisPcOnlyItOffersToCreateAToken() {
        var creates = 0
        show(AlexaState(ServerStatus(ServerState.LISTENING, 5000)), onCreate = { creates++ })
        compose.onNodeWithText(serverListening(5000, onLan = false)).assertExists()
        compose.onNodeWithText(ALEXA_OFF).assertExists()
        compose.onNodeWithText(CREATE_TOKEN).click()
        assertEquals(1, creates)
    }

    @Test
    fun withATokenItListensOnTheHomeNetworkAndOffersANewOne() {
        var creates = 0
        show(AlexaState(ServerStatus(ServerState.LISTENING, 5000, onLan = true, tokenConfigured = true)), onCreate = { creates++ })
        compose.onNodeWithText(serverListening(5000, onLan = true)).assertExists()
        compose.onNodeWithText(ALEXA_ON).assertExists()
        compose.onAllNodesWithText(CREATE_TOKEN).assertCountEquals(0)
        // A lost token is never a dead end; making a new one says what it costs.
        compose.onNodeWithText(NEW_TOKEN_WARNING).assertExists()
        compose.onNodeWithText(NEW_TOKEN).click()
        assertEquals(1, creates)
    }

    @Test
    fun aCopyThatFailedKeepsTheLineToSelect() {
        show(
            AlexaState(
                ServerStatus(ServerState.LISTENING, 5000, onLan = true, tokenConfigured = true),
                newToken = "abc123",
                error = COPY_FAILED,
            ),
        )
        compose.onNodeWithText(COPY_FAILED).assertExists()
        compose.onNodeWithText(secretsLine("abc123")).assertExists()
    }

    @Test
    fun aPortInUseIsShown() {
        show(AlexaState(ServerStatus(ServerState.PORT_IN_USE, 5000)))
        compose.onNodeWithText(portInUseNotice(5000)).assertExists()
    }

    @Test
    fun aNewTokenIsShownWithCopyAndDone() {
        var copies = 0
        var dones = 0
        show(
            AlexaState(ServerStatus(ServerState.LISTENING, 5000, onLan = true, tokenConfigured = true), newToken = "abc123"),
            onCopy = { copies++ },
            onDone = { dones++ },
        )
        compose.onNodeWithText(secretsLine("abc123")).assertExists()
        // With a token on screen there is nothing to replace yet.
        compose.onAllNodesWithText(NEW_TOKEN).assertCountEquals(0)
        compose.onNodeWithText("Copy").click()
        compose.onNodeWithText("Done").click()
        assertEquals(1, copies)
        assertEquals(1, dones)
    }

    @Test
    fun whileATokenIsBeingMadeItSaysStartingAndTheButtonWaits() {
        // The server moves onto the home network off the UI thread; meanwhile the panel says so, and a second click
        // can't start another.
        show(AlexaState(ServerStatus(ServerState.LISTENING, 5000), busy = true))
        compose.onNodeWithText(SERVER_STARTING).assertExists()
        compose.onAllNodesWithText(serverListening(5000, onLan = false)).assertCountEquals(0)
        compose.onNodeWithText(CREATE_TOKEN).assertIsNotEnabled()
    }

    @Test
    fun whileAnotherPcAnswersAlexaNoTokenIsMade() {
        val alexa = PeerMessages.yieldAlexa("DEN")
        show(AlexaState(ServerStatus(ServerState.LISTENING, 5000)), peerNotice = PeerNotice("Top notice", alexa))
        compose.onNodeWithText("Top notice").assertExists()
        compose.onNodeWithText(alexa).assertExists()
        compose.onAllNodesWithText(ALEXA_OFF).assertCountEquals(0)
        compose.onNodeWithText(CREATE_TOKEN).assertIsNotEnabled()
    }

    @Test
    fun aTokenSetUpIsKeptButNotReplacedWhileAnotherPcAnswers() {
        val alexa = PeerMessages.yieldAlexa("DEN")
        show(
            AlexaState(ServerStatus(ServerState.LISTENING, 5000, onLan = false, tokenConfigured = true)),
            peerNotice = PeerNotice("Top notice", alexa),
        )
        compose.onNodeWithText(serverListening(5000, onLan = false)).assertExists()
        compose.onNodeWithText(alexa).assertExists()
        compose.onAllNodesWithText(ALEXA_ON).assertCountEquals(0)
        compose.onAllNodesWithText(NEW_TOKEN_WARNING).assertCountEquals(0)
        compose.onNodeWithText(NEW_TOKEN).assertIsNotEnabled()
    }
}
