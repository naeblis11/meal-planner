package com.naeblis11.mealplanner.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.naeblis11.mealplanner.app.SettingsStore

/** How Windows starts the app at sign-in (the Run key's command line): hidden in the tray. */
const val MINIMIZED_ARG = "--minimized"

/** What the window's close button did. */
enum class CloseOutcome { HIDDEN, HIDDEN_FIRST_TIME, QUIT }

/**
 * The window while the app lives in the tray (P3-R1, P3-R6). Closing hides it and the app keeps running, unless
 * there is no tray to come back from, when closing quits. The screens are composed from the window's first showing on
 * ([hasBeenShown]), so a start hidden in the tray runs no UI work and marks no message as seen. Compose state: read it
 * in composition, change it on the UI thread. [beforeShow] runs at every [show] before the window comes forward (P7-R12:
 * whether the app was replaced on disk), so no way of showing it skips that look.
 */
class WindowShell(
    startMinimized: Boolean,
    val traySupported: Boolean,
    private val notice: TrayNotice,
    private val beforeShow: () -> Unit = {},
) {
    var isVisible by mutableStateOf(!(startMinimized && traySupported))
        private set

    var hasBeenShown by mutableStateOf(isVisible)
        private set

    /** Goes up at every [show]; the window raises itself when it changes. */
    var raiseRequests by mutableIntStateOf(0)
        private set

    var quitting by mutableStateOf(false)
        private set

    /** The tray's Open, a double click on its icon, a second launch, or a recipe from the extension. */
    fun show() {
        if (quitting) return
        try {
            beforeShow()
        } catch (e: Exception) {
            System.err.println("Meal Planner: looking before showing the window failed: ${e.javaClass.simpleName}")
        }
        isVisible = true
        hasBeenShown = true
        raiseRequests++
    }

    /** The window's close button: hide, and say so the first time on this PC; with no tray the caller quits. */
    fun closeRequested(): CloseOutcome {
        if (!traySupported) return CloseOutcome.QUIT
        isVisible = false
        return if (notice.takeFirst()) CloseOutcome.HIDDEN_FIRST_TIME else CloseOutcome.HIDDEN
    }

    /** The tray's Quit (or a close with no tray). True only the first time: the caller then runs the shutdown. */
    fun quit(): Boolean {
        if (quitting) return false
        quitting = true
        isVisible = false
        return true
    }
}

/** The "still running in the tray" message: shown once on this PC (P3-R1), remembered in the settings node. */
class TrayNotice(private val settings: SettingsStore) {
    private var shownThisRun = false

    /** True the first time ever; that is recorded at once. A settings failure still shows it once per run. */
    fun takeFirst(): Boolean {
        if (shownThisRun) return false
        shownThisRun = true
        val before = try {
            settings.getString(KEY)
        } catch (e: Exception) {
            null
        }
        if (before == SHOWN) return false
        try {
            settings.put(mapOf(KEY to SHOWN))
        } catch (e: Exception) {
            System.err.println("Meal Planner: couldn't remember the tray message: $e")
        }
        return true
    }

    companion object {
        const val KEY = "tray_notice"
        const val SHOWN = "shown"
        const val TITLE = "Meal Planner"
        const val MESSAGE = "Meal Planner is still running in the tray so your phone can sync."
    }
}
