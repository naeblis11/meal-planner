package com.naeblis11.mealplanner.settings

import kotlinx.coroutines.flow.StateFlow

/**
 * P7-R12: the desktop app noticing it was replaced on disk while it ran (a new MSI installed over it), so the window
 * says so above every screen. Null on Android.
 */
interface RestartControls {
    /** Null while the running copy is current; once it was replaced, how it can be restarted. It stays for the session. */
    val replaced: StateFlow<AppReplaced?>

    /** "Restart now": the normal Quit (asking about an unsaved edit first), then the installed app opens again. */
    fun restartNow()
}

/**
 * The running copy was replaced on disk. [canRestart] is true only in the installed app with a valid launcher; [removed]
 * when the installed launcher is gone too (uninstalled, or an install under way), so there is nothing to restart yet.
 */
data class AppReplaced(val canRestart: Boolean, val removed: Boolean = false)

/** P7-R12: the notice, and what is offered under it. */
const val APP_REPLACED_MESSAGE = "Meal Planner has been updated. Restart to use the new version."
const val RESTART_NOW_LABEL = "Restart now"
const val RESTART_BY_HAND = "Quit Meal Planner and open it again."
const val APP_REMOVED_MESSAGE =
    "Meal Planner was removed or is being updated. Quit it from the tray, then open it again once it is installed."
