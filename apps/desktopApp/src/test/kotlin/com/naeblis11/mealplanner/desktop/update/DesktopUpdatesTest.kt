package com.naeblis11.mealplanner.desktop.update

import com.naeblis11.mealplanner.desktop.MapSettings
import com.naeblis11.mealplanner.ui.LeaveGuard
import com.naeblis11.mealplanner.update.ReleaseEndpoints
import com.naeblis11.mealplanner.update.ReleaseHttp
import com.naeblis11.mealplanner.update.RunningApp
import com.naeblis11.mealplanner.update.UpdateMessages
import com.naeblis11.mealplanner.update.UpdateOffer
import com.naeblis11.mealplanner.update.UpdateStatus
import com.naeblis11.mealplanner.update.Updates
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopUpdatesTest {
    private val dir: File = Files.createTempDirectory("mp-desktop-updates").toFile()

    // A throwaway key, in memory; the owner's is never read here.
    private val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun onlyTheInstalledAppChecks() {
        assertNull(DesktopUpdates.unavailableReason(installed = "true", gate = null, version = "1.0.0"))
        assertNull(DesktopUpdates.unavailableReason(installed = "true", gate = "on", version = "1.0.0"))
        // Development and the preview (no installed=true, no version) can't update themselves.
        assertEquals(DesktopUpdates.NOT_INSTALLED, DesktopUpdates.unavailableReason(installed = null, gate = null, version = null))
        assertEquals(DesktopUpdates.NOT_INSTALLED, DesktopUpdates.unavailableReason(installed = "true", gate = null, version = null))
        assertEquals(DesktopUpdates.NOT_INSTALLED, DesktopUpdates.unavailableReason(installed = "true", gate = null, version = " "))
        assertEquals(DesktopUpdates.NOT_INSTALLED, DesktopUpdates.unavailableReason(installed = null, gate = "on", version = "1.0.0"))
        assertEquals(DesktopUpdates.NOT_INSTALLED, DesktopUpdates.unavailableReason(installed = "false", gate = "on", version = "1.0.0"))
    }

    @Test
    fun offBeatsTheInstalledLauncher() {
        // JAVA_TOOL_OPTIONS can add -Dmealplanner.updates=off but can't override the launcher's installed=true (plan 7):
        // the smoke run's copy must never contact GitHub. A typo counts as off.
        assertEquals(DesktopUpdates.CHECKS_OFF, DesktopUpdates.unavailableReason(installed = "true", gate = "off", version = "1.0.0"))
        assertEquals(DesktopUpdates.CHECKS_OFF, DesktopUpdates.unavailableReason(installed = "true", gate = "", version = "1.0.0"))
        assertEquals(DesktopUpdates.CHECKS_OFF, DesktopUpdates.unavailableReason(installed = "true", gate = "no", version = "1.0.0"))
        assertEquals(DesktopUpdates.CHECKS_OFF, DesktopUpdates.unavailableReason(installed = "true", gate = "ON", version = "1.0.0"))
    }

    @Test
    fun downloadsGoIntoTheAppDataFolderNeverDocumentsOrTheInstallFolder() {
        // P7-R10: the app data folder is the secrets folder, %LOCALAPPDATA%\Meal Planner (or the preview's data folder).
        val appData = File(dir, "LocalAppData/Meal Planner")
        assertEquals(File(appData, "updates").absoluteFile, DesktopUpdates.folder(appData))
        assertEquals(Updates.DIR_NAME, DesktopUpdates.FOLDER)
        val updates = DesktopUpdates.create(appData, MapSettings(), quit = {}, version = "1.0.0", installed = "true", gate = null, publicKey = key)
        assertEquals(File(appData, "updates").absoluteFile, updates.dir)
        // Nothing is made until a download needs it.
        assertFalse(File(appData, "updates").exists())
    }

    @Test
    fun theInstalledAppAsksGitHubWithTheBuiltInKey() {
        val appData = File(dir, "data")
        val updates = DesktopUpdates.create(appData, MapSettings(), quit = {}, version = "1.0.0", installed = "true", gate = null, publicKey = key)
        // CARRY (Task 3/4): ReleaseHttp's defaults and ReleaseEndpoints.GITHUB, never from configuration.
        assertEquals(ReleaseEndpoints.GITHUB, updates.endpoints)
        assertEquals(ReleaseHttp.GITHUB_HOSTS, updates.http.hosts)
        assertEquals(RunningApp.Desktop("1.0.0"), updates.running)
        assertTrue(updates.state.value.offered)
        assertNull(updates.state.value.unavailableReason)
        assertTrue(updates.state.value.installClosesApp)
        // The installer hands over only what is in this same folder.
        assertEquals(updates.dir, DesktopUpdates.installer(appData, quit = {}).updatesDir)
    }

    @Test
    fun thePreviewAndTheSmokeRunNeverCheck() {
        val preview = DesktopUpdates.create(File(dir, "preview"), MapSettings(), quit = {}, version = null, installed = null, gate = null, publicKey = key)
        assertFalse(preview.state.value.offered)
        assertEquals(DesktopUpdates.NOT_INSTALLED, preview.state.value.unavailableReason)
        val smoke = DesktopUpdates.create(File(dir, "smoke"), MapSettings(), quit = {}, version = "1.0.0", installed = "true", gate = "off", publicKey = key)
        assertFalse(smoke.state.value.offered)
        assertEquals(DesktopUpdates.CHECKS_OFF, smoke.state.value.unavailableReason)
    }

    @Test
    fun aFolderTheInstallerCantBeHandedSaysSoBeforeAnyDownload() {
        // P8-R6a: judged at creation, so Settings explains it instead of failing after a download.
        val comma = File(dir, "a,b")
        assertEquals(DesktopUpdates.PATH_UNSAFE, DesktopUpdates.handOffReason(comma))
        val updates = DesktopUpdates.create(comma, MapSettings(), quit = {}, version = "1.0.0", installed = "true", gate = null, publicKey = key)
        assertFalse(updates.state.value.offered)
        assertEquals(DesktopUpdates.PATH_UNSAFE, updates.state.value.unavailableReason)
        assertTrue(DesktopUpdates.PATH_UNSAFE.contains("releases page"))
        // The run's own reasons come first: the preview never mentions the folder.
        val preview = DesktopUpdates.create(comma, MapSettings(), quit = {}, version = null, installed = null, gate = null, publicKey = key)
        assertEquals(DesktopUpdates.NOT_INSTALLED, preview.state.value.unavailableReason)
        // An apostrophe, an accent and an ampersand are fine (where the temp folder itself is).
        if (MsiInstaller.isHandOffSafe(dir.absolutePath)) {
            for (name in listOf("O'Brien", "Jos" + 0xE9.toChar(), "R&D")) assertNull(name, DesktopUpdates.handOffReason(File(dir, name)))
        }
    }

    @Test
    fun installWaitsUntilTheAppCanQuit() {
        // P8-R6a: Main passes canQuit = quitNow is set; the installer follows it.
        var ready = false
        val installer = DesktopUpdates.installer(File(dir, "data"), quit = {}, canQuit = { ready })
        assertFalse(installer.canStartNow())
        ready = true
        assertTrue(installer.canStartNow())
    }

    @Test
    fun installWaitsWhileAFormHoldsTheLeaveGuard() {
        // P8-F1: an edit or import review begun during the download holds the guard; the installer waits, never quits.
        val guard = LeaveGuard()
        val updateQuit = UpdateQuit()
        val events = mutableListOf<String>()
        val installer = DesktopUpdates.installer(File(dir, "data"), quit = updateQuit::quit, canQuit = updateQuit::canQuit)
        assertFalse("no quit attached yet", installer.canStartNow())
        updateQuit.attach(quit = { guard.request { events += "quit" } }, holding = { guard.holding })
        assertTrue(installer.canStartNow())
        val release = guard.hold { events += "asked" }
        assertFalse("a form with unsaved changes holds the guard", installer.canStartNow())
        // A quit reached anyway (the moment between the check and the quit) asks the holder; it never quits by itself.
        updateQuit.quit()
        assertEquals(listOf("asked"), events)
        release()
        assertTrue(installer.canStartNow())
        updateQuit.quit()
        assertEquals(listOf("asked", "quit"), events)
        // Fails closed.
        updateQuit.attach(quit = {}, holding = { throw IllegalStateException("no") })
        assertFalse(installer.canStartNow())
    }

    @Test
    fun theKeyIsUpdatesReleaseKey() {
        // CARRY (Task 4): the default key is Updates.releaseKey()'s, which turns any key problem into "no update".
        val updates = DesktopUpdates.create(File(dir, "data"), MapSettings(), quit = {}, version = "1.0.0", installed = "true", gate = null)
        val expected = if (Updates.releaseKey() == null) UpdateMessages.NO_KEY else null
        assertEquals(expected, updates.state.value.unavailableReason)
        val source = File(
            File(System.getProperty("launcherOptionsScript") ?: error("launcherOptionsScript is not set; run through Gradle")).parentFile,
            "src/main/kotlin/com/naeblis11/mealplanner/desktop/update/DesktopUpdates.kt",
        ).readText()
        assertTrue(source.contains("Updates.releaseKey()"))
        assertFalse(source.contains("ReleaseKey.builtIn"))
        // Hosts and addresses are never read from configuration: only the defaults.
        assertFalse(Regex("ReleaseHttp\\((?!\\))").containsMatchIn(source))
        assertFalse(Regex("ReleaseEndpoints\\(").containsMatchIn(source))
        assertFalse(source.contains("getenv"))
    }

    @Test
    fun theTrayTooltipNamesAnUpdate() {
        assertEquals("Meal Planner", trayTooltip(UpdateStatus(offered = true)))
        val offer = UpdateOffer("1.0.1", "release-2", "MealPlanner-1.0.1.msi", 10, "a".repeat(64))
        assertEquals("Meal Planner: version 1.0.1 is available", trayTooltip(UpdateStatus(offered = true, offer = offer)))
    }
}
