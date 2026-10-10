package com.naeblis11.mealplanner.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naeblis11.mealplanner.folder.LIBRARY_BLOCKED_MESSAGE
import com.naeblis11.mealplanner.ui.theme.MealColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * P7-R11: "Allow Meal Planner (asks for admin)" and the line beside it, in the window's banner and Settings' Folders
 * panel. Shown only by the installed app ([StorageControls.canAllowApp]) and only while the library is blocked by
 * Controlled folder access (not for any other failure). Off while it runs; what it found is said under it.
 */
@Composable
fun AllowAppControls(storage: StorageControls) {
    val notice by storage.libraryNotice.collectAsStateWithLifecycle()
    if (!storage.canAllowApp || notice != LIBRARY_BLOCKED_MESSAGE) return
    val allowing by storage.allowing.collectAsStateWithLifecycle()
    val message by storage.allowMessage.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(
            onClick = { scope.launch(Dispatchers.IO) { storage.allowApp() } },
            enabled = !allowing,
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text(ALLOW_APP_LABEL) }
        Text(ALLOW_APP_EXPLAIN, color = MealColors.Muted)
        message?.let { Text(it, color = MealColors.DangerHover) }
    }
}
