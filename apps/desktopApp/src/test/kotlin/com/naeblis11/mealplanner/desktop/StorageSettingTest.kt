package com.naeblis11.mealplanner.desktop

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.folder.LIBRARY_BLOCKED_MESSAGE
import com.naeblis11.mealplanner.settings.ALLOW_APP_EXPLAIN
import com.naeblis11.mealplanner.settings.ALLOW_APP_LABEL
import com.naeblis11.mealplanner.settings.StorageControls
import com.naeblis11.mealplanner.ui.MealPlannerApp
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * P7-R10: Settings says where the recipes and the app data are, with Open folder for each that exists, and while Windows
 * keeps the app from saving to Documents the window says how to allow it, above every screen and atop Settings.
 */
class StorageSettingTest {
    // Outermost: the app and its database close only after the compose rule has disposed the composition.
    @get:Rule(order = 0)
    val closing = CloseAfterCompose({ tearDown() })

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val dir: File = Files.createTempDirectory("mp-storage").toFile()
    private val app = DesktopApp(dir, settingsFactory = { MapSettings() })

    private fun tearDown() {
        app.close()
        dir.deleteRecursively()
    }

    private class FakeStorage(
        notice: String?,
        var libraryThere: Boolean = true,
        override val canAllowApp: Boolean = false,
        private val allowAnswer: String? = null,
    ) : StorageControls {
        override val libraryPath = "C:\\Users\\someone\\Documents\\Meal Planner"
        override val appDataPath = "C:\\Users\\someone\\AppData\\Local\\Meal Planner"
        override val libraryNotice = MutableStateFlow(notice)
        override val checking = MutableStateFlow(false)
        override val allowing = MutableStateFlow(false)
        override val allowMessage = MutableStateFlow<String?>(null)
        val opened = mutableListOf<String>()
        var checks = 0
        var allows = 0

        // P7-R11: the fake answers as AllowApp.run would; nothing elevates.
        override fun allowApp() {
            allows++
            allowMessage.value = allowAnswer
            if (allowAnswer == null) libraryNotice.value = null
        }

        // The user's Check again: here, Windows has allowed the app meanwhile, and the library folder is made.
        override fun checkAgain() {
            checks++
            libraryNotice.value = null
            libraryThere = true
        }

        override fun libraryExists() = libraryThere

        override fun appDataExists() = true

        override fun openLibrary(): Boolean = opened.add("library")

        override fun openAppData(): Boolean = opened.add("appData")
    }

    @Test
    fun theInstalledAppOffersToAllowItselfInTheBannerAndInSettings() {
        val storage = FakeStorage(LIBRARY_BLOCKED_MESSAGE, canAllowApp = true)
        compose.showAt(1000.dp) { MealPlannerApp(app.container, storage = storage) }
        compose.waitForText(ALLOW_APP_LABEL)
        compose.onAllNodesWithText(ALLOW_APP_EXPLAIN).assertCountEquals(1)
        compose.tab("Settings").click()
        compose.waitForText("Your recipes: ${storage.libraryPath}")
        compose.onAllNodesWithText(ALLOW_APP_LABEL).assertCountEquals(2)
        assertEquals(0, storage.allows)
        // Allowed and checked again: the notice and both buttons go.
        compose.onAllNodesWithText(ALLOW_APP_LABEL)[0].click()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(LIBRARY_BLOCKED_MESSAGE).fetchSemanticsNodes().isEmpty() }
        compose.onAllNodesWithText(ALLOW_APP_LABEL).assertCountEquals(0)
        assertEquals(1, storage.allows)
    }

    @Test
    fun aDeclinedPromptSaysSoAndKeepsTheButton() {
        val storage = FakeStorage(LIBRARY_BLOCKED_MESSAGE, canAllowApp = true, allowAnswer = "Not allowed. Nothing changed.")
        compose.showAt(1000.dp) { MealPlannerApp(app.container, storage = storage) }
        compose.waitForText(ALLOW_APP_LABEL)
        compose.onAllNodesWithText(ALLOW_APP_LABEL)[0].click()
        compose.waitForText("Not allowed. Nothing changed.")
        compose.onAllNodesWithText(ALLOW_APP_LABEL).assertCountEquals(1)
    }

    @Test
    fun theButtonIsOffWhileItRuns() {
        val storage = FakeStorage(LIBRARY_BLOCKED_MESSAGE, canAllowApp = true).apply { allowing.value = true }
        compose.showAt(1000.dp) { MealPlannerApp(app.container, storage = storage) }
        compose.waitForText(ALLOW_APP_LABEL)
        compose.onAllNodesWithText(ALLOW_APP_LABEL)[0].assertIsNotEnabled()
    }

    @Test
    fun noAllowButtonOutsideTheInstalledAppOrForAnyOtherFailure() {
        val preview = FakeStorage(LIBRARY_BLOCKED_MESSAGE, canAllowApp = false)
        compose.showAt(1000.dp) { MealPlannerApp(app.container, storage = preview) }
        compose.waitForText(LIBRARY_BLOCKED_MESSAGE)
        compose.onAllNodesWithText(ALLOW_APP_LABEL).assertCountEquals(0)
        // A full disk isn't Controlled folder access: no button there either.
        preview.libraryNotice.value = "Couldn't save to Documents\\Meal Planner: There is not enough space on the disk"
        compose.waitForText("Couldn't save to Documents\\Meal Planner: There is not enough space on the disk")
        compose.onAllNodesWithText(ALLOW_APP_LABEL).assertCountEquals(0)
    }

    @Test
    fun aFullDiskOnTheInstalledAppHasNoAllowButton() {
        val full = "Couldn't save to Documents\\Meal Planner: There is not enough space on the disk"
        val storage = FakeStorage(full, canAllowApp = true)
        compose.showAt(1000.dp) { MealPlannerApp(app.container, storage = storage) }
        compose.waitForText(full)
        compose.onAllNodesWithText(ALLOW_APP_LABEL).assertCountEquals(0)
    }

    @Test
    fun aBlockedLibraryIsSaidAboveEveryScreenAndAtopSettings() {
        val storage = FakeStorage(LIBRARY_BLOCKED_MESSAGE)
        compose.showAt(1000.dp) { MealPlannerApp(app.container, storage = storage) }
        compose.waitForText(LIBRARY_BLOCKED_MESSAGE)
        compose.tab("Settings").click()
        compose.waitForText("Your recipes: ${storage.libraryPath}")
        compose.onAllNodesWithText(LIBRARY_BLOCKED_MESSAGE).assertCountEquals(2)
        // P7-R10b: opening Settings writes nothing to look.
        assertEquals(0, storage.checks)
    }

    @Test
    fun anyOtherFailureIsSaidAsItIs() {
        val full = "Couldn't save to Documents\\Meal Planner: There is not enough space on the disk"
        val storage = FakeStorage(full)
        compose.showAt(1000.dp) { MealPlannerApp(app.container, storage = storage) }
        compose.waitForText(full)
        compose.onAllNodesWithText(LIBRARY_BLOCKED_MESSAGE).assertCountEquals(0)
    }

    @Test
    fun noNoticeUntilAWriteIsRefused() {
        val storage = FakeStorage(null)
        compose.showAt(1000.dp) { MealPlannerApp(app.container, storage = storage) }
        compose.waitForText("New recipe")
        compose.onAllNodesWithText(LIBRARY_BLOCKED_MESSAGE).assertCountEquals(0)
        storage.libraryNotice.value = LIBRARY_BLOCKED_MESSAGE
        compose.waitForText(LIBRARY_BLOCKED_MESSAGE)
    }

    @Test
    fun checkAgainIsOffWhileItIsChecking() {
        // M5: no second write while one is under way.
        val storage = FakeStorage(LIBRARY_BLOCKED_MESSAGE).apply { checking.value = true }
        compose.showAt(1000.dp) { MealPlannerApp(app.container, storage = storage) }
        compose.tab("Settings").click()
        compose.waitForText("Checking...")
        compose.onAllNodesWithText("Checking...")[0].assertIsNotEnabled()
        storage.checking.value = false
        compose.waitForText(CHECK_AGAIN)
        compose.onAllNodesWithText(CHECK_AGAIN)[0].assertIsEnabled()
    }

    @Test
    fun theFoldersAreReadAgainAfterCheckAgain() {
        // M6: a library folder Check again made gets its Open folder.
        val storage = FakeStorage(LIBRARY_BLOCKED_MESSAGE, libraryThere = false)
        compose.showAt(1000.dp) { MealPlannerApp(app.container, storage = storage) }
        compose.tab("Settings").click()
        compose.waitForText("App data: ${storage.appDataPath}")
        compose.waitUntil(5_000) { compose.onAllNodesWithText(OPEN_FOLDER).fetchSemanticsNodes().size == 1 }
        compose.onAllNodesWithText(CHECK_AGAIN)[0].click()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(OPEN_FOLDER).fetchSemanticsNodes().size == 2 }
    }

    @Test
    fun checkAgainIsOnlyWhatTheUserPresses() {
        val storage = FakeStorage(LIBRARY_BLOCKED_MESSAGE)
        compose.showAt(1000.dp) { MealPlannerApp(app.container, storage = storage) }
        compose.tab("Settings").click()
        compose.waitForText(CHECK_AGAIN)
        compose.waitForIdle()
        assertEquals(0, storage.checks)
        compose.onAllNodesWithText(CHECK_AGAIN)[0].click()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(LIBRARY_BLOCKED_MESSAGE).fetchSemanticsNodes().isEmpty() }
        assertEquals(1, storage.checks)
        compose.onAllNodesWithText(CHECK_AGAIN).assertCountEquals(0)
    }

    @Test
    fun settingsShowsBothFoldersAndOpensEach() {
        val storage = FakeStorage(null)
        compose.showAt(1000.dp) { MealPlannerApp(app.container, storage = storage) }
        compose.tab("Settings").click()
        compose.waitForText("Your recipes: ${storage.libraryPath}")
        compose.waitForText("App data: ${storage.appDataPath}")
        compose.waitUntil(5_000) { compose.onAllNodesWithText(OPEN_FOLDER).fetchSemanticsNodes().size == 2 }
        compose.onAllNodesWithText(OPEN_FOLDER)[0].click()
        compose.onAllNodesWithText(OPEN_FOLDER)[1].click()
        compose.waitForIdle()
        assertEquals(listOf("library", "appData"), storage.opened)
        assertEquals(0, storage.checks)
    }

    @Test
    fun aLibraryThatIsntThereHasNoOpenFolder() {
        val storage = FakeStorage(null, libraryThere = false)
        compose.showAt(1000.dp) { MealPlannerApp(app.container, storage = storage) }
        compose.tab("Settings").click()
        compose.waitForText("Your recipes: ${storage.libraryPath}")
        compose.waitForText("App data: ${storage.appDataPath}")
        compose.waitUntil(5_000) { compose.onAllNodesWithText(OPEN_FOLDER).fetchSemanticsNodes().size == 1 }
        compose.onAllNodesWithText(OPEN_FOLDER)[0].click()
        compose.waitForIdle()
        assertEquals(listOf("appData"), storage.opened)
    }

    private companion object {
        const val OPEN_FOLDER = "Open folder"
        const val CHECK_AGAIN = "Check again"
    }
}
