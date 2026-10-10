package com.naeblis11.mealplanner.calendar

import android.Manifest

/**
 * The calendar permissions, asked for when the user sets up calendar sending. The app's only runtime permissions; its
 * others (INTERNET and REQUEST_INSTALL_PACKAGES, plan 8) aren't asked for at run time (ManifestPermissionsTest).
 */
object CalendarPermissions {
    val ALL: Array<String> = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
}
