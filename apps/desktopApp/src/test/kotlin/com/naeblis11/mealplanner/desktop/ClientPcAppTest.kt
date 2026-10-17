package com.naeblis11.mealplanner.desktop

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import com.naeblis11.mealplanner.sync.FirstSync
import com.naeblis11.mealplanner.sync.FoundPc
import com.naeblis11.mealplanner.sync.JoinControls
import com.naeblis11.mealplanner.sync.PossibleDuplicate
import com.naeblis11.mealplanner.sync.SyncControls
import com.naeblis11.mealplanner.sync.SyncNoun
import com.naeblis11.mealplanner.sync.SyncStatus
import com.naeblis11.mealplanner.ui.JOIN_HINT
import com.naeblis11.mealplanner.ui.MealPlannerApp
import com.naeblis11.mealplanner.ui.SYNC_ACTION_TAG
import com.naeblis11.mealplanner.ui.theme.MealPlannerTheme
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.prefs.Preferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

/** LAN sync stage 4a, the whole window: a PC gets the Sync button once it has joined, and never the phone's join hint. */
class ClientPcAppTest {
    @get:Rule(order = 0)
    val closing = CloseAfterCompose({ tearDown() })

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val dir: File = Files.createTempDirectory("mp-client-app").toFile()
    private val prefsNode = "com/naeblis11/mealplanner/test-${System.nanoTime()}"
    private val app = DesktopApp(dir, prefsNode)
    private val controls = PcControls()

    private fun tearDown() {
        app.close()
        dir.deleteRecursively()
        Preferences.userRoot().node(prefsNode).removeNode()
    }

    // A PC's sync with no main PC: what the window reads, nothing more.
    private class PcControls : SyncControls, JoinControls {
        override val noun = SyncNoun.PC
        override val status = MutableStateFlow(SyncStatus(known = true))
        override val syncing = MutableStateFlow(false)
        override val duplicates = MutableStateFlow<List<PossibleDuplicate>>(emptyList())
        override val outcome = MutableStateFlow<String?>(null)
        override val onWifi = MutableStateFlow(true)

        override suspend fun needsFirstChoice() = false

        override fun sync(first: FirstSync?) = Unit

        override fun replace(open: () -> OutputStream, deleteBackup: () -> Unit) = Unit

        override fun outcomeShown(text: String) = Unit

        override fun keepBoth(duplicate: PossibleDuplicate) = Unit

        override fun keepPcs(duplicate: PossibleDuplicate) = Unit

        override fun recheckWifi() = Unit

        override suspend fun pairScanned(text: String) = Unit

        override suspend fun findPcs(): List<FoundPc> = emptyList()

        override suspend fun pairTyped(code: String, pc: FoundPc) = Unit

        override suspend fun leave() = Unit
    }

    @Test
    fun theSyncButtonShowsOnceThisPcHasJoinedAndThePhonesHintNever() {
        compose.setTestContent { MealPlannerTheme { MealPlannerApp(app.container, join = controls, sync = controls) } }
        compose.waitForText("Shopping")
        compose.waitForIdle()
        check(compose.onAllNodesWithText(JOIN_HINT).fetchSemanticsNodes().isEmpty()) { "the phone's hint showed on a PC" }
        check(compose.onAllNodesWithTag(SYNC_ACTION_TAG).fetchSemanticsNodes().isEmpty()) { "a Sync button before joining" }
        controls.status.value = SyncStatus(known = true, pcName = "KITCHEN")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(SYNC_ACTION_TAG).fetchSemanticsNodes().isNotEmpty() }
    }
}
