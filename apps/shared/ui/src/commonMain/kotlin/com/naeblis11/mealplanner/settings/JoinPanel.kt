package com.naeblis11.mealplanner.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.naeblis11.mealplanner.sync.FoundPc
import com.naeblis11.mealplanner.ui.AttentionBanner
import com.naeblis11.mealplanner.ui.theme.MealColors

const val JOIN_PANEL = "Join a PC"
const val JOIN_INTRO =
    "Sync this phone's recipes, meal plan, pantry and shopping list with the Meal Planner PC at home. On the PC, open " +
        "Settings > Phones and PCs and press Pair a device."
const val SCAN_CODE = "Scan code"
const val TYPE_CODE = "Type code"
const val WIFI_FIRST = "Connect to your home Wi-Fi first"
const val OPEN_WIFI_SETTINGS = "Open Wi-Fi settings"
const val SCAN_UNAVAILABLE = "Scanning isn't available on this phone. Type the code instead."
const val TYPE_SAFER = "Scanning is safer: typing works only on a home network you trust."
const val TYPE_CODE_TITLE = "Type the code"
const val CODE_LABEL = "6-digit code"
const val PAIR = "Pair"
const val CHOOSE_PC = "Which PC?"
const val LEAVE = "Leave"
const val JOIN_CANCEL = "Cancel"
const val FINDING_PC = "Looking for your PC..."
const val PAIRING_NOW = "Pairing..."
const val LEAVING = "Leaving..."
const val ABOUT_PHONE_SCANNER =
    "Scanning a pairing code uses Google's code scanner, which runs in Google Play services; Meal Planner itself has no " +
        "camera permission. Meal Planner removes the scanner library's own upload pipeline; Google Play services, which " +
        "runs the scanner, may record that it was used."

fun pairedWith(pcName: String): String = "Paired with $pcName"

fun leaveQuestion(pcName: String): String = "Leave $pcName? This phone keeps its data but stops syncing."

/**
 * Settings' "Join a PC" (LAN sync stage 2c, D3):
 * - Paired: "Paired with NAME" and Leave, which asks first.
 * - Not paired and off Wi-Fi: "Connect to your home Wi-Fi first" with Open Wi-Fi settings.
 * - Otherwise: Scan code (recommended) and Type code.
 * Scan code, Type code and Leave are off while a sync (or a Leave) runs. Work under way and a failure show below, or
 * in the Type code dialog while it is open.
 */
@Composable
fun JoinSetting(state: JoinState, actions: JoinActions) {
    val pcName = state.status.pcName
    Text(JOIN_INTRO)
    when {
        !state.status.known -> Unit
        pcName != null -> {
            Text(pairedWith(pcName), style = MaterialTheme.typography.titleMedium)
            // Red: Leave removes the pairing (DESIGN.md's danger voice).
            OutlinedButton(
                onClick = actions.askLeave,
                enabled = state.canStart,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MealColors.Danger),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(LEAVE) }
        }
        !state.onWifi -> {
            AttentionBanner(WIFI_FIRST)
            Button(onClick = actions.openWifiSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text(OPEN_WIFI_SETTINGS) }
        }
        else -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions.scan, enabled = state.canStart, modifier = Modifier.heightIn(min = 48.dp)) { Text(SCAN_CODE) }
                OutlinedButton(onClick = actions.openTyping, enabled = state.canStart, modifier = Modifier.heightIn(min = 48.dp)) { Text(TYPE_CODE) }
            }
            if (state.scanUnavailable) Text(SCAN_UNAVAILABLE, color = MealColors.Muted)
        }
    }
    if (!state.typing) {
        state.busy?.let { BusyLine(it) }
        state.error?.let { AttentionBanner(it) }
    }
    if (state.typing) TypeCodeDialog(state, actions)
    if (state.leaveAsk && pcName != null) {
        AlertDialog(
            onDismissRequest = actions.cancelLeave,
            containerColor = MealColors.Paper,
            text = { Text(leaveQuestion(pcName)) },
            confirmButton = {
                TextButton(
                    onClick = actions.confirmLeave,
                    enabled = state.canStart,
                    colors = ButtonDefaults.textButtonColors(contentColor = MealColors.Danger),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(LEAVE) }
            },
            dismissButton = { TextButton(onClick = actions.cancelLeave, modifier = Modifier.heightIn(min = 48.dp)) { Text(JOIN_CANCEL) } },
        )
    }
}

/** Type code: six digits, the note that scanning is safer, then what was found. */
@Composable
private fun TypeCodeDialog(state: JoinState, actions: JoinActions) {
    // Plain remember: the code never goes into saved state; a half-typed code is lost on rotation.
    var code by remember { mutableStateOf("") }
    val ready = code.length == 6 && state.canStart && state.choices.isEmpty()
    AlertDialog(
        onDismissRequest = actions.cancelTyping,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(16.dp).fillMaxWidth(),
        containerColor = MealColors.Paper,
        title = { Text(TYPE_CODE_TITLE) },
        text = {
            Column(Modifier.widthIn(min = 280.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { typed -> code = typed.filter { it in '0'..'9' }.take(6) },
                    label = { Text(CODE_LABEL) },
                    singleLine = true,
                    enabled = state.busy == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(TYPE_SAFER, color = MealColors.Muted, style = MaterialTheme.typography.bodyMedium)
                state.busy?.let { BusyLine(it) }
                state.error?.let { AttentionBanner(it) }
                if (state.choices.isNotEmpty()) {
                    Text(CHOOSE_PC)
                    for (pc in state.choices) {
                        OutlinedButton(
                            onClick = { actions.choose(pc) },
                            enabled = state.canStart,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text(pcLabel(pc, state.choices)) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { actions.submitCode(code) }, enabled = ready, modifier = Modifier.heightIn(min = 48.dp)) { Text(PAIR) }
        },
        dismissButton = {
            TextButton(onClick = actions.cancelTyping, enabled = state.busy == null, modifier = Modifier.heightIn(min = 48.dp)) { Text(JOIN_CANCEL) }
        },
    )
}

// A PC's name, with its address when two found share it.
private fun pcLabel(pc: FoundPc, all: List<FoundPc>): String =
    if (all.count { it.name == pc.name } > 1) "${pc.name} (${pc.host})" else pc.name

@Composable
private fun BusyLine(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        Text(text)
    }
}
