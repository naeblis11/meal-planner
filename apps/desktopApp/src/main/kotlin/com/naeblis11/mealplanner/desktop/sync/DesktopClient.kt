package com.naeblis11.mealplanner.desktop.sync

import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.AppMetaEntity
import com.naeblis11.mealplanner.data.Household
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.data.inTransaction
import com.naeblis11.mealplanner.sync.FirstSync
import com.naeblis11.mealplanner.sync.HandoverBundle
import com.naeblis11.mealplanner.sync.HandoverRefusedException
import com.naeblis11.mealplanner.sync.HandoverRequest
import com.naeblis11.mealplanner.sync.HttpSyncConnector
import com.naeblis11.mealplanner.sync.PairingStore
import com.naeblis11.mealplanner.sync.PairingWire
import com.naeblis11.mealplanner.sync.PcFinder
import com.naeblis11.mealplanner.sync.PhonePairing
import com.naeblis11.mealplanner.sync.PhoneSync
import com.naeblis11.mealplanner.sync.PhoneSyncControls
import com.naeblis11.mealplanner.sync.SyncConnector
import com.naeblis11.mealplanner.sync.SyncMarks
import com.naeblis11.mealplanner.sync.SyncMessages
import com.naeblis11.mealplanner.sync.SyncNoun
import com.naeblis11.mealplanner.sync.SyncResult
import com.naeblis11.mealplanner.update.Sha256
import java.io.File
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Stage 4b: where the old main PC will find this PC once it has taken over: its name, addresses, sync port and hub id. */
data class HandoverHere(val pcName: String, val addresses: List<String>, val port: Int, val hubId: String)

/** Stage 4b: what taking over asks of the rest of this PC (Main: DesktopSync and the Alexa token). */
interface HandoverTaker {
    suspend fun here(): HandoverHere

    /** Installs the role: the certificate, the devices with the old main PC under [masterKeyHash], the Alexa token. */
    suspend fun adopt(bundle: HandoverBundle, masterKeyHash: String)
}

/**
 * This PC as a client of the household's main PC (LAN sync stage 4a): the phone's PhoneSync and PhoneSyncControls over
 * this PC's own database and recipe repository, so recipes it syncs are YAML files in its library (RecipeRepository
 * writes the file first). The pairing is [store] (DpapiPairingStore, `sync-client.bin`); [finder] is JmdnsPcFinder;
 * the main PC lists this one as a PC (PairingWire.KIND_PC) under [deviceName].
 *
 * The role is the pairing (R3): [master] is the main PC's name while this PC is a client, null while it is a main PC of
 * its own. [load] reads it at start, before the servers bind; a Join or a Leave changes it at once and calls
 * [onRoleChanged] (Main rebinds the server and the sync port and announces the new role). Joining takes the main PC's
 * household (PhoneSync keeps its id) and forgets the Google events this PC recorded, which belong to the old household
 * (R2). Leaving, or being removed on the main PC, gives this PC a household of its own, new and created now (R11), so it
 * is never mistaken for the one it left; its data stays.
 *
 * Stage 4b: when an exchange says the main PC offers its role, this PC takes it at once (inside the same Sync, through
 * [taker]): the bundle is kept sealed in [pending] while the old main PC is told it is done, then installed, and the
 * pairing is forgotten with the household kept. The other way round, [joinByHandover] makes this PC the client of the PC
 * that took its role, and syncs with it (Merge, no question: the two hold the same data).
 *
 * Built lazily ([database] and [recipes] are only asked for on first use), so nothing opens the database on the UI thread.
 */
class DesktopClient(
    private val database: () -> AppDatabase,
    private val recipes: () -> RecipeRepository,
    private val imagesDir: File,
    private val store: PairingStore,
    private val finder: PcFinder,
    private val deviceName: String,
    private val connector: SyncConnector = HttpSyncConnector,
    private val onRoleChanged: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: () -> ByteArray = { ByteArray(16).also(SecureRandom()::nextBytes) },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val taker: HandoverTaker? = null,
    private val pending: HandoverPending? = null,
    private val log: (String) -> Unit = { System.err.println(it) },
) {
    private val _master = MutableStateFlow<String?>(null)

    /** The main PC's name while this PC is a client; null while it is a main PC (of its household, or on its own). */
    val master: StateFlow<String?> = _master.asStateFlow()

    /** True while this PC syncs with another (R4: no Alexa, no phones, no sync port). */
    fun isClient(): Boolean = _master.value != null

    val engine: PhoneSync by lazy {
        PhoneSync(
            database(), recipes(), imagesDir, store, finder, deviceName, connector, clock,
            kind = PairingWire.KIND_PC,
            onJoined = { joined() },
            onLeft = { left() },
            afterSync = { pairing, result -> if (result.handover) takeOver(pairing, result) else result },
        )
    }

    /** Settings' Join a household and the Sync button. */
    val controls: PhoneSyncControls by lazy {
        PhoneSyncControls(engine, recipes(), imagesDir, wifi = { true }, io = io, noun = SyncNoun.PC)
    }

    /** Reads the role from the pairing file, at start and before the servers bind. A file that can't be opened: not a client. */
    suspend fun load() {
        _master.value = store.load()?.pcName
    }

    /**
     * R9: one sync at start, only for a client that has synced before; the first sync waits for Merge or Replace, chosen
     * on the Sync button. Returns at once: the sync runs in the controls' scope. Stage 4b: a hand-over cut short (the
     * pending file) is finished first, instead.
     */
    suspend fun startupSync() {
        val cut = pending?.load()
        if (cut != null) {
            val pairing = store.load()
            if (pairing == null) pending.clear() else finish(pairing, cut)
            return
        }
        if (engine.pairing() != null && engine.hasSynced()) controls.sync(null)
    }

    /** Cancels a sync, pairing or Leave under way and waits for it to end, before the database closes. */
    suspend fun close() {
        controls.closeAndJoin()
    }

    /**
     * Stage 4b, R5: the PC that took this one's role is done; this PC becomes its client in the same household, with the
     * key it made for this PC, and syncs with it at once.
     */
    suspend fun joinByHandover(handed: HandedOver) {
        val request = handed.request
        val db = database()
        val household = db.appMetaDao().get(Household.KEY) ?: Household(db).id()
        val pairing = PhonePairing(request.addresses, request.port, handed.fingerprint, request.pcName, request.masterKey, request.hubId, household)
        store.save(pairing)
        SyncMarks.clear(db)
        _master.value = pairing.pcName
        onRoleChanged()
        controls.sync(FirstSync.MERGE)
    }

    // Inside the sync that was offered the role, under PhoneSync's lock. A failure before the bundle is kept changes
    // nothing (the sync itself worked); the offer stays until it runs out, so Sync again tries again.
    private suspend fun takeOver(pairing: PhonePairing, result: SyncResult): SyncResult {
        val side = taker ?: return result
        val keep = pending ?: return result
        val masterKey = Sha256.hex(random() + random())
        val bundle = try {
            val here = side.here()
            if (here.addresses.isEmpty()) return result.copy(note = SyncMessages.HANDOVER_FAILED)
            val request = HandoverRequest(here.pcName, here.addresses, here.port, here.hubId, masterKey)
            connector.takeHandover(pairing.addresses.first(), pairing.port, pairing.fingerprint, pairing.deviceKey, request)
                .also { keep.save(PendingHandover(it, masterKey)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HandoverRefusedException) {
            return result
        } catch (e: Exception) {
            log("Meal Planner: taking over as the main PC failed: ${e.javaClass.simpleName}")
            return result.copy(note = SyncMessages.HANDOVER_FAILED)
        }
        return finish(pairing, PendingHandover(bundle, masterKey))?.let { result.copy(note = it) } ?: result
    }

    // The old main PC is told; then, unless it refused (it had given up waiting), the role is installed here. Unreachable
    // means it has already become this PC's client (or is off: then two main PCs show the notice until one joins the
    // other). Returns the outcome to say, or null when this PC stays a client.
    private suspend fun finish(pairing: PhonePairing, cut: PendingHandover): String? {
        val side = taker ?: return null
        try {
            connector.handoverDone(pairing.addresses.first(), pairing.port, pairing.fingerprint, pairing.deviceKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HandoverRefusedException) {
            log("Meal Planner: the main PC gave up the hand-over; this PC stays its client.")
            pending?.clear()
            return null
        } catch (e: Exception) {
            log("Meal Planner: the old main PC didn't answer the hand-over's end (${e.javaClass.simpleName}); taking over.")
        }
        side.adopt(cut.bundle, PairedDevices.hashOf(cut.masterKey))
        store.clear()
        SyncMarks.clear(database())
        pending?.clear()
        _master.value = null
        onRoleChanged()
        log("Meal Planner: this PC is now the household's main PC.")
        return SyncMessages.nowMainPc(pairing.pcName)
    }

    private suspend fun joined() {
        database().googleEventDao().clear()
        _master.value = store.load()?.pcName ?: MAIN_PC
        onRoleChanged()
    }

    private suspend fun left() {
        val db = database()
        val meta = db.appMetaDao()
        val id = random().joinToString("") { "%02x".format(it) }
        db.inTransaction {
            meta.put(AppMetaEntity(Household.KEY, id))
            meta.put(AppMetaEntity(Household.CREATED_KEY, clock().toString()))
        }
        _master.value = null
        onRoleChanged()
    }

    companion object {
        /** The name used when a pairing names no main PC. */
        const val MAIN_PC = "the main PC"

        /** This PC's name as the main PC lists it when Windows gives none (R13). */
        const val FALLBACK_NAME = "Windows PC"
    }
}
