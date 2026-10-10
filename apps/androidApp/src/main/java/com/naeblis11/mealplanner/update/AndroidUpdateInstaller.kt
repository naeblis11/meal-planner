package com.naeblis11.mealplanner.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/**
 * The phone's installer (spec "Updates"): hands the verified APK to Android's package installer, which asks the user
 * and refuses an APK not signed with this app's key. The intent is explicit: it names a system app that handles the APK
 * type ([installerPackage]), so no other app installed on the phone can catch it and the read grant on the APK goes
 * nowhere else; without one, nothing is started or granted and Updates reports INSTALL_FAILED. The APK is shared
 * through the app's own FileProvider, which exposes only cache/updates (and the camera's capture). Android lets an app
 * ask to install only once the user has allowed "install unknown apps" for it ([canInstall]); [openInstallPermission]
 * opens that page for Meal Planner. [inForeground] says whether one of the app's activities is resumed (the app passes
 * its own count): Android blocks an activity started from the background without saying so, so the installer is started
 * only then (P8-PF11) and Updates otherwise waits for Install again. [uriFor] is the provider's content URI for a file (a
 * seam only because FileProvider can't map a path on a Windows test host, where its root check expects '/'); the
 * installer itself refuses any file outside the updates folder before asking it, so even a looser provider would never
 * be handed anything else.
 */
class AndroidUpdateInstaller(
    private val context: Context,
    private val inForeground: () -> Boolean,
    private val uriFor: (File) -> Uri = { FileProvider.getUriForFile(context, authority(context), it) },
) : UpdateInstaller {
    override val closesApp: Boolean = false

    override fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    override fun canStartNow(): Boolean = inForeground()

    override fun openInstallPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            System.err.println("Meal Planner: this phone has no page for allowing app installs.")
        }
    }

    /**
     * IllegalArgumentException for a file that isn't directly in cache/updates (nor would the provider share it);
     * IOException when the phone has no system package installer, before anything is started or granted.
     */
    override fun install(file: File) {
        require(file.canonicalFile.parentFile == folder(context).canonicalFile) { "Only the updates folder is shared." }
        val uri = uriFor(file)
        val target = installerPackage(uri) ?: throw IOException("This phone has no system package installer to open.")
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_TYPE)
            .setPackage(target)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            throw IOException("This phone has no package installer to open.")
        }
    }

    /**
     * The package of a system app (FLAG_SYSTEM) that opens [uri] as an APK, in the system's own order, or null. Only
     * system apps count: a downloaded app that claims APKs never gets the update or its read grant. The manifest's
     * <queries> entry lets Android 11+ show the installer to this lookup.
     */
    internal fun installerPackage(uri: Uri): String? {
        val probe = Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_TYPE)
        @Suppress("DEPRECATION")
        val handlers = context.packageManager.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
        return handlers.firstOrNull { info ->
            val app = info.activityInfo?.applicationInfo
            app != null && (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        }?.activityInfo?.packageName
    }

    companion object {
        const val APK_TYPE = "application/vnd.android.package-archive"

        /** Updates refuses any other name, and file_paths.xml shares only this folder of the cache. */
        const val FOLDER = Updates.DIR_NAME

        /** The app's FileProvider (AndroidManifest.xml), shared with the camera's capture. */
        fun authority(context: Context): String = "${context.packageName}.files"

        /** Where the phone's downloads go: its private cache, never Downloads or external storage. */
        fun folder(context: Context): File = File(context.cacheDir, FOLDER)
    }
}
