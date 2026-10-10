package com.naeblis11.mealplanner.desktop

import com.naeblis11.mealplanner.settings.StorageControls
import java.awt.Desktop
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings' view of the app's two folders (P7-R10): the library and the app data, from [app], and whether Windows keeps
 * the app from saving to the library. [opener] is Explorer (java.awt.Desktop); tests pass their own. A folder that
 * isn't there is never made here, only reported: an empty recipe library made in place of one that went away would
 * unindex every recipe at the next sync. [allow] (P7-R11) is the installed app's "Allow Meal Planner"; null (the
 * preview, the tests) offers none.
 */
class DesktopStorage(
    private val app: DesktopApp,
    private val allow: AllowApp? = null,
    private val opener: (File) -> Unit = { Desktop.getDesktop().open(it) },
) : StorageControls {
    override val libraryPath: String = app.libraryDir.absolutePath
    override val appDataPath: String = app.appDataDir.absolutePath
    override val libraryNotice: StateFlow<String?> = app.libraryNotice
    override val checking: StateFlow<Boolean> = app.libraryChecking

    // Looked at once: the launcher doesn't move while the app runs.
    override val canAllowApp: Boolean by lazy { allow?.available == true }

    private val _allowing = MutableStateFlow(false)
    override val allowing: StateFlow<Boolean> = _allowing.asStateFlow()

    private val _allowMessage = MutableStateFlow<String?>(null)
    override val allowMessage: StateFlow<String?> = _allowMessage.asStateFlow()

    // One at a time: a second press while Windows is still asking runs nothing.
    override fun allowApp() {
        val action = allow ?: return
        if (!_allowing.compareAndSet(false, true)) return
        try {
            // A message from an earlier press never stands beside this one.
            _allowMessage.value = null
            _allowMessage.value = action.run()
        } finally {
            _allowing.value = false
        }
    }

    // Once the notice clears, what the last Allow said goes with it.
    override fun checkAgain() {
        if (app.checkLibraryAgain() == null) _allowMessage.value = null
    }

    override fun libraryExists(): Boolean = app.libraryDir.isDirectory

    override fun appDataExists(): Boolean = app.appDataDir.isDirectory

    override fun openLibrary(): Boolean = open(app.libraryDir)

    override fun openAppData(): Boolean = open(app.appDataDir)

    // Called straight from a button, so it never throws.
    private fun open(dir: File): Boolean {
        if (!dir.isDirectory) return false
        return try {
            opener(dir)
            true
        } catch (e: Exception) {
            System.err.println("Meal Planner: opening a folder failed: ${e.javaClass.simpleName}")
            false
        }
    }
}
