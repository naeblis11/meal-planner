package com.naeblis11.mealplanner.ui

import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.naeblis11.mealplanner.app.AppContainer
import com.naeblis11.mealplanner.settings.BackupViewModel
import com.naeblis11.mealplanner.settings.CalendarSetupViewModel
import com.naeblis11.mealplanner.settings.SettingsScreen
import java.io.IOException
import java.time.LocalDate

/** Settings: Google Calendar, backup, import and About. */
fun NavGraphBuilder.settingsDestination(nav: NavController, container: AppContainer, backupVm: BackupViewModel, startImport: () -> Unit) {
    composable(Routes.SETTINGS) {
        val context = LocalContext.current
        // The application's resolver: a lambda a ViewModel keeps must never hold the Activity.
        val resolver = remember(context) { context.applicationContext.contentResolver }
        val backup by backupVm.state.collectAsStateWithLifecycle()
        val exportGuard = rememberLaunchGuard()
        val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            exportGuard.done()
            if (uri != null) {
                backupVm.export(
                    open = { resolver.openOutputStream(uri) ?: throw IOException("Could not open the backup file.") },
                    deleteDestination = { DocumentsContract.deleteDocument(resolver, uri) },
                )
            }
        }
        val calendarVm: CalendarSetupViewModel = viewModel { CalendarSetupViewModel(container.calendarGateway, container.calendarChoice) }
        val calendar by calendarVm.state.collectAsStateWithLifecycle()
        val requestCalendarPermission = rememberCalendarPermissionRequest(calendarVm::permissionResult)
        val openAppSettings = rememberAppSettingsOpener(calendarVm::returnedFromAppSettings)
        @Suppress("DEPRECATION")
        val version = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "" }
        SettingsScreen(
            backup = backup,
            calendar = calendar,
            version = version,
            onBack = dropUnlessResumed { nav.popBackStack() },
            onExport = { exportGuard.launch { saveBackup.launch("meal-planner-backup-${LocalDate.now()}.zip") } },
            onImport = startImport,
            onDismissBackup = backupVm::dismiss,
            // The system dialog only when it is needed; once allowed, straight to the list.
            onSetUpCalendar = { if (calendarVm.hasPermission()) calendarVm.loadCalendars() else requestCalendarPermission() },
            onChooseCalendar = calendarVm::choose,
            onCancelChoosing = calendarVm::cancelChoosing,
            onOpenAppSettings = openAppSettings,
        )
    }
}