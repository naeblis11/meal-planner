package com.naeblis11.mealplanner.sync

import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow

/** The phone's sync with no PC: records what the screens ask, pairs and leaves at once, and fails when told to. */
class FakeSyncControls(
    pcName: String? = "KITCHEN",
    lastSyncAt: Long? = null,
    @Volatile var firstChoice: Boolean = true,
    override val noun: SyncNoun = SyncNoun.PHONE,
) : SyncControls, JoinControls {
    override val status = MutableStateFlow(SyncStatus(known = true, pcName = pcName, lastSyncAt = lastSyncAt))
    override val syncing = MutableStateFlow(false)
    override val duplicates = MutableStateFlow<List<PossibleDuplicate>>(emptyList())
    override val outcome = MutableStateFlow<String?>(null)
    override val onWifi = MutableStateFlow(true)
    val calls = CopyOnWriteArrayList<String>()

    @Volatile
    var found: List<FoundPc> = emptyList()

    @Volatile
    var pairFailure: Exception? = null

    @Volatile
    var leaveFailure: Exception? = null

    override suspend fun needsFirstChoice(): Boolean = firstChoice

    override fun sync(first: FirstSync?) {
        calls += "sync ${first ?: "-"}"
    }

    override fun replace(open: () -> OutputStream, deleteBackup: () -> Unit) {
        calls += "replace"
    }

    override fun outcomeShown(text: String) {
        if (outcome.value == text) outcome.value = null
    }

    override fun keepBoth(duplicate: PossibleDuplicate) {
        calls += "keepBoth ${duplicate.name}"
        duplicates.value = duplicates.value - duplicate
    }

    override fun keepPcs(duplicate: PossibleDuplicate) {
        calls += "keepPcs ${duplicate.name}"
        duplicates.value = duplicates.value - duplicate
    }

    override fun recheckWifi() {
        calls += "wifi"
    }

    override suspend fun pairScanned(text: String) {
        pairFailure?.let { throw it }
        calls += "scanned $text"
        status.value = status.value.copy(pcName = "KITCHEN")
    }

    override suspend fun findPcs(): List<FoundPc> {
        calls += "find"
        return found
    }

    override suspend fun pairTyped(code: String, pc: FoundPc) {
        pairFailure?.let { throw it }
        calls += "typed $code ${pc.host}"
        status.value = status.value.copy(pcName = pc.name)
    }

    override suspend fun leave() {
        leaveFailure?.let { throw it }
        calls += "leave"
        status.value = status.value.copy(pcName = null)
    }
}
