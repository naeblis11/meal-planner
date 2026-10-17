package com.naeblis11.mealplanner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naeblis11.mealplanner.sync.PossibleDuplicate
import com.naeblis11.mealplanner.ui.theme.MealColors
import java.time.LocalDate

const val MERGE = "Merge"
const val MERGE_HELP = "Keep this phone's recipes, plan and lists and combine them with the PC's"
const val REPLACE = "Replace"
const val REPLACE_HELP = "Make this phone a copy of the PC. You'll save a backup of its recipes first."
const val FIRST_SYNC_CANCEL = "Cancel"
const val YOUR_PC = "your PC"
const val POSSIBLE_DUPLICATES = "Possible duplicates"
const val DUPLICATES_HELP =
    "These recipes have the same name on this phone and on the PC. Keep both, or keep the PC's: this phone's copy is " +
        "removed and its planned meals move to the PC's."
const val KEEP_BOTH = "Keep both"
const val KEEP_PCS = "Keep the PC's"
const val LATER = "Later"

/** Stage 4a: the same dialogs on a PC joining the main PC. */
const val DUPLICATES_HELP_PC =
    "These recipes have the same name on this PC and on the main PC. Keep both, or keep the main PC's: this PC's copy " +
        "is removed and its planned meals move to the main PC's."
const val KEEP_MAIN_PCS = "Keep the main PC's"

fun firstSyncTitle(pcName: String): String = "First sync with $pcName"

fun mergeHelpPc(pcName: String): String = "Keep this PC's recipes, plan and lists and combine them with $pcName's"

fun replaceHelpPc(pcName: String): String = "Make this PC a copy of $pcName. You'll save a backup first."

/**
 * The Sync button's dialogs and its outcome, once for the whole app (AppShell):
 * - The outcome shows in a snackbar, once; a snackbar cut short by a rotation shows again in the new activity.
 * - The first sync asks Merge or Replace. Replace first opens the backup's save picker, the same as Settings' Export
 *   backup (D5). A cancelled picker replaces nothing; nothing syncs until the backup is written.
 * - After a Merge, Possible duplicates lists recipes with the same name on both sides, each with Keep both or Keep the
 *   PC's (D5), until each is settled or put off with Later.
 */
@Composable
internal fun SyncDialogs(vm: SyncViewModel, snackbar: SnackbarHostState) {
    val state by vm.state.collectAsStateWithLifecycle()
    val guard = rememberLaunchGuard()
    val saveBackup = rememberSaveFile(guard, "application/zip") { target -> vm.backupChosen(target.open, target.delete) }
    val message = state.message
    if (message != null) {
        LaunchedEffect(message) {
            // Shown only once the snackbar ends by itself or is dismissed. An effect cancelled mid-snackbar (a rotation,
            // the activity going) leaves the outcome for the next composition to show: the user hasn't seen it out.
            snackbar.showSnackbar(message)
            vm.messageShown(message)
        }
    }
    if (state.askFirst) {
        FirstSyncDialog(
            pcName = state.status.pcName ?: YOUR_PC,
            forPc = vm.forPc,
            onMerge = vm::merge,
            onReplace = {
                vm.replaceChosen()
                saveBackup("meal-planner-backup-${LocalDate.now()}.zip")
            },
            onCancel = vm::cancelFirst,
        )
    }
    if (state.showDuplicates) {
        DuplicatesDialog(state.duplicates, onKeepBoth = vm::keepBoth, onKeepPcs = vm::keepPcs, onLater = vm::later, forPc = vm.forPc)
    }
}

/** "First sync with NAME": Merge (the default, first) or Replace, each with what it does; on a PC ([forPc]) in its words. */
@Composable
fun FirstSyncDialog(pcName: String, onMerge: () -> Unit, onReplace: () -> Unit, onCancel: () -> Unit, forPc: Boolean = false) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = MealColors.Paper,
        title = { Text(firstSyncTitle(pcName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onMerge, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(MERGE) }
                Text(if (forPc) mergeHelpPc(pcName) else MERGE_HELP, color = MealColors.Muted, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onReplace, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(REPLACE) }
                Text(if (forPc) replaceHelpPc(pcName) else REPLACE_HELP, color = MealColors.Muted, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(FIRST_SYNC_CANCEL) } },
    )
}

/**
 * "Possible duplicates" (D5): each recipe with the same name here and on the PC, with Keep both, or Keep the PC's, which
 * removes this phone's copy and moves its planned meals to the PC's (in red: the removal reaches the PC at the next sync).
 * Nothing is removed without a click.
 * Later puts the list off; it stays until each is settled.
 */
@Composable
fun DuplicatesDialog(
    duplicates: List<PossibleDuplicate>,
    onKeepBoth: (PossibleDuplicate) -> Unit,
    onKeepPcs: (PossibleDuplicate) -> Unit,
    onLater: () -> Unit,
    forPc: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onLater,
        containerColor = MealColors.Paper,
        title = { Text(POSSIBLE_DUPLICATES) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (forPc) DUPLICATES_HELP_PC else DUPLICATES_HELP)
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(duplicates, key = { "${it.thisDevicesId}-${it.pcsId}" }) { duplicate ->
                        Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(duplicate.name, style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { onKeepBoth(duplicate) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(KEEP_BOTH) }
                                OutlinedButton(
                                    onClick = { onKeepPcs(duplicate) },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MealColors.Danger),
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) { Text(if (forPc) KEEP_MAIN_PCS else KEEP_PCS) }
                            }
                        }
                        HorizontalDivider(color = MealColors.LineSoft)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onLater, modifier = Modifier.heightIn(min = 48.dp)) { Text(LATER) } },
    )
}
