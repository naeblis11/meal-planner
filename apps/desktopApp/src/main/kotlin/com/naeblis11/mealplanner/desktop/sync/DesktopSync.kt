package com.naeblis11.mealplanner.desktop.sync

import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.AppMetaEntity
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.settings.DeviceRow
import com.naeblis11.mealplanner.settings.PairingControls
import com.naeblis11.mealplanner.settings.PairingView
import com.naeblis11.mealplanner.settings.QrModules
import com.naeblis11.mealplanner.settings.SyncAttention
import com.naeblis11.mealplanner.settings.SyncServerStatus
import com.naeblis11.mealplanner.sync.HeldRemoval
import com.naeblis11.mealplanner.sync.PairingQr
import com.naeblis11.mealplanner.sync.SyncApplier
import com.naeblis11.mealplanner.sync.SyncErrors
import com.naeblis11.mealplanner.sync.SyncHub
import com.naeblis11.mealplanner.sync.SyncTables
import java.io.File
import java.security.SecureRandom
import java.util.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The master PC's side of LAN sync (stage 2b): 2a's hub over the app's own database and repository (so RecipeFolder's
 * holds apply), the certificate, the pairing window, the paired devices, the routes and the sync port. It is what
 * Settings' "Phones and PCs" ([PairingControls]) and Needs attention ([SyncAttention]) see. The database is reached
 * lazily, so making this opens nothing.
 *
 * The port listens only while a device is paired, the window is open, or a device was removed in the last
 * [REMOVED_LISTEN_MILLIS] (30 days, from [LAST_REMOVED_KEY] in app_meta: so a removed phone's next sync is told
 * "removed", 401 unpaired, instead of finding the port closed), and only while [lanAllowed] (not yielding: a phone of a
 * yielding PC finds it unreachable). Every change that could alter that rebinds it, and never on a sync request's own
 * thread: a pairing, or the fifth wrong code, changes the window inside a request, and a rebind there would stop the
 * listener under the reply it is sending. So a window closed by a request, and the device list changed by one, are
 * followed from [start]'s collectors, in the scope; Settings' own calls rebind on an IO thread.
 *
 * Requests under way are counted, so [awaitIdle] lets DesktopApp.close() wait for them before it closes the database;
 * one that reaches the handler after [stop] is answered `failed` without touching it. A remade certificate's notice is
 * kept in app_meta ([CERTIFICATE_REMADE_KEY]) until the next pairing, so a restart still says it.
 */
class DesktopSync(
    database: () -> AppDatabase,
    recipes: () -> RecipeRepository,
    private val imagesDir: File,
    val certificate: SyncCertificate,
    private val household: suspend () -> String,
    private val pcName: () -> String,
    private val lanAllowed: () -> Boolean,
    private val addresses: () -> List<String>,
    engine: SyncEngine = JdkHttpsEngine(),
    val port: Int = SyncServer.SYNC_PORT,
    lanHost: String = SyncServer.ALL_INTERFACES,
    private val qr: (String) -> QrModules = ::qrModules,
    private val clock: () -> Long = System::currentTimeMillis,
    random: Random = SecureRandom(),
    private val log: (String) -> Unit = { System.err.println(it) },
) : PairingControls, SyncAttention {
    private val db: AppDatabase by lazy(database)
    private val repository: RecipeRepository by lazy(recipes)

    val hub: SyncHub by lazy { SyncHub(db, repository, imagesDir, clock) }
    val window = PairingWindow(clock, random)
    val paired: PairedDevices by lazy { PairedDevices(db.pairedDeviceDao(), clock, random) }

    // Whether any device is paired, kept here so the port's lock never waits on the database. Set from the database
    // under [deviceLock] (settleDevices), and to true by a pairing before its window closes (so its reply keeps the port).
    @Volatile
    private var hasDevices = false
    private val deviceLock = Mutex()

    // When a device was last removed (app_meta), kept here for the same reason; null for never. Set under [deviceLock].
    @Volatile
    private var lastRemovedAt: Long? = null

    // Publishing the window as Settings sees it ([offer]) and closing it on expiry happen under this, so a window reopened
    // meanwhile is never cleared or closed by an older one's end.
    private val offerLock = Any()

    // Requests in the routes now; after [stop], none may start.
    private val inFlight = MutableStateFlow(0)

    @Volatile
    private var stopped = false

    private val routes: SyncRoutes by lazy {
        SyncRoutes(
            hub, window, paired,
            fingerprint = { certificate.fingerprint },
            household = household,
            pcName = pcName,
            lanAllowed = lanAllowed,
            onPaired = {
                hasDevices = true
                // A device has paired with this certificate: the "pair again" notice has done its work.
                guarded("Clearing the certificate notice") {
                    _certificateNotice.value = null
                    db.appMetaDao().remove(CERTIFICATE_REMADE_KEY)
                }
            },
            onExchanged = {
                // The exchange itself worked: a failed re-read must not turn its reply into a failure.
                try {
                    refreshHeld()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("Meal Planner: reading held sync removals failed: ${e.javaClass.simpleName}")
                }
            },
            onLeft = ::deviceLeft,
            log = log,
        )
    }

    val server = SyncServer(
        engine,
        ssl = { certificate.sslContext() },
        handler = SyncHandler { request -> counted { routes.handle(request) } },
        wanted = { window.isOpen() || hasDevices || removedRecently() },
        lanAllowed = lanAllowed,
        port = port,
        lanHost = lanHost,
        log = log,
    )

    private val _devices = MutableStateFlow<List<DeviceRow>>(emptyList())
    override val devices: StateFlow<List<DeviceRow>> = _devices.asStateFlow()

    private val _offer = MutableStateFlow<PairingView?>(null)
    override val offer: StateFlow<PairingView?> = _offer.asStateFlow()

    override val status: StateFlow<SyncServerStatus> get() = server.status

    private val _certificateNotice = MutableStateFlow<String?>(null)
    override val certificateNotice: StateFlow<String?> = _certificateNotice.asStateFlow()

    private val _held = MutableStateFlow<List<HeldRemoval>>(emptyList())
    override val held: StateFlow<List<HeldRemoval>> = _held.asStateFlow()

    @Volatile
    private var scope: CoroutineScope? = null

    @Volatile
    private var expiry: Job? = null

    @Volatile
    private var removalEnd: Job? = null

    // A request counted from before it starts until it is answered. One that arrives after stop() is refused here, so
    // once awaitIdle() has seen none, none touches the database.
    private suspend fun counted(block: suspend () -> SyncHttpResponse): SyncHttpResponse {
        inFlight.update { it + 1 }
        try {
            if (stopped) return SyncHttpResponse.error(500, SyncErrors.FAILED)
            return block()
        } finally {
            inFlight.update { it - 1 }
        }
    }

    /** Waits until no request is in the routes (DesktopApp.close(), after [stop], under its own time limit). */
    suspend fun awaitIdle() {
        inFlight.first { it == 0 }
    }

    private fun removedRecently(): Boolean {
        val at = lastRemovedAt ?: return false
        return clock() - at < REMOVED_LISTEN_MILLIS
    }

    /**
     * After the startup sync and the server: the port as the paired devices say, the device list, the held removals.
     * The server and the collectors come first, so a database read that fails here (logged as its class only) never
     * leaves sync dead for the session: the device list's collector settles the port again on its next emission.
     */
    suspend fun start(scope: CoroutineScope) {
        this.scope = scope
        // Before the port starts: whether a device was removed lately (it keeps the port open) and the remade notice.
        guarded("Reading the sync state") {
            lastRemovedAt = db.appMetaDao().get(LAST_REMOVED_KEY)?.toLongOrNull()
            if (db.appMetaDao().get(CERTIFICATE_REMADE_KEY) != null) _certificateNotice.value = SyncCertificate.REMADE_NOTICE
        }
        server.start(scope)
        // A certificate remade this run (at a bind or at Pair a device) is said until a device pairs, across restarts.
        scope.launch {
            certificate.notice.filterNotNull().collect { notice ->
                _certificateNotice.value = notice
                guarded("Keeping the certificate notice") { db.appMetaDao().put(AppMetaEntity(CERTIFICATE_REMADE_KEY, "1")) }
            }
        }
        scope.launch {
            // Through flow {}, so a database that can't be opened is retried like any other failure of the list.
            flow { emitAll(paired.devices) }
                .retryWhen { e, _ ->
                    if (e is CancellationException) {
                        false
                    } else {
                        log("Meal Planner: watching the paired devices failed: ${e.javaClass.simpleName}")
                        delay(COLLECT_RETRY_MILLIS)
                        true
                    }
                }
                .collect { list ->
                    guarded("Following the paired devices") {
                        _devices.value = list.map { DeviceRow(it.deviceId, it.name, it.kind, it.lastSyncAt) }
                        // Only when the list says something changed (an exchange's touch doesn't), and then from the database.
                        if (list.isNotEmpty() != hasDevices) settleDevices()
                    }
                }
        }
        // A window closed by a pairing, five wrong codes, Settings or its expiry: the port follows, here and never on the
        // request's thread, so a pairing's own reply is never cut.
        scope.launch {
            window.offer.collect { open ->
                if (open == null) {
                    guarded("Closing the sync port after pairing") {
                        synchronized(offerLock) { if (window.offer.value == null) _offer.value = null }
                        server.rebind()
                    }
                }
            }
        }
        // Listening now if a device is paired (the collector would get there too, a moment later).
        guarded("Reading the paired devices") { settleDevices() }
        guarded("Reading held sync removals") { refreshHeld() }
    }

    // One step of the background work: a failure is logged as its class only (never its message) and the work goes on.
    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Meal Planner: $what failed: ${e.javaClass.simpleName}")
        }
    }

    /** The yield changed (PeerWatch): the port follows. Blocking. */
    fun rebind() = server.rebind()

    /**
     * First in DesktopApp.close(): nothing may reach a closing database. Blocking, bounded. A request already in the
     * routes may still be running when this returns: DesktopApp.close() waits for it with [awaitIdle].
     */
    fun stop() {
        stopped = true
        synchronized(offerLock) {
            expiry?.cancel()
            window.close()
            _offer.value = null
        }
        removalEnd?.cancel()
        server.stop()
    }

    override suspend fun open() {
        withContext(Dispatchers.IO) {
            // Made on first need: the QR code carries its fingerprint. A certificate that can't be written fails here,
            // before any window opens (Settings says pairing couldn't start).
            val fingerprint = try {
                certificate.fingerprint
            } catch (e: Exception) {
                log("Meal Planner: pairing couldn't start: ${e.javaClass.simpleName}")
                throw e
            }
            // No home-network address (or none readable): the code is still shown, without a QR code.
            val here = try {
                addresses().take(PairingQr.MAX_ADDRESSES)
            } catch (e: Exception) {
                emptyList()
            }
            val made = synchronized(offerLock) {
                val made = window.open()
                try {
                    val text = if (here.isEmpty()) null else PairingQr.encode(here, port, made.code, fingerprint)
                    _offer.value = PairingView(made.code, made.expiresAt, here, text, text?.let(qr))
                } catch (e: Exception) {
                    window.close()
                    _offer.value = null
                    log("Meal Planner: pairing couldn't start: ${e.javaClass.simpleName}")
                    throw e
                }
                expiry?.cancel()
                made
            }
            server.rebind()
            expiry = scope?.launch {
                delay((made.expiresAt - clock()).coerceAtLeast(0L))
                synchronized(offerLock) { if (window.offer.value === made) window.close() }
            }
        }
    }

    override fun close() {
        synchronized(offerLock) {
            expiry?.cancel()
            window.close()
            _offer.value = null
        }
        // Called from the UI thread: the port closes in the background.
        scope?.launch { server.rebind() }
    }

    // The removal's time first, so the port is never closed between the removal and it: the device's next sync must
    // reach the port to be told it was removed.
    override suspend fun remove(deviceId: String) {
        withContext(Dispatchers.IO) {
            val now = clock()
            db.appMetaDao().put(AppMetaEntity(LAST_REMOVED_KEY, now.toString()))
            lastRemovedAt = now
            paired.remove(deviceId)
            settleDevices()
        }
    }

    // A device left by itself (POST /sync/leave), on the request's own thread. It is recorded as Remove records it: its
    // time first, so the port stays open for 30 days to tell any other sync it had under way. The port is then settled
    // in the scope, never under this reply (the removal time keeps it wanted, so the reply isn't cut).
    private suspend fun deviceLeft(deviceId: String) {
        val now = clock()
        db.appMetaDao().put(AppMetaEntity(LAST_REMOVED_KEY, now.toString()))
        lastRemovedAt = now
        paired.remove(deviceId)
        scope?.launch { guarded("Settling the sync port after a device left") { withContext(Dispatchers.IO) { settleDevices() } } }
    }

    // Whether any device is paired and when one was last removed, read again under the lock, then the port as that says:
    // whoever settles last reads the database after every change before it, so an older device list can't reopen the
    // port after a removal.
    private suspend fun settleDevices() {
        deviceLock.withLock {
            hasDevices = paired.any()
            lastRemovedAt = db.appMetaDao().get(LAST_REMOVED_KEY)?.toLongOrNull()
            server.rebind()
            closeAfterRemoval()
        }
    }

    // The port closes by itself once the removal window is over (when nothing else wants it).
    private fun closeAfterRemoval() {
        removalEnd?.cancel()
        val at = lastRemovedAt ?: return
        val left = at + REMOVED_LISTEN_MILLIS - clock()
        if (left <= 0) return
        removalEnd = scope?.launch {
            delay(left)
            guarded("Closing the sync port after a removal") { withContext(Dispatchers.IO) { server.rebind() } }
        }
    }

    /** Reads the held removals again: after every exchange, and after Remove or Keep. */
    suspend fun refreshHeld() {
        _held.value = hub.heldRemovals()
    }

    override suspend fun plannedMeals(uuids: Set<String>): Int = withContext(Dispatchers.IO) {
        val sync = db.syncDao()
        val held = sync.pending()
            .filter { it.reason == SyncApplier.MASS_REMOVAL && it.tableName == SyncTables.RECIPE && it.syncKey in uuids }
            .map { it.syncKey }
            .toSet()
        sync.meals().count { it.recipeUuid in held }
    }

    // Both wait on SyncHub's lock behind an exchange, and confirm moves recipe files to the Recycle Bin: never the caller's
    // thread. Each takes only the recipes the user was shown; any held since stay held.
    override suspend fun confirm(uuids: Set<String>) {
        withContext(Dispatchers.IO) {
            hub.confirmHeldRemovals(uuids)
            refreshHeld()
        }
    }

    override suspend fun keep(uuids: Set<String>) {
        withContext(Dispatchers.IO) {
            hub.keepHeldRemovals(uuids)
            refreshHeld()
        }
    }

    companion object {
        /** How long after the paired devices' list failed it is watched again. */
        const val COLLECT_RETRY_MILLIS = 5_000L

        /** How long after a device is removed the port stays open with nothing paired, so the device can be told (F2). */
        const val REMOVED_LISTEN_MILLIS = 30L * 24 * 60 * 60 * 1000

        /** app_meta: when a device was last removed, in milliseconds. */
        const val LAST_REMOVED_KEY = "sync_last_removed_at"

        /** app_meta: present while a remade certificate's notice waits for the next pairing. */
        const val CERTIFICATE_REMADE_KEY = "sync_certificate_remade"
    }
}
