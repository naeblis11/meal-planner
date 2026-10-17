package com.naeblis11.mealplanner.sync

import com.naeblis11.mealplanner.backup.BackupExporter
import com.naeblis11.mealplanner.data.RecipeRepository
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The phone's LAN sync for its screens (stage 2c): the Sync button ([SyncControls]) and Settings' Join a PC
 * ([JoinControls]), over [engine]. There is exactly one per process, over the process's one PhoneSync, held app-scoped
 * (a second would keep its own [syncing] and outcome, and let two syncs be asked for at once). Its work runs in its own
 * scope on [io]: a sync, a pairing or a Leave started on a screen finishes even if the screen, or the activity, goes.
 * One sync at a time ([syncing]), and no Leave meanwhile; its outcome is said once, in SyncMessages' sentences. A pairing
 * doesn't hold [syncing] (Settings shows its own progress), but no sync, Replace or Leave starts while one runs: a sync
 * queued behind it would run against the marks the new pairing clears, with the old pairing's first-sync answer. A
 * pairing asked for while a sync runs waits for it. PhoneSync's lock is unchanged; this is the screens' order. [wifi]
 * is read again on [recheckWifi]. Nothing here logs a key, a code or the pairing.
 */
class PhoneSyncControls(
    private val engine: PhoneSync,
    private val recipes: RecipeRepository,
    private val imagesDir: File,
    private val wifi: () -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** The phone's sentences, or a client PC's (stage 4a). */
    override val noun: SyncNoun = SyncNoun.PHONE,
) : SyncControls, JoinControls {
    private val scope = CoroutineScope(SupervisorJob() + io)

    private val _status = MutableStateFlow(SyncStatus())
    override val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    override val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _duplicates = MutableStateFlow<List<PossibleDuplicate>>(emptyList())
    override val duplicates: StateFlow<List<PossibleDuplicate>> = _duplicates.asStateFlow()

    private val _outcome = MutableStateFlow<String?>(null)
    override val outcome: StateFlow<String?> = _outcome.asStateFlow()

    // Pairings under way. Set before a pairing waits for [syncing] to be free, and read after a sync, Replace or Leave has
    // taken [syncing] (startWork): whichever comes second sees the other, so the two never overlap.
    private val pairings = MutableStateFlow(0)

    private val _onWifi = MutableStateFlow(readWifi())
    override val onWifi: StateFlow<Boolean> = _onWifi.asStateFlow()

    init {
        scope.launch { refresh() }
    }

    override suspend fun needsFirstChoice(): Boolean = withContext(io) {
        try {
            !engine.hasSynced()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    // Takes [syncing] for a sync, Replace or Leave; false while one runs or a pairing does.
    private fun startWork(): Boolean {
        if (!_syncing.compareAndSet(false, true)) return false
        if (pairings.value > 0) {
            _syncing.value = false
            return false
        }
        return true
    }

    override fun sync(first: FirstSync?) {
        if (!startWork()) return
        scope.launch { run(first) }
    }

    override fun replace(open: () -> OutputStream, deleteBackup: () -> Unit) {
        if (!startWork()) {
            // The save picker has already made the file: a refused Replace mustn't leave an empty "backup" behind.
            runCatching { deleteBackup() }
            _outcome.value = SyncMessages.REPLACE_REFUSED
            return
        }
        scope.launch {
            val written = try {
                BackupExporter(recipes, imagesDir, io).export(open())
                true
            } catch (e: CancellationException) {
                _syncing.value = false
                throw e
            } catch (e: Exception) {
                runCatching { deleteBackup() }
                _outcome.value = SyncMessages.backupFailed(e)
                false
            }
            if (written) run(FirstSync.REPLACE) else _syncing.value = false
        }
    }

    // One sync. Its outcome is said once, the status read again, and only then is the button free again.
    private suspend fun run(first: FirstSync?) {
        val pcName = _status.value.pcName
        try {
            _outcome.value = try {
                val result = engine.sync(first)
                if (result.duplicates.isNotEmpty()) _duplicates.value = result.duplicates
                result.note ?: SyncMessages.synced(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is UnpairedException) _duplicates.value = emptyList()
                SyncMessages.syncFailed(e, pcName, noun)
            }
            refresh()
        } finally {
            _syncing.value = false
        }
    }

    // The pairing and the last sync as stored now. A read that fails shows "not paired": the store already treats an
    // unreadable pairing so.
    private suspend fun refresh() {
        val pairing = try {
            engine.pairing()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val last = try {
            engine.lastSyncAt()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        _status.value = SyncStatus(known = true, pcName = pairing?.pcName, lastSyncAt = last)
    }

    override fun outcomeShown(text: String) {
        _outcome.compareAndSet(text, null)
    }

    override fun keepBoth(duplicate: PossibleDuplicate) = _duplicates.update { it - duplicate }

    override fun keepPcs(duplicate: PossibleDuplicate) {
        // Off the list first, so a second tap deletes nothing more; back on it if the delete fails. The meals planned with
        // this phone's copy move to the PC's (a plain delete would cascade them away, here and, at the next sync, on the PC).
        if (duplicate !in _duplicates.value) return
        _duplicates.update { it - duplicate }
        scope.launch {
            try {
                recipes.replaceWith(duplicate.thisDevicesId, duplicate.pcsId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _duplicates.update { it + duplicate }
                _outcome.value = SyncMessages.removeCopyFailed(noun)
            }
        }
    }

    override fun recheckWifi() {
        _onWifi.value = readWifi()
    }

    private fun readWifi(): Boolean = try {
        wifi()
    } catch (e: Exception) {
        false
    }

    // Each runs in this scope, not the caller's: leaving Settings mid-pairing still keeps a pairing the PC has made. It
    // counts in [pairings] until it ends there, and first lets a sync (or Leave) already under way finish.
    private suspend fun pairing(block: suspend () -> Unit) {
        pairings.update { it + 1 }
        scope.async {
            try {
                _syncing.first { !it }
                block()
                refresh()
            } finally {
                pairings.update { it - 1 }
            }
        }.await()
    }

    override suspend fun pairScanned(text: String) = pairing { engine.pairScanned(text) }

    override suspend fun findPcs(): List<FoundPc> = withContext(io) { engine.findPcs() }

    override suspend fun pairTyped(code: String, pc: FoundPc) = pairing { engine.pairTyped(code, pc) }

    // Not while a sync or a pairing runs (the screen doesn't offer it); it holds [syncing] itself, so no sync starts mid-Leave.
    override suspend fun leave() {
        check(startWork()) { "A sync or a pairing is under way." }
        scope.async {
            try {
                engine.leave()
                _duplicates.value = emptyList()
                refresh()
            } finally {
                _syncing.value = false
            }
        }.await()
    }

    /** Stops this scope (tests; the app keeps it for the process). */
    fun close() = scope.cancel()

    /** Stage 4a: stops this scope and waits for its work to end, so a client PC's database is closed only after. */
    suspend fun closeAndJoin() = scope.coroutineContext.job.cancelAndJoin()
}
