package com.naeblis11.mealplanner.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naeblis11.mealplanner.sync.FirstSync
import com.naeblis11.mealplanner.sync.PossibleDuplicate
import com.naeblis11.mealplanner.sync.SyncControls
import com.naeblis11.mealplanner.sync.SyncNoun
import com.naeblis11.mealplanner.sync.SyncStatus
import java.io.OutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Sync button as shown. [askFirst] is the first sync's question; [showDuplicates] is the Possible duplicates
 * dialog, unless put off with Later; [message] is the outcome to show once.
 */
data class SyncBarState(
    val status: SyncStatus = SyncStatus(),
    val syncing: Boolean = false,
    val askFirst: Boolean = false,
    val duplicates: List<PossibleDuplicate> = emptyList(),
    val showDuplicates: Boolean = false,
    val message: String? = null,
)

/**
 * The phone's Sync button (LAN sync stage 2c, D4, D5), one per activity (AppShell makes it; it survives a rotation), seen
 * by all four top bars through LocalSyncControls. The sync itself runs in [controls]' app-wide scope.
 * - A tap starts a sync, or, the first time with this PC, asks Merge or Replace.
 * - Replace waits for the backup's file to be chosen ([backupChosen]); a cancelled picker leaves everything as it was.
 */
class SyncViewModel(private val controls: SyncControls) : ViewModel() {
    private data class Local(val askFirst: Boolean = false, val asking: Boolean = false, val later: Boolean = false)

    private val local = MutableStateFlow(Local())

    /** Stage 4a: true on a PC, whose dialogs say "this PC" and "the main PC". */
    val forPc: Boolean = controls.noun == SyncNoun.PC

    val state: StateFlow<SyncBarState> =
        combine(controls.status, controls.syncing, controls.duplicates, controls.outcome, local) { status, syncing, duplicates, outcome, mine ->
            SyncBarState(status, syncing, mine.askFirst, duplicates, duplicates.isNotEmpty() && !mine.later, outcome)
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            SyncBarState(
                controls.status.value, controls.syncing.value, false, controls.duplicates.value,
                controls.duplicates.value.isNotEmpty(), controls.outcome.value,
            ),
        )

    /** The Sync button. Ignored unless paired, while a sync runs, or while the question is open. */
    fun tap() {
        val mine = local.value
        if (!controls.status.value.paired || controls.syncing.value || mine.asking || mine.askFirst) return
        local.update { it.copy(asking = true) }
        viewModelScope.launch {
            try {
                if (controls.needsFirstChoice()) local.update { it.copy(askFirst = true) } else controls.sync()
            } finally {
                local.update { it.copy(asking = false) }
            }
        }
    }

    /** Merge, the first sync's default. A new Merge brings back a duplicates list put off with Later. */
    fun merge() {
        local.update { it.copy(askFirst = false, later = false) }
        controls.sync(FirstSync.MERGE)
    }

    /** Replace was chosen: the question closes and the screen opens the backup's save picker. */
    fun replaceChosen() = local.update { it.copy(askFirst = false) }

    /** The backup's file was chosen: the backup is written, and only then does Replace run. */
    fun backupChosen(open: () -> OutputStream, deleteBackup: () -> Unit) = controls.replace(open, deleteBackup)

    fun cancelFirst() = local.update { it.copy(askFirst = false) }

    fun messageShown(text: String) = controls.outcomeShown(text)

    fun keepBoth(duplicate: PossibleDuplicate) = controls.keepBoth(duplicate)

    fun keepPcs(duplicate: PossibleDuplicate) = controls.keepPcs(duplicate)

    /** Later: the duplicates dialog goes until the next activity or the next Merge; the list stays in [controls]. */
    fun later() = local.update { it.copy(later = true) }
}
