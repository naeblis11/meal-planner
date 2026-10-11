package com.naeblis11.mealplanner.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naeblis11.mealplanner.sync.FoundPc
import com.naeblis11.mealplanner.sync.JoinControls
import com.naeblis11.mealplanner.sync.SyncMessages
import com.naeblis11.mealplanner.sync.SyncStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Settings' "Join a PC" as shown. [busy] is what is under way ("Pairing..."); [typing] is the Type code dialog;
 * [choices] are the PCs found for a typed code when there were several; [leaveAsk] is Leave's question; [syncing] is a
 * sync (or a Leave) under way app-wide, while which Scan code, Type code and Leave are off.
 */
data class JoinState(
    val status: SyncStatus = SyncStatus(),
    val onWifi: Boolean = true,
    val busy: String? = null,
    val error: String? = null,
    val typing: Boolean = false,
    val choices: List<FoundPc> = emptyList(),
    val leaveAsk: Boolean = false,
    val scanUnavailable: Boolean = false,
    val syncing: Boolean = false,
) {
    /** Scan code, Type code, Pair and Leave may start something. */
    val canStart: Boolean get() = busy == null && !syncing
}

/** What the section can ask for; all nothing by default (screen tests). */
class JoinActions(
    val scan: () -> Unit = {},
    val openTyping: () -> Unit = {},
    val cancelTyping: () -> Unit = {},
    val submitCode: (String) -> Unit = {},
    val choose: (FoundPc) -> Unit = {},
    val askLeave: () -> Unit = {},
    val confirmLeave: () -> Unit = {},
    val cancelLeave: () -> Unit = {},
    val openWifiSettings: () -> Unit = {},
)

/**
 * Settings' "Join a PC" (LAN sync stage 2c, D3):
 * - A scanned code pairs at once.
 * - A typed code first looks for PCs on this Wi-Fi. One is paired with at once; several are offered by name; none is
 *   "Couldn't find your PC on this Wi-Fi.", with the dialog kept open to try again.
 * - Leave asks first.
 * - Nothing starts while a sync (or a Leave) holds the controls' `syncing`.
 * The work itself runs in the controls' own scope, so leaving Settings mid-pairing keeps the pairing.
 */
class JoinViewModel(private val controls: JoinControls) : ViewModel() {
    private data class Local(
        val busy: String? = null,
        val error: String? = null,
        val typing: Boolean = false,
        val choices: List<FoundPc> = emptyList(),
        val leaveAsk: Boolean = false,
        val scanUnavailable: Boolean = false,
    )

    private val local = MutableStateFlow(Local())

    // The typed code: kept only from Pair until the pairing ends or while the user chooses among several PCs; forgotten
    // after any pairing, when no PC is found and when the dialog is cancelled.
    private var code = ""

    val state: StateFlow<JoinState> = combine(controls.status, controls.onWifi, controls.syncing, local) { status, wifi, syncing, mine ->
        JoinState(status, wifi, mine.busy, mine.error, mine.typing, mine.choices, mine.leaveAsk, mine.scanUnavailable, syncing)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        JoinState(controls.status.value, controls.onWifi.value, syncing = controls.syncing.value),
    )

    // Something of this section's under way, or the app's sync: nothing new starts.
    private val held: Boolean get() = local.value.busy != null || controls.syncing.value

    fun recheckWifi() = controls.recheckWifi()

    /** The scanner's text. */
    fun scanned(text: String) {
        if (held) return
        pair(scanned = true) { controls.pairScanned(text) }
    }

    fun scanUnavailable() = local.update { it.copy(scanUnavailable = true) }

    fun openTyping() = local.update { it.copy(typing = true, error = null, choices = emptyList()) }

    fun cancelTyping() {
        if (local.value.busy != null) return
        code = ""
        local.update { it.copy(typing = false, error = null, choices = emptyList()) }
    }

    /** Pair, with [typed] in the field; ignored unless it is six digits. */
    fun submitCode(typed: String) {
        if (held || !CODE.matches(typed)) return
        code = typed
        local.update { it.copy(busy = FINDING_PC, error = null, choices = emptyList()) }
        viewModelScope.launch {
            val found = try {
                controls.findPcs()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            when (found.size) {
                0 -> {
                    code = ""
                    local.update { it.copy(busy = null, error = SyncMessages.NO_PC_FOUND) }
                }
                1 -> pairTyped(found.single())
                else -> local.update { it.copy(busy = null, choices = found) }
            }
        }
    }

    /** One of several PCs found for the typed code; nothing once that list is gone (the code with it). */
    fun choose(pc: FoundPc) {
        if (held || code.isEmpty() || pc !in local.value.choices) return
        local.update { it.copy(choices = emptyList()) }
        pairTyped(pc)
    }

    private fun pairTyped(pc: FoundPc) {
        val typed = code
        pair(scanned = false) { controls.pairTyped(typed, pc) }
    }

    private fun pair(scanned: Boolean, block: suspend () -> Unit) {
        local.update { it.copy(busy = PAIRING_NOW, error = null) }
        viewModelScope.launch {
            try {
                block()
                local.update { it.copy(typing = false, scanUnavailable = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                local.update { it.copy(error = SyncMessages.pairingFailed(e, scanned)) }
            } finally {
                code = ""
                local.update { it.copy(busy = null) }
            }
        }
    }

    fun askLeave() = local.update { it.copy(leaveAsk = true, error = null) }

    fun cancelLeave() = local.update { it.copy(leaveAsk = false) }

    /** Leave, confirmed. */
    fun confirmLeave() {
        if (held) return
        local.update { it.copy(leaveAsk = false, busy = LEAVING, error = null) }
        viewModelScope.launch {
            try {
                controls.leave()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                local.update { it.copy(error = SyncMessages.LEAVE_FAILED) }
            } finally {
                local.update { it.copy(busy = null) }
            }
        }
    }

    private companion object {
        val CODE = Regex("[0-9]{6}")
    }
}
