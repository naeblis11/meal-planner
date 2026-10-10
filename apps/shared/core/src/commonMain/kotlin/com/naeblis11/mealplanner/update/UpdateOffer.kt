package com.naeblis11.mealplanner.update

/** The copy that is running: the Windows app by its MSI version, the phone by its versionCode. */
sealed interface RunningApp {
    data class Desktop(val version: String) : RunningApp

    data class Android(val versionCode: Long, val versionName: String) : RunningApp
}

/** A newer version for this app: what Settings names ([version]), and the file to fetch from release [tag]. */
data class UpdateOffer(val version: String, val tag: String, val file: String, val size: Long, val sha256: String)

/**
 * This app's entry, when it is newer than [running] (spec: "its version is newer than the running one"): the desktop's
 * MSI version compared part by part, the phone's versionCode. Null when it isn't, or when the list has no entry for it.
 */
fun ReleaseManifest.offerFor(running: RunningApp): UpdateOffer? = when (running) {
    is RunningApp.Desktop -> desktop
        ?.takeIf { DesktopVersions.isNewer(it.version, running.version) }
        ?.let { UpdateOffer(it.version, tag, it.file, it.size, it.sha256) }
    is RunningApp.Android -> android
        ?.takeIf { it.versionCode > running.versionCode }
        ?.let { UpdateOffer(it.versionName, tag, it.file, it.size, it.sha256) }
}
