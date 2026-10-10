package com.naeblis11.mealplanner.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The desktop's two folders (P7-R10): the library (recipes and photos, in Documents) and the app's own data (its
 * database, log and secrets, in %LOCALAPPDATA%). Settings shows both; once Windows refused a write to the library
 * (Controlled folder access) the window says how to allow it. Null on Android, which has neither.
 */
interface StorageControls {
    /** Where the recipes and photos are: `Documents\Meal Planner`. */
    val libraryPath: String

    /** Where the app keeps its own data: `%LOCALAPPDATA%\Meal Planner`. */
    val appDataPath: String

    /**
     * What the window says about the library, or null: the Controlled folder access sentence once a real write was
     * refused, or why else a needed write failed. It stays for the session, until [checkAgain] finds it fixed.
     */
    val libraryNotice: StateFlow<String?>

    /**
     * Settings' Check again, only when the user presses it (P7-R10b: nothing is written only to look otherwise, as each
     * refused write is a Defender notification). Blocks briefly (one small write): call it off the UI thread.
     */
    fun checkAgain()

    /** True while [checkAgain] is writing; Check again is disabled meanwhile (M5). */
    val checking: StateFlow<Boolean>

    fun libraryExists(): Boolean

    fun appDataExists(): Boolean

    /** Opens the library in Explorer; never throws, false when it couldn't. */
    fun openLibrary(): Boolean

    /** Opens the app data folder in Explorer; never throws, false when it couldn't. */
    fun openAppData(): Boolean

    /** P7-R11: true only in the installed app, which can ask Windows to allow it through Controlled folder access. */
    val canAllowApp: Boolean get() = false

    /** True while [allowApp] waits on Windows; the button is disabled meanwhile. */
    val allowing: StateFlow<Boolean> get() = NOT_ALLOWING

    /** How the last [allowApp] went, when there is something to say (declined, failed, still blocked); else null. */
    val allowMessage: StateFlow<String?> get() = NO_ALLOW_MESSAGE

    /** "Allow Meal Planner (asks for admin)": UAC, then Check again. Blocks up to about 2 minutes: off the UI thread. */
    fun allowApp() {}
}

/** P7-R11: the button that allows the installed app through Controlled folder access, and the line beside it. */
const val ALLOW_APP_LABEL = "Allow Meal Planner (asks for admin)"
const val ALLOW_APP_EXPLAIN = "Windows will ask for permission once. This lets Meal Planner save to Documents\\Meal Planner."

private val NOT_ALLOWING: StateFlow<Boolean> = MutableStateFlow(false)
private val NO_ALLOW_MESSAGE: StateFlow<String?> = MutableStateFlow(null)

/** Settings' Folders panel as last looked at: the two paths, and which of them is there to open. */
data class FoldersState(val libraryPath: String, val appDataPath: String, val libraryThere: Boolean, val appDataThere: Boolean)
