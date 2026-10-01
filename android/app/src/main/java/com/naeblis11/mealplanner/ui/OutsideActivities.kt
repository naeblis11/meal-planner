package com.naeblis11.mealplanner.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.naeblis11.mealplanner.calendar.CalendarGateway

/** Shows the system dialog for the calendar permissions, once at a time; [onResult] gets true when both were granted. */
@Composable
fun rememberCalendarPermissionRequest(onResult: (Boolean) -> Unit): () -> Unit {
    val guard = rememberLaunchGuard()
    val latest by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        guard.done()
        latest(granted.isNotEmpty() && granted.values.all { it })
    }
    return remember(guard, launcher) { { guard.launch { launcher.launch(CalendarGateway.PERMISSIONS) } } }
}

/** Opens this app's page in the system settings (to allow a refused permission); [onReturn] runs when the user is back. */
@Composable
fun rememberAppSettingsOpener(onReturn: () -> Unit): () -> Unit {
    val packageName = LocalContext.current.packageName
    val guard = rememberLaunchGuard()
    val latest by rememberUpdatedState(onReturn)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        guard.done()
        latest()
    }
    return remember(guard, launcher, packageName) {
        {
            try {
                guard.launch {
                    launcher.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                }
            } catch (e: ActivityNotFoundException) {
                // No system settings app to open (not a real phone): nothing to do.
            }
        }
    }
}