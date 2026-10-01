package com.naeblis11.mealplanner.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.calendar.CalendarInfo
import com.naeblis11.mealplanner.calendar.CalendarMessages
import com.naeblis11.mealplanner.ui.AttentionBanner
import com.naeblis11.mealplanner.ui.theme.MealColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    backup: BackupState,
    calendar: CalendarSetupState,
    version: String,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onDismissBackup: () -> Unit,
    onSetUpCalendar: () -> Unit,
    onChooseCalendar: (CalendarInfo) -> Unit,
    onCancelChoosing: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Panel("Google Calendar") {
                CalendarSetup(calendar, onSetUpCalendar, onChooseCalendar, onCancelChoosing, onOpenAppSettings)
            }
            Panel("Backup") {
                Text("A zip of every recipe and photo. To move them to the Pi, unzip it into an empty Meal Planner data folder there and rescan.")
                when (backup) {
                    BackupState.Working -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        Text("Saving backup...")
                    }
                    is BackupState.Done -> { Text(backup.message, color = MealColors.AccentHover); TextButton(onClick = onDismissBackup) { Text("OK") } }
                    is BackupState.Failed -> { AttentionBanner(backup.message); TextButton(onClick = onDismissBackup) { Text("OK") } }
                    BackupState.Idle -> {}
                }
                Button(onClick = onExport, enabled = backup != BackupState.Working) { Text("Export backup") }
            }
            Panel("Import") {
                Text("Add recipes from a recipe file (.yaml), a Meal Master file (.mmf) or a backup (.zip). You review them before anything is saved.")
                OutlinedButton(onClick = onImport) { Text("Import recipes") }
            }
            Panel("About") {
                Text("Meal Planner $version")
                Text("Everything stays on this phone. Meal Planner has no internet permission and sends nothing anywhere.")
                Text("Meals you send to a calendar are written to that calendar on this phone; Android's own calendar sync takes them from there.")
                Text("Meal Planner is free software under the MIT License.")
                Text("It is built with Kotlin, Jetpack Compose, AndroidX, Room, kotlinx.serialization and SnakeYAML, all under the Apache License 2.0.")
                Text("It uses the phone's own fonts; none are bundled.")
            }
        }
    }
}

@Composable
private fun CalendarSetup(
    state: CalendarSetupState,
    onSetUp: () -> Unit,
    onChoose: (CalendarInfo) -> Unit,
    onCancel: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    Text("Send a week's meals to a calendar on this phone, such as your Google calendar. Android syncs that calendar; Meal Planner itself never goes online.")
    val chosen = state.chosen
    if (chosen != null) Text("Sending to ${chosen.name}", style = MaterialTheme.typography.titleMedium)
    if (state.permissionDenied) {
        AttentionBanner(CalendarMessages.PERMISSION_DENIED)
        OutlinedButton(onClick = onOpenAppSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open app settings") }
    }
    state.error?.let { AttentionBanner(it) }
    when (val picker = state.picker) {
        CalendarPicker.Closed ->
            if (chosen == null) {
                Button(onClick = onSetUp, modifier = Modifier.heightIn(min = 48.dp)) { Text("Set up calendar sending") }
            } else {
                OutlinedButton(onClick = onSetUp, modifier = Modifier.heightIn(min = 48.dp)) { Text("Choose another calendar") }
            }
        CalendarPicker.Loading -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator()
            Text("Looking for calendars...")
        }
        is CalendarPicker.Choosing -> {
            Text(if (picker.calendars.isEmpty()) CalendarMessages.NO_CALENDARS else "Choose the calendar to send meals to:")
            for (calendar in picker.calendars) {
                OutlinedButton(onClick = { onChoose(calendar) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(calendar.label) }
            }
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
        }
    }
}

@Composable
private fun Panel(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MealColors.Paper), border = CardDefaults.outlinedCardBorder(), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}