package com.naeblis11.mealplanner.sync

import java.io.OutputStream
import kotlinx.coroutines.flow.StateFlow

/**
 * Where the phone stands with its PC. [known] is true once the pairing has been read; until then the screens show
 * neither the Sync button nor the first-run hint.
 */
data class SyncStatus(val known: Boolean = false, val pcName: String? = null, val lastSyncAt: Long? = null) {
    val paired: Boolean get() = pcName != null
}

/**
 * The phone's Sync button (LAN sync stage 2c, D4, D5) as the screens see it. PhoneSyncControls is one per process, so a
 * sync outlives the screen (and the rotation) that started it. Null on the desktop.
 */
interface SyncControls {
    val status: StateFlow<SyncStatus>

    /** A sync, Replace's backup or a Leave is under way; one at a time, app-wide. */
    val syncing: StateFlow<Boolean>

    /** The last Merge's possible duplicates still to settle; kept for this process (D5). */
    val duplicates: StateFlow<List<PossibleDuplicate>>

    /** The last sync's outcome, to show once ([outcomeShown] puts it away). */
    val outcome: StateFlow<String?>

    /** Whether the next sync is the first with this PC, which asks Merge or Replace. */
    suspend fun needsFirstChoice(): Boolean

    /** Starts a sync in the app's scope; ignored while one runs. */
    fun sync(first: FirstSync? = null)

    /**
     * Replace (D5): writes the backup to [open] first, and only once it is written syncs with FirstSync.REPLACE. A
     * failed backup is deleted with [deleteBackup], and nothing is synced. Ignored while a sync runs.
     */
    fun replace(open: () -> OutputStream, deleteBackup: () -> Unit)

    /** [text] has been shown; a newer outcome stays. */
    fun outcomeShown(text: String)

    /** Keep both: off the list; nothing changes. */
    fun keepBoth(duplicate: PossibleDuplicate)

    /** Keep the PC's: this phone's copy is deleted, and its deletion reaches the PC at the next sync. */
    fun keepPcs(duplicate: PossibleDuplicate)
}

/** Settings' "Join a PC" (LAN sync stage 2c, D3) as the screen sees it. Null on the desktop. */
interface JoinControls {
    val status: StateFlow<SyncStatus>

    /** A sync (or a Leave) is under way: Leave is not offered meanwhile, and [leave] refuses. */
    val syncing: StateFlow<Boolean>

    /** Whether the phone is on Wi-Fi, as [recheckWifi] last read it. */
    val onWifi: StateFlow<Boolean>

    fun recheckWifi()

    /**
     * Pairs with the PC in a scanned code. Throws IllegalArgumentException for a code that isn't Meal Planner's,
     * SyncHttpException otherwise; SyncMessages.pairingFailed says which in words.
     */
    suspend fun pairScanned(text: String)

    /** The Meal Planner PCs on this Wi-Fi, after about PhoneSync.FIND_MILLIS. */
    suspend fun findPcs(): List<FoundPc>

    /** Pairs with [pc] by the typed [code]. */
    suspend fun pairTyped(code: String, pc: FoundPc)

    /**
     * Tells the PC (best effort), forgets the pairing and the sync marks; keeps the data. IllegalStateException while
     * [syncing].
     */
    suspend fun leave()
}
