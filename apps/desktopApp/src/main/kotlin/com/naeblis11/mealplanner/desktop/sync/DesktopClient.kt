package com.naeblis11.mealplanner.desktop.sync

import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.AppMetaEntity
import com.naeblis11.mealplanner.data.Household
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.data.inTransaction
import com.naeblis11.mealplanner.sync.HttpSyncConnector
import com.naeblis11.mealplanner.sync.PairingStore
import com.naeblis11.mealplanner.sync.PairingWire
import com.naeblis11.mealplanner.sync.PcFinder
import com.naeblis11.mealplanner.sync.PhoneSync
import com.naeblis11.mealplanner.sync.PhoneSyncControls
import com.naeblis11.mealplanner.sync.SyncConnector
import com.naeblis11.mealplanner.sync.SyncNoun
import java.io.File
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
     * on the Sync button. Returns at once: the sync runs in the controls' scope.
     */
    suspend fun startupSync() {
        if (engine.pairing() != null && engine.hasSynced()) controls.sync(null)
    }

    /** Cancels a sync, pairing or Leave under way and waits for it to end, before the database closes. */
    suspend fun close() {
        controls.closeAndJoin()
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
