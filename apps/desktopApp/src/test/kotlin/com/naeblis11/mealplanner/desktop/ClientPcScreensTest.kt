package com.naeblis11.mealplanner.desktop

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.desktop.sync.DesktopJoinOffer
import com.naeblis11.mealplanner.settings.AlexaState
import com.naeblis11.mealplanner.settings.BackupState
import com.naeblis11.mealplanner.settings.CREATE_TOKEN
import com.naeblis11.mealplanner.settings.CalendarSetupState
import com.naeblis11.mealplanner.settings.DevicesState
import com.naeblis11.mealplanner.settings.JOIN_INTRO_PC
import com.naeblis11.mealplanner.settings.JOIN_OFFER_DISMISSED_KEY
import com.naeblis11.mealplanner.settings.JOIN_OFFER_JOIN
import com.naeblis11.mealplanner.settings.JOIN_PANEL
import com.naeblis11.mealplanner.settings.JOIN_PANEL_PC
import com.naeblis11.mealplanner.settings.JoinActions
import com.naeblis11.mealplanner.settings.JoinOfferBanner
import com.naeblis11.mealplanner.settings.JoinState
import com.naeblis11.mealplanner.settings.KEEP_SEPARATE
import com.naeblis11.mealplanner.settings.LEAVE
import com.naeblis11.mealplanner.settings.PAIR_DEVICE
import com.naeblis11.mealplanner.settings.SCAN_CODE
import com.naeblis11.mealplanner.settings.SettingsScreen
import com.naeblis11.mealplanner.settings.ServerState
import com.naeblis11.mealplanner.settings.ServerStatus
import com.naeblis11.mealplanner.settings.SyncServerState
import com.naeblis11.mealplanner.settings.SyncServerStatus
import com.naeblis11.mealplanner.settings.TYPE_CODE
import com.naeblis11.mealplanner.settings.TYPE_CODE_TITLE
import com.naeblis11.mealplanner.settings.TYPE_ON_TRUSTED_NETWORK
import com.naeblis11.mealplanner.settings.WIFI_FIRST
import com.naeblis11.mealplanner.settings.joinOffer
import com.naeblis11.mealplanner.settings.leaveHouseholdQuestion
import com.naeblis11.mealplanner.settings.pcKeepsItsRecipes
import com.naeblis11.mealplanner.settings.syncsWith
import com.naeblis11.mealplanner.sync.FoundPc
import com.naeblis11.mealplanner.sync.PossibleDuplicate
import com.naeblis11.mealplanner.sync.SyncMessages
import com.naeblis11.mealplanner.sync.SyncStatus
import com.naeblis11.mealplanner.ui.DUPLICATES_HELP_PC
import com.naeblis11.mealplanner.ui.DuplicatesDialog
import com.naeblis11.mealplanner.ui.FirstSyncDialog
import com.naeblis11.mealplanner.ui.KEEP_MAIN_PCS
import com.naeblis11.mealplanner.ui.MERGE_HELP
import com.naeblis11.mealplanner.ui.REPLACE_HELP
import com.naeblis11.mealplanner.ui.mergeHelpPc
import com.naeblis11.mealplanner.ui.replaceHelpPc
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** LAN sync stage 4a: Join a household on a PC, a client PC's Alexa and pairing sentences, its dialogs and the first-run offer. */
class ClientPcScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val alexa = AlexaState(ServerStatus(ServerState.LISTENING, 5000, onLan = false, tokenConfigured = false))
    private val devices = DevicesState(status = SyncServerStatus(SyncServerState.OFF, 5001))

    private fun show(join: JoinState, actions: JoinActions = JoinActions()) = compose.showAt(600.dp, height = 2400.dp) {
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
            server = alexa,
            devices = devices,
            join = join,
            joinActions = actions,
        )
    }

    @Test
    fun aMainPcOffersJoinAHouseholdByTypedCodeOnly() {
        var typing = 0
        // Off "Wi-Fi" too: a PC never shows the phone's Wi-Fi line.
        show(JoinState(SyncStatus(known = true), onWifi = false, forPc = true), JoinActions(openTyping = { typing++ }))
        compose.onNodeWithText(JOIN_PANEL_PC).assertExists()
        compose.onNodeWithText(JOIN_PANEL).assertDoesNotExist()
        compose.onNodeWithText(JOIN_INTRO_PC).assertExists()
        compose.onNodeWithText(SCAN_CODE).assertDoesNotExist()
        compose.onNodeWithText(WIFI_FIRST).assertDoesNotExist()
        compose.onNodeWithText(TYPE_CODE).click()
        assertEquals(1, typing)
        // A main PC still makes tokens and pairs phones.
        compose.onNodeWithText(CREATE_TOKEN).assertIsEnabled()
        compose.onNodeWithText(PAIR_DEVICE).assertIsEnabled()
    }

    @Test
    fun theTypeCodeDialogOnAPcSaysNothingAboutScanning() {
        show(JoinState(SyncStatus(known = true), typing = true, forPc = true))
        compose.onNodeWithText(TYPE_CODE_TITLE).assertExists()
        compose.onNodeWithText(TYPE_ON_TRUSTED_NETWORK).assertExists()
    }

    @Test
    fun aClientPcSaysWhoAnswersAlexaAndPairsPhonesAndOffersLeave() {
        var asked = 0
        show(JoinState(SyncStatus(known = true, pcName = "KITCHEN"), forPc = true), JoinActions(askLeave = { asked++ }))
        compose.onNodeWithText(syncsWith("KITCHEN")).assertExists()
        compose.onNodeWithText(pcKeepsItsRecipes("KITCHEN")).assertExists()
        compose.onNodeWithText(SyncMessages.clientAlexa("KITCHEN")).assertExists()
        compose.onNodeWithText(CREATE_TOKEN).assertIsNotEnabled()
        compose.onNodeWithText(SyncMessages.clientDevices("KITCHEN")).assertExists()
        compose.onNodeWithText(PAIR_DEVICE).assertIsNotEnabled()
        compose.onNodeWithText(LEAVE).click()
        assertEquals(1, asked)
    }

    @Test
    fun leavingOnAPcAsksInItsWords() {
        show(JoinState(SyncStatus(known = true, pcName = "KITCHEN"), leaveAsk = true, forPc = true))
        compose.onNodeWithText(leaveHouseholdQuestion("KITCHEN")).assertExists()
    }

    @Test
    fun thePhonesJoinSettingKeepsItsAlexaAndPairing() {
        // A paired phone's JoinState (forPc false) never turns the Alexa or pairing sentences on.
        show(JoinState(SyncStatus(known = true, pcName = "KITCHEN")))
        compose.onNodeWithText(JOIN_PANEL).assertExists()
        compose.onNodeWithText(SyncMessages.clientAlexa("KITCHEN")).assertDoesNotExist()
        compose.onNodeWithText(PAIR_DEVICE).assertIsEnabled()
    }

    @Test
    fun theFirstSyncAndDuplicatesDialogsSpeakForAPc() {
        compose.showAt(600.dp) { FirstSyncDialog("KITCHEN", onMerge = {}, onReplace = {}, onCancel = {}, forPc = true) }
        compose.onNodeWithText(mergeHelpPc("KITCHEN")).assertExists()
        compose.onNodeWithText(replaceHelpPc("KITCHEN")).assertExists()
        compose.onNodeWithText(MERGE_HELP).assertDoesNotExist()
        compose.onNodeWithText(REPLACE_HELP).assertDoesNotExist()
    }

    @Test
    fun theDuplicatesDialogSpeaksForAPc() {
        val soup = PossibleDuplicate("Soup", 1L, 2L)
        compose.showAt(600.dp) { DuplicatesDialog(listOf(soup), onKeepBoth = {}, onKeepPcs = {}, onLater = {}, forPc = true) }
        compose.onNodeWithText(DUPLICATES_HELP_PC).assertExists()
        compose.onNodeWithText(KEEP_MAIN_PCS).assertExists()
    }

    @Test
    fun theFirstRunOfferJoinsOrIsPutAwayForGood() = runBlocking<Unit> {
        val settings = MapSettings()
        val offer = DesktopJoinOffer(settings, firstRun = true, isClient = { false }, find = { listOf(FoundPc("KITCHEN", "192.0.2.20", 5001)) })
        offer.look()
        var joins = 0
        compose.showAt(600.dp) { JoinOfferBanner(offer, onJoin = { joins++ }) }
        compose.onNodeWithText(joinOffer("KITCHEN")).assertExists()
        compose.onNodeWithText(JOIN_OFFER_JOIN).click()
        assertEquals(1, joins)
        compose.onNodeWithText(KEEP_SEPARATE).click()
        compose.onNodeWithText(joinOffer("KITCHEN")).assertDoesNotExist()
        assertEquals("1", settings.getString(JOIN_OFFER_DISMISSED_KEY))
        // Put away for good: a later first-run look offers nothing.
        val again = DesktopJoinOffer(settings, firstRun = true, isClient = { false }, find = { listOf(FoundPc("KITCHEN", "192.0.2.20", 5001)) })
        again.look()
        assertNull(again.offer.value)
    }

    @Test
    fun theOfferIsOnlyForAFirstRunThatIsNotAClient() = runBlocking<Unit> {
        var looks = 0
        val found = listOf(FoundPc("KITCHEN", "192.0.2.20", 5001))
        val later = DesktopJoinOffer(MapSettings(), firstRun = false, isClient = { false }, find = { looks++; found })
        later.look()
        assertNull(later.offer.value)
        val client = DesktopJoinOffer(MapSettings(), firstRun = true, isClient = { true }, find = { looks++; found })
        client.look()
        assertNull(client.offer.value)
        assertEquals("never even looked", 0, looks)
        // Joined while the offer is up: it goes.
        var joined = false
        val shown = DesktopJoinOffer(MapSettings(), firstRun = true, isClient = { joined }, find = { found })
        shown.look()
        assertEquals("KITCHEN", shown.offer.value)
        joined = true
        shown.roleChanged()
        assertNull(shown.offer.value)
        // Nothing found, or the look failed: nothing offered.
        val none = DesktopJoinOffer(MapSettings(), firstRun = true, isClient = { false }, find = { throw IllegalStateException("no network") })
        none.look()
        assertNull(none.offer.value)
    }
}
