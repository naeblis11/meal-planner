package com.naeblis11.mealplanner.update

import java.io.File
import kotlinx.coroutines.flow.StateFlow

enum class UpdatePhase { IDLE, CHECKING, DOWNLOADING, INSTALLING }

/**
 * The update check as Settings, the notice and the PC's tray see it. [offered] false (with [unavailableReason]) for a
 * copy that never checks. [message] is a check that failed, said quietly; [problem] is an install that didn't happen
 * (a download that didn't match, an installer that didn't start). [downloaded] counts bytes while DOWNLOADING.
 * [needsPermission]: Android wants "install unknown apps" allowed first. [foundAutomatically]: the launch check found
 * [offer], so the notice shows until [noticeDismissed]. [installClosesApp]: Install quits the app (the PC).
 * [waitingToInstall]: the offer is downloaded and verified, but the installer couldn't start because the app wasn't in
 * the foreground (Android, P8-PF11) or, on the PC, because a form or an import review had something unsaved (P8-F1);
 * the notice offers Install again, which uses the verified file at once.
 */
data class UpdateStatus(
    val offered: Boolean,
    val unavailableReason: String? = null,
    val automatic: Boolean = true,
    val lastChecked: Long? = null,
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val offer: UpdateOffer? = null,
    val upToDate: Boolean = false,
    val message: String? = null,
    val problem: String? = null,
    val downloaded: Long = 0,
    val needsPermission: Boolean = false,
    val foundAutomatically: Boolean = false,
    val noticeDismissed: Boolean = false,
    val installClosesApp: Boolean = false,
    val waitingToInstall: Boolean = false,
) {
    /**
     * The one-line notice above every screen: an update the launch check found, or one downloaded while the app was in
     * the background and waiting for Install, until opened or put away.
     */
    val showsNotice: Boolean
        get() = offer != null && (foundAutomatically || waitingToInstall) && !noticeDismissed && phase == UpdatePhase.IDLE
}

/** What the screens can ask of the update check (Updates); Settings and the notice use nothing else. */
interface UpdateControls {
    val state: StateFlow<UpdateStatus>

    /** Settings' Check for updates: looks now, whatever the day's limit. Returns at once. */
    fun checkNow()

    /** Settings' Automatically switch: whether launches check. Remembered. */
    fun setAutomatic(on: Boolean)

    /** Install the offered update: download, verify, hand to the system. Returns at once. */
    fun install()

    /** Android: open the system page that allows installing unknown apps for Meal Planner. Nothing on the PC. */
    fun openInstallPermission()

    /** The notice's Not now. */
    fun dismissNotice()
}

/**
 * The platform's installer, behind a seam so no test starts msiexec or Android's package installer (spec "Updates").
 * The PC's is MsiInstaller (msiexec, then quit); the phone's is AndroidUpdateInstaller (the package installer).
 */
interface UpdateInstaller {
    /** Whether a started install quits this app (the PC): Settings asks first. */
    val closesApp: Boolean

    /** Whether the system lets this app install now (Android's "install unknown apps"); always true on the PC. */
    fun canInstall(): Boolean

    /**
     * Whether the installer can be started right now: Android blocks an activity started from the background without
     * saying so, so the phone's answers true only while the app is in the foreground (P8-PF11). Always true on the PC.
     * Abstract on purpose: every installer says which it is.
     */
    fun canStartNow(): Boolean

    /** Opens the system page that allows it; nothing where there is none. */
    fun openInstallPermission()

    /**
     * Hands the verified [file] to the system's installer and returns once it has started; throws when it couldn't.
     * Updates checks the file's size and SHA-256 against the signed list and then hands it over by path, so whoever can
     * write to the cache folder between the two could swap it: that folder must be writable only by this user (the
     * PC: the per-user app data folder) or only by this app (Android: its private cache).
     */
    fun install(file: File)
}
