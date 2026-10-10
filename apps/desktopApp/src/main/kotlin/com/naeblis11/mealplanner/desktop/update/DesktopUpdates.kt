package com.naeblis11.mealplanner.desktop.update

import com.naeblis11.mealplanner.app.SettingsStore
import com.naeblis11.mealplanner.desktop.StartWithWindows
import com.naeblis11.mealplanner.update.ReleaseEndpoints
import com.naeblis11.mealplanner.update.ReleaseHttp
import com.naeblis11.mealplanner.update.RunningApp
import com.naeblis11.mealplanner.update.UpdateStatus
import com.naeblis11.mealplanner.update.Updates
import java.io.File
import java.security.PublicKey
import java.util.concurrent.atomic.AtomicReference

/** The Windows app's update check (plan 8): who checks, where downloads go, and how Install hands over and quits. */
object DesktopUpdates {
    /** Set to anything but "on", no check runs, even in the installed app (the smoke run passes "off"). */
    const val GATE_PROPERTY = "mealplanner.updates"

    /** The installed app's version, which only the packaged launcher passes (plan 7). */
    const val VERSION_PROPERTY = "mealplanner.version"

    /** The downloads' folder in the app data folder; Updates refuses any other name. */
    const val FOLDER = Updates.DIR_NAME
    const val NOT_INSTALLED = "Only the installed app checks for updates."
    const val CHECKS_OFF = "Update checks are turned off for this run."

    /**
     * Why this run never checks, or null when it does: only the installed app ([installed] is
     * StartWithWindows.INSTALLED_PROPERTY's value, "true" in the packaged launcher, with [version] set) checks, and never
     * when [gate] is set to anything but "on". JAVA_TOOL_OPTIONS can add a property but not override the launcher's own,
     * so the off has to win here, as for Start with Windows and discovery (plan 7).
     */
    fun unavailableReason(installed: String?, gate: String?, version: String?): String? = when {
        gate != null && gate != "on" -> CHECKS_OFF
        installed != "true" || version.isNullOrBlank() -> NOT_INSTALLED
        else -> null
    }

    /**
     * Where downloads go (P7-R10): `updates` in the app data folder ([appDataDir], DesktopPaths.appDataDir: the secrets
     * folder `%LOCALAPPDATA%\Meal Planner`, writable only by this user), never Documents, Downloads or the install
     * folder `%LOCALAPPDATA%\Meal-Planner`. Anything run with mealplanner.dataDir keeps its own there.
     */
    fun folder(appDataDir: File): File = File(appDataDir.absoluteFile, FOLDER)

    /**
     * P8-R6a: why an MSI downloaded into [appDataDir]'s updates folder could never be handed to the installer
     * (MsiInstaller.isHandOffSafe, judged on a sample name), or null when it could. Checked before any download, so a
     * PC whose folder can't be handed over says so in Settings instead of failing after the download.
     */
    fun handOffReason(appDataDir: File): String? =
        if (MsiInstaller.isHandOffSafe(File(folder(appDataDir), SAMPLE_FILE).absolutePath)) null else PATH_UNSAFE

    /**
     * The PC's installer: the verified MSI to explorer.exe, then [quit] (MsiInstaller, P8-PF2); never started while
     * [canQuit] is false.
     */
    fun installer(appDataDir: File, quit: () -> Unit, canQuit: () -> Boolean = { true }): MsiInstaller =
        MsiInstaller(folder(appDataDir), quit, canQuit)

    /**
     * The PC's Updates: GitHub through ReleaseHttp's defaults and ReleaseEndpoints.GITHUB (never from configuration),
     * the built-in key through Updates.releaseKey() (a key problem is "no update", never verified), and MsiInstaller.
     * [canQuit] is whether main's quit is ready (P8-R6a); until it is, a verified download waits for Install again.
     */
    fun create(
        appDataDir: File,
        settings: SettingsStore,
        quit: () -> Unit,
        canQuit: () -> Boolean = { true },
        version: String? = System.getProperty(VERSION_PROPERTY),
        installed: String? = System.getProperty(StartWithWindows.INSTALLED_PROPERTY),
        gate: String? = System.getProperty(GATE_PROPERTY),
        publicKey: PublicKey? = Updates.releaseKey(),
    ): Updates = Updates(
        running = RunningApp.Desktop(version?.takeIf { it.isNotBlank() } ?: "dev"),
        publicKey = publicKey,
        installer = installer(appDataDir, quit, canQuit),
        settings = settings,
        dir = folder(appDataDir),
        http = ReleaseHttp(),
        endpoints = ReleaseEndpoints.GITHUB,
        unavailable = unavailableReason(installed, gate, version) ?: handOffReason(appDataDir),
    )

    const val PATH_UNSAFE =
        "Meal Planner can't install updates from this folder because its path has a character Windows can't pass to " +
            "the installer. Download new versions from the releases page."

    private const val SAMPLE_FILE = "update-x.msi"
}

/** The tray icon's tooltip: an update found while the app sits in the tray shows there too. */
fun trayTooltip(status: UpdateStatus): String =
    status.offer?.let { "Meal Planner: version ${it.version} is available" } ?: "Meal Planner"

/**
 * The update's way out of the app (P8-R6a, P8-F1). Main attaches the window's guarded quit and the LeaveGuard once
 * they are composed. [canQuit] (MsiInstaller's canStartNow, asked after the download) is true only once they are
 * there and no form or import review holds the guard, so an edit begun while the MSI downloaded is never discarded:
 * the verified file waits instead (Updates' Waiting) and the notice asks to save or leave the edit, then Install
 * again. [quit] runs the attached quit, which still asks a holder first, for an edit begun in the moment between.
 */
class UpdateQuit {
    private class Ready(val quit: () -> Unit, val holding: () -> Boolean)

    private val ready = AtomicReference<Ready?>(null)

    /** [quit] is the window's guarded quit; [holding] whether a page holds the LeaveGuard. */
    fun attach(quit: () -> Unit, holding: () -> Boolean) {
        ready.set(Ready(quit, holding))
    }

    /** Whether the installer may start now: the quit is there and nothing unsaved holds it. Fails closed. */
    fun canQuit(): Boolean {
        val current = ready.get() ?: return false
        return try {
            !current.holding()
        } catch (e: Exception) {
            false
        }
    }

    /** The attached quit, or nothing while there is none (canQuit was false, so nothing was started). */
    fun quit() {
        ready.get()?.quit?.invoke()
    }
}
