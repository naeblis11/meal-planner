package com.naeblis11.mealplanner.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.ui.AttentionBanner
import com.naeblis11.mealplanner.ui.theme.MealColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

const val DEVICES_PANEL = "Phones and PCs"
const val DEVICES_INTRO = "Phones and other PCs that sync with this PC over your home network."
const val PAIR_DEVICE = "Pair a device"
const val PAIRING_STARTING = "Starting..."
const val SCAN_HELP = "Scan this code from Meal Planner on your phone. If it can't scan, type the code below instead."
const val PAIR_ON_PHONE = "Open on your phone: Settings > Join a PC"
const val NO_QR = "This PC isn't on a home network just now, so there is no code to scan. Type the code on the phone instead."
const val NO_DEVICES_YET = "No phones or PCs are paired yet."
const val NEVER_SYNCED = "Never synced"
const val REMOVE = "Remove"
const val REMOVE_DEVICE = "Remove device"
const val KEEP_DEVICE = "Keep it"
const val PAIRING_FAILED = "Couldn't start pairing. Try again in a moment."
const val REMOVE_FAILED = "Couldn't remove it. Try again in a moment."
const val SYNC_FAILED = "Sync couldn't start on this PC, so devices can't sync. The log in the app data folder's .cache says why."
const val QR_DESCRIPTION = "Pairing code to scan"

/** "123 456": the code in two groups, easier to read out and type. */
fun groupedCode(code: String): String = if (code.length == 6) "${code.take(3)} ${code.drop(3)}" else code

/** "Closes in m:ss", rounded up to the second, never below 0:00. */
fun closesIn(millisLeft: Long): String {
    val seconds = (millisLeft.coerceAtLeast(0L) + 999) / 1000
    return "Closes in ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

fun kindLabel(kind: String): String = if (kind == "pc") "PC" else "Phone"

fun lastSyncedText(at: Long?, zone: ZoneId = ZoneId.systemDefault()): String =
    if (at == null) NEVER_SYNCED else "Last synced " + DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH).format(Instant.ofEpochMilli(at).atZone(zone))

/** "Phone \u00B7 Last synced ..." (a middle dot between), the line under a device's name. */
fun deviceDetail(row: DeviceRow, zone: ZoneId = ZoneId.systemDefault()): String = "${kindLabel(row.kind)} \u00B7 ${lastSyncedText(row.lastSync, zone)}"

fun removeDeviceQuestion(name: String): String = "Remove $name? It keeps its data but stops syncing until you pair it again."

/**
 * Settings' "Phones and PCs" (LAN sync stage 2b, D10): Pair a device (off while this PC yields, [yielding] saying why),
 * the open window's QR code, its code in large type, how to join from the phone and its countdown, then the paired
 * devices with Remove, which asks first. A port in use and a remade certificate are said at the top.
 */
@Composable
fun DevicesSetting(state: DevicesState, actions: DeviceActions, yielding: String?) {
    Text(DEVICES_INTRO)
    state.certificateNotice?.let { AttentionBanner(it) }
    when (state.status.state) {
        SyncServerState.PORT_IN_USE -> AttentionBanner(syncPortInUseNotice(state.status.port))
        SyncServerState.FAILED -> AttentionBanner(SYNC_FAILED)
        else -> Unit
    }
    val offer = state.offer
    if (offer == null) {
        if (yielding != null) Text(yielding, color = MealColors.Muted, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = actions.pair, enabled = yielding == null && !state.opening, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (state.opening) PAIRING_STARTING else PAIR_DEVICE)
        }
    } else {
        PairingPanel(offer, state.now, actions.cancelPairing)
    }
    state.error?.let { AttentionBanner(it) }
    if (state.devices.isEmpty()) {
        Text(NO_DEVICES_YET, color = MealColors.Muted)
    } else {
        for (row in state.devices) DeviceLine(row, onRemove = { actions.askRemove(row) })
    }
    state.removeAsk?.let { row ->
        AlertDialog(
            onDismissRequest = actions.cancelRemove,
            text = { Text(removeDeviceQuestion(row.name)) },
            confirmButton = { TextButton(onClick = actions.confirmRemove) { Text(REMOVE_DEVICE, color = MealColors.Danger) } },
            dismissButton = { TextButton(onClick = actions.cancelRemove) { Text(KEEP_DEVICE) } },
        )
    }
}

@Composable
private fun PairingPanel(offer: PairingView, now: Long, onCancel: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val qr = offer.qr
        if (qr != null) {
            Text(SCAN_HELP)
            QrImage(qr, Modifier.size(QR_SIZE))
        } else {
            Text(NO_QR, color = MealColors.Muted)
        }
        Text(groupedCode(offer.code), style = MaterialTheme.typography.displaySmall, fontFamily = FontFamily.Monospace)
        Text(PAIR_ON_PHONE)
        Text(closesIn(offer.expiresAt - now), color = MealColors.Muted)
        TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
    }
}

@Composable
private fun DeviceLine(row: DeviceRow, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Column(Modifier.weight(1f)) {
            Text(row.name, style = MaterialTheme.typography.titleMedium)
            Text(deviceDetail(row), color = MealColors.Muted, style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = onRemove, modifier = Modifier.heightIn(min = 48.dp)) { Text(REMOVE, color = MealColors.Danger) }
    }
}

/** A QR code drawn module by module: ink on paper, with the four-module quiet zone a scanner needs. */
@Composable
fun QrImage(qr: QrModules, modifier: Modifier = Modifier) {
    Canvas(modifier.semantics { contentDescription = QR_DESCRIPTION }) {
        val cells = qr.size + 2 * QUIET_ZONE
        val cell = size.minDimension / cells
        drawRect(MealColors.Paper, size = Size(cell * cells, cell * cells))
        for (y in 0 until qr.size) {
            for (x in 0 until qr.size) {
                if (qr.isDark(x, y)) {
                    drawRect(MealColors.Ink, topLeft = Offset((x + QUIET_ZONE) * cell, (y + QUIET_ZONE) * cell), size = Size(cell, cell))
                }
            }
        }
    }
}

private const val QUIET_ZONE = 4
private val QR_SIZE = 240.dp
