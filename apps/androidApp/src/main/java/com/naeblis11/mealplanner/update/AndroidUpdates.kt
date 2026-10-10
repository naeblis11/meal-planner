package com.naeblis11.mealplanner.update

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.core.content.pm.PackageInfoCompat
import com.naeblis11.mealplanner.app.SharedPreferencesStore
import java.security.PublicKey

/**
 * The phone's update check (plan 8): GitHub through ReleaseHttp's defaults and ReleaseEndpoints.GITHUB (never from
 * configuration), the built-in key through Updates.releaseKey() (a key problem, even an Error from the signature
 * code's start-up, is "no update", never verified), the package installer, and the app's private cache/updates.
 * Updates does its network work on its own IO scope, never on the main thread.
 */
object AndroidUpdates {
    const val RELEASE_PACKAGE = "com.naeblis11.mealplanner"
    const val PREFS = "updates"
    const val DEBUG_BUILD =
        "Updates come to the released Meal Planner. This is a debug build, which installs beside it and can't be updated by it."

    /** Only the release build checks: a debug build (".debug", debuggable) can't be updated by the release APK. */
    fun isRelease(context: Context): Boolean =
        context.packageName == RELEASE_PACKAGE && (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0

    /** [inForeground]: whether one of the app's activities is resumed (MealPlannerApplication passes its own count). */
    fun create(
        context: Context,
        inForeground: () -> Boolean,
        publicKey: PublicKey? = Updates.releaseKey(),
        release: Boolean = isRelease(context),
    ): Updates = build(
        context,
        publicKey,
        release,
        http = ReleaseHttp(),
        endpoints = ReleaseEndpoints.GITHUB,
        installer = AndroidUpdateInstaller(context.applicationContext, inForeground),
    )

    /**
     * [create] with the client, addresses and installer as parameters, for the tests' fake release server on 127.0.0.1
     * and a provider stand-in (AndroidUpdateInstaller's uriFor).
     */
    internal fun build(
        context: Context,
        publicKey: PublicKey?,
        release: Boolean,
        http: ReleaseHttp,
        endpoints: ReleaseEndpoints,
        installer: UpdateInstaller,
    ): Updates {
        val app = context.applicationContext
        @Suppress("DEPRECATION")
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        return Updates(
            running = RunningApp.Android(PackageInfoCompat.getLongVersionCode(info), info.versionName.orEmpty()),
            publicKey = publicKey,
            installer = installer,
            settings = SharedPreferencesStore(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)),
            dir = AndroidUpdateInstaller.folder(app),
            http = http,
            endpoints = endpoints,
            unavailable = if (release) null else DEBUG_BUILD,
        )
    }
}
