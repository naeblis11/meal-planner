package com.naeblis11.mealplanner.desktop.sync

import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.data.openAt
import com.naeblis11.mealplanner.desktop.google.FakeProtector
import com.naeblis11.mealplanner.desktop.server.PortProbe
import com.naeblis11.mealplanner.desktop.server.freeLoopbackPort
import com.naeblis11.mealplanner.settings.SyncServerState
import com.naeblis11.mealplanner.sync.SyncHttp
import com.naeblis11.mealplanner.sync.SyncHub
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Cancels [scope] and waits for its work to end, then closes [db]. cancel() alone returns while a collector of the scope
 * (DesktopSync's paired-devices list) may still be in a Room query on an IO thread, and closing the database under it
 * crashed the test JVM in the bundled SQLite driver. Bounded: work that won't end fails the test and leaves the database
 * open, as DesktopApp.close() does.
 */
internal fun closeAfterScope(scope: CoroutineScope, db: AppDatabase) {
    runBlocking { withTimeout(10_000) { scope.coroutineContext.job.cancelAndJoin() } }
    db.close()
}

/** A sync engine that binds nothing: it records where it was asked to listen, keeps the handler, and fails when told to. */
class FakeSyncEngine : SyncEngine {
    private val hosts = mutableListOf<String>()
    private val failures = ArrayDeque<Exception>()
    private var attempts = 0
    private var stops = 0
    private var listeningOn: String? = null

    /** The handler of the listener now running, for tests that call routes through it. */
    @Volatile
    var handler: SyncHandler? = null

    /** Thrown by every stop of a listener while set: it still listens. */
    @Volatile
    var stopFailure: Exception? = null

    @get:Synchronized
    override val listening: Boolean
        get() = listeningOn != null

    @Synchronized
    fun failNext(vararg errors: Exception) {
        failures.addAll(errors)
    }

    @Synchronized
    override fun start(host: String, port: Int, ssl: SSLContext, handler: SyncHandler) {
        attempts++
        check(listeningOn == null) { "The sync listener is already running." }
        failures.removeFirstOrNull()?.let { throw it }
        hosts += host
        listeningOn = host
        this.handler = handler
    }

    @Synchronized
    override fun stop() {
        stops++
        if (listeningOn == null) return
        stopFailure?.let { throw it }
        listeningOn = null
        handler = null
    }

    @Synchronized
    fun startedHosts(): List<String> = hosts.toList()

    @Synchronized
    fun attempts(): Int = attempts

    @Synchronized
    fun stops(): Int = stops
}

/**
 * The master PC's sync side for real: a hub on a temp database, a certificate made with FakeProtector, the routes and a
 * SyncServer over JdkHttpsEngine on 127.0.0.1 and an ephemeral port (never 5000 or 5001). Always wanted, so it listens
 * from start(); its lifecycle is SyncServerTest's.
 */
internal class SyncRig : Closeable {
    val root: File = Files.createTempDirectory("mp-sync-rig").toFile()
    val logs = CopyOnWriteArrayList<String>()
    val images = File(root, "pc-images").apply { mkdirs() }
    val db = AppDatabase.openAt(File(root, "pc.db"))
    val recipes = RecipeRepository(db, images)
    val hub = SyncHub(db, recipes, images)
    val certificate = SyncCertificate(File(root, "app-data"), FakeProtector(), log = { logs += it })
    val window = PairingWindow()
    val devices = PairedDevices(db.pairedDeviceDao())

    /** Requests that reached the routes: a refused certificate never gets this far. */
    val requests = AtomicInteger()

    val routes = SyncRoutes(hub, window, devices, fingerprint = { certificate.fingerprint }, household = { HOUSEHOLD }, pcName = { PC_NAME }, log = { logs += it })
    val port = freeLoopbackPort()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val server = SyncServer(
        JdkHttpsEngine(portFree = { PortProbe.isFree(it, InetAddress.getLoopbackAddress()) }, log = { logs += it }),
        ssl = { certificate.sslContext() },
        handler = SyncHandler { request ->
            requests.incrementAndGet()
            routes.handle(request)
        },
        wanted = { true },
        port = port,
        lanHost = LOOPBACK,
        log = { logs += it },
    )

    fun start(): SyncRig = apply {
        server.start(scope)
        check(server.status.value.state == SyncServerState.LISTENING) { "not listening: ${server.status.value}" }
    }

    /** A client of this PC, pinned to [pin]; null is the typed code's first, unpinned connection. */
    fun client(pin: String? = certificate.fingerprint) = SyncHttp(LOOPBACK, port, pin)

    override fun close() {
        server.stop()
        closeAfterScope(scope, db)
        root.deleteRecursively()
    }

    companion object {
        const val LOOPBACK = "127.0.0.1"
        const val HOUSEHOLD = "0123456789abcdef0123456789abcdef"
        const val PC_NAME = "KITCHEN"
        const val PHONE_ID = "fedcba9876543210fedcba9876543210"
    }
}
