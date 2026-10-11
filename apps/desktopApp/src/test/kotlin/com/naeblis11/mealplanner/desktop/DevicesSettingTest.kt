package com.naeblis11.mealplanner.desktop

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.desktop.sync.SyncCertificate
import com.naeblis11.mealplanner.settings.BackupState
import com.naeblis11.mealplanner.settings.CalendarSetupState
import com.naeblis11.mealplanner.settings.DeviceActions
import com.naeblis11.mealplanner.settings.DeviceRow
import com.naeblis11.mealplanner.settings.DevicesState
import com.naeblis11.mealplanner.settings.NO_DEVICES_YET
import com.naeblis11.mealplanner.settings.NO_QR
import com.naeblis11.mealplanner.settings.PAIR_DEVICE
import com.naeblis11.mealplanner.settings.PAIR_ON_PHONE
import com.naeblis11.mealplanner.settings.PairingView
import com.naeblis11.mealplanner.settings.PeerNotice
import com.naeblis11.mealplanner.settings.QR_DESCRIPTION
import com.naeblis11.mealplanner.settings.QrModules
import com.naeblis11.mealplanner.settings.REMOVE_DEVICE
import com.naeblis11.mealplanner.settings.SettingsScreen
import com.naeblis11.mealplanner.settings.SyncServerState
import com.naeblis11.mealplanner.settings.SyncServerStatus
import com.naeblis11.mealplanner.settings.closesIn
import com.naeblis11.mealplanner.settings.deviceDetail
import com.naeblis11.mealplanner.settings.lastSyncedText
import com.naeblis11.mealplanner.settings.removeDeviceQuestion
import com.naeblis11.mealplanner.settings.syncPortInUseNotice
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** LAN sync stage 2b (D10): Settings' "Phones and PCs" section. */
class DevicesSettingTest {
    @get:Rule
    val compose = createComposeRule()

    private val pixel = DeviceRow("0123456789abcdef0123456789abcdef", "Pixel", "phone", null)
    private val off = SyncServerStatus(SyncServerState.OFF, 5001)
    private val listening = SyncServerStatus(SyncServerState.LISTENING, 5001)

    private fun show(state: DevicesState, actions: DeviceActions = DeviceActions(), peerNotice: PeerNotice? = null) = compose.showAt(600.dp) {
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
            peerNotice = peerNotice,
            devices = state,
            deviceActions = actions,
        )
    }

    @Test
    fun pairADeviceAsksTheControls() {
        var pairs = 0
        show(DevicesState(status = off), DeviceActions(pair = { pairs++ }))
        compose.onNodeWithText(NO_DEVICES_YET).assertExists()
        compose.onNodeWithText(PAIR_DEVICE).click()
        assertEquals(1, pairs)
    }

    @Test
    fun whileYieldingPairingIsOffAndSaysWhy() {
        val notice = PeerNotice("Another PC on this network (OFFICE) runs Meal Planner.", alexa = "Alexa is answered there.")
        show(DevicesState(status = off), peerNotice = notice)
        compose.onNodeWithText(PAIR_DEVICE).assertIsNotEnabled()
        // Atop Settings, and again in the section.
        compose.onAllNodesWithText(notice.text).assertCountEquals(2)
    }

    @Test
    fun anOpenWindowShowsTheQrTheCodeAndTheCountdown() {
        var cancels = 0
        val view = PairingView("012345", 1_000_000L + 252_000L, listOf("192.0.2.20"), "mealplanner://pair?v=1", QrModules(21, BooleanArray(21 * 21) { it % 2 == 0 }))
        show(DevicesState(offer = view, status = listening, now = 1_000_000L), DeviceActions(cancelPairing = { cancels++ }))
        compose.onNodeWithText("012 345").assertExists()
        compose.onNodeWithText(PAIR_ON_PHONE).assertExists()
        compose.onNodeWithText("Closes in 4:12").assertExists()
        compose.onNodeWithContentDescription(QR_DESCRIPTION).assertExists()
        compose.onAllNodesWithText(PAIR_DEVICE).assertCountEquals(0)
        compose.onNodeWithText("Cancel").click()
        assertEquals(1, cancels)
    }

    @Test
    fun withNoHomeNetworkTheCodeIsStillThere() {
        show(DevicesState(offer = PairingView("012345", 2_000L, emptyList(), null, null), status = listening, now = 1_000L))
        compose.onNodeWithText(NO_QR).assertExists()
        compose.onNodeWithText("012 345").assertExists()
    }

    @Test
    fun aPairedDeviceIsListedAndRemoveAsksFirst() {
        var asked: DeviceRow? = null
        show(DevicesState(devices = listOf(pixel), status = listening), DeviceActions(askRemove = { asked = it }))
        compose.onNodeWithText("Pixel").assertExists()
        compose.onNodeWithText(deviceDetail(pixel)).assertExists()
        compose.onNodeWithText("Remove").click()
        assertEquals(pixel, asked)
    }

    @Test
    fun theRemoveQuestionNamesTheDevice() {
        var confirms = 0
        show(DevicesState(devices = listOf(pixel), status = listening, removeAsk = pixel), DeviceActions(confirmRemove = { confirms++ }))
        compose.onNodeWithText(removeDeviceQuestion("Pixel")).assertExists()
        compose.onNodeWithText(REMOVE_DEVICE).click()
        assertEquals(1, confirms)
    }

    @Test
    fun aPortInUseAndARemadeCertificateAreSaid() {
        show(DevicesState(status = SyncServerStatus(SyncServerState.PORT_IN_USE, 5001), certificateNotice = SyncCertificate.REMADE_NOTICE))
        compose.onNodeWithText(syncPortInUseNotice(5001)).assertExists()
        compose.onNodeWithText(SyncCertificate.REMADE_NOTICE).assertExists()
    }

    @Test
    fun theTextsReadAsTheBriefSays() {
        assertEquals("Port 5001 is in use by another program, so devices can't sync. Retrying every minute.", syncPortInUseNotice(5001))
        assertEquals("Remove Pixel? It keeps its data but stops syncing until you pair it again.", removeDeviceQuestion("Pixel"))
        assertEquals("Closes in 0:00", closesIn(-5))
        assertEquals("Closes in 5:00", closesIn(300_000))
        assertEquals("Last synced 12 Oct 2026, 18:04", lastSyncedText(Instant.parse("2026-10-12T18:04:00Z").toEpochMilli(), ZoneOffset.UTC))
        assertEquals("Phone \u00B7 Never synced", deviceDetail(pixel))
        assertEquals("PC \u00B7 Never synced", deviceDetail(pixel.copy(kind = "pc")))
    }
}
