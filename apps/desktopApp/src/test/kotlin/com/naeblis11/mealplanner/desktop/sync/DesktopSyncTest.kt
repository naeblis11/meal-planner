package com.naeblis11.mealplanner.desktop.sync

import androidx.room.useWriterConnection
import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.AppMetaEntity
import com.naeblis11.mealplanner.data.MealPlanRepository
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.data.SyncPendingEntity
import com.naeblis11.mealplanner.data.openAt
import com.naeblis11.mealplanner.desktop.google.FakeProtector
import com.naeblis11.mealplanner.desktop.server.eventually
import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.YamlMap
import com.naeblis11.mealplanner.settings.DeviceRow
import com.naeblis11.mealplanner.settings.QrModules
import com.naeblis11.mealplanner.settings.SyncServerState
import com.naeblis11.mealplanner.sync.HeldRemoval
import com.naeblis11.mealplanner.sync.PairRequest
import com.naeblis11.mealplanner.sync.PairingInvite
import com.naeblis11.mealplanner.sync.PairingQr
import com.naeblis11.mealplanner.sync.SyncApplier
import com.naeblis11.mealplanner.sync.SyncPaths
import com.naeblis11.mealplanner.sync.SyncTables
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.LocalDate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** LAN sync stage 2b (D7, D9, D11): the PC's sync side as Settings and Needs attention see it. FakeSyncEngine: no sockets. */
class DesktopSyncTest {
    private val dir: File = Files.createTempDirectory("mp-desktop-sync").toFile()
    private val images = File(dir, "images").apply { mkdirs() }
    private val db = AppDatabase.openAt(File(dir, "pc.db"))
    private val recipes = RecipeRepository(db, images)
    private val engine = FakeSyncEngine()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logs = CopyOnWriteArrayList<String>()

    @Volatile
    private var allowed = true

    @Volatile
    private var addresses = listOf("192.0.2.20")

    @Volatile
    private var now = 1_800_000_000_000L

    private fun desktopSync(
        certificateDir: File = File(dir, "app-data"),
        certificate: SyncCertificate = SyncCertificate(certificateDir, FakeProtector(), log = { logs += it }),
    ) = DesktopSync(
        database = { db },
        recipes = { recipes },
        imagesDir = images,
        certificate = certificate,
        household = { "0123456789abcdef0123456789abcdef" },
        pcName = { "KITCHEN" },
        lanAllowed = { allowed },
        addresses = { addresses },
        engine = engine,
        port = 5065,
        qr = { text -> QrModules(1, booleanArrayOf(text.isNotEmpty())) },
        clock = { now },
        log = { logs += it },
    )

    private val sync = desktopSync()

    @After
    fun tearDown() {
        sync.stop()
        scope.cancel()
        db.close()
        dir.deleteRecursively()
    }

    @Suppress("UNCHECKED_CAST")
    private fun doc(name: String, uuid: String): YamlMap = RecipeYaml.load(
        "recipe_name: $name\nrecipe_uuid: $uuid\ningredients:\n- Salt:\n    amounts:\n    - amount: 1\n      unit: tsp\nsteps:\n- step: Stir.\n",
    ) as YamlMap

    @Test
    fun withNothingPairedTheSyncPortIsClosedUntilPairingOpens() = runBlocking<Unit> {
        sync.start(scope)
        assertEquals(SyncServerState.OFF, sync.status.value.state)
        sync.open()
        assertEquals(SyncServerState.LISTENING, sync.status.value.state)
        assertEquals(listOf(SyncServer.ALL_INTERFACES), engine.startedHosts())
        val view = sync.offer.value!!
        assertTrue(Regex("[0-9]{6}").matches(view.code))
        assertEquals(PairingInvite(listOf("192.0.2.20"), 5065, view.code, sync.certificate.fingerprint), PairingQr.decode(view.qrText!!))
        assertNotNull(view.qr)
        assertFalse(view.toString().contains(view.code))
        sync.close()
        assertNull(sync.offer.value)
        eventually { sync.status.value.state == SyncServerState.OFF }
    }

    @Test
    fun whileYieldingPairingDoesntOpenTheSyncPort() = runBlocking<Unit> {
        allowed = false
        sync.start(scope)
        sync.open()
        assertEquals(SyncServerState.OFF, sync.status.value.state)
        assertEquals(0, engine.attempts())
        allowed = true
        sync.rebind()
        assertEquals(SyncServerState.LISTENING, sync.status.value.state)
    }

    @Test
    fun aRemovedDeviceKeepsThePortOpenThirtyDaysSoItCanBeToldThenItCloses() = runBlocking<Unit> {
        sync.paired.register(PairRequest(ID, "Pixel", "phone", "0f".repeat(16), "ab".repeat(32)))
        sync.start(scope)
        assertEquals(SyncServerState.LISTENING, sync.status.value.state)
        eventually { sync.devices.value == listOf(DeviceRow(ID, "Pixel", "phone", null)) }
        sync.remove(ID)
        // Its next sync must hear "removed" (401 unpaired), not find the port closed.
        assertEquals(SyncServerState.LISTENING, sync.status.value.state)
        eventually { sync.devices.value.isEmpty() }
        assertEquals(now.toString(), db.appMetaDao().get(DesktopSync.LAST_REMOVED_KEY))
        now += DesktopSync.REMOVED_LISTEN_MILLIS - 1
        sync.rebind()
        assertEquals(SyncServerState.LISTENING, sync.status.value.state)
        now += 1
        sync.rebind()
        assertEquals(SyncServerState.OFF, sync.status.value.state)
        assertFalse(engine.listening)
    }

    @Test
    fun aRecentRemovalKeepsThePortOpenAfterARestartAndAnOldOneDoesnt() = runBlocking<Unit> {
        db.appMetaDao().put(AppMetaEntity(DesktopSync.LAST_REMOVED_KEY, (now - 24 * 3_600_000L).toString()))
        sync.start(scope)
        assertEquals(SyncServerState.LISTENING, sync.status.value.state)
        sync.stop()
        db.appMetaDao().put(AppMetaEntity(DesktopSync.LAST_REMOVED_KEY, (now - DesktopSync.REMOVED_LISTEN_MILLIS).toString()))
        val later = desktopSync()
        try {
            later.start(scope)
            assertEquals(SyncServerState.OFF, later.status.value.state)
        } finally {
            later.stop()
        }
    }

    @Test
    fun whileYieldingARecentRemovalDoesntOpenThePort() = runBlocking<Unit> {
        db.appMetaDao().put(AppMetaEntity(DesktopSync.LAST_REMOVED_KEY, now.toString()))
        allowed = false
        sync.start(scope)
        assertEquals(SyncServerState.OFF, sync.status.value.state)
        assertEquals(0, engine.attempts())
    }

    @Test
    fun awaitIdleWaitsForARequestUnderWay() = runBlocking<Unit> {
        sync.start(scope)
        sync.open()
        val handler = engine.handler!!
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val answered = CompletableFuture<SyncHttpResponse>()
        thread {
            val body: (Int) -> ByteArray = {
                entered.countDown()
                release.await()
                "not json".toByteArray()
            }
            answered.complete(runBlocking { handler.handle(SyncHttpRequest("POST", SyncPaths.PAIR, null, 8, body, "203.0.113.30")) })
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        sync.stop()
        val idle = async(Dispatchers.IO) { sync.awaitIdle() }
        // A short negative wait: the request is still running.
        assertNull(withTimeoutOrNull(300) { idle.await() })
        release.countDown()
        withTimeout(5_000) { idle.await() }
        assertEquals(400, answered.get(5, TimeUnit.SECONDS).status)
        // A request that reaches the handler after the stop is answered without the database.
        assertEquals(500, handler.handle(SyncHttpRequest("POST", SyncPaths.EXCHANGE, null, 0, { ByteArray(0) }, "203.0.113.30")).status)
        withTimeout(5_000) { sync.awaitIdle() }
    }

    @Test
    fun theWindowsExpiryClosesThePort() = runBlocking<Unit> {
        sync.start(scope)
        sync.open()
        assertEquals(SyncServerState.LISTENING, sync.status.value.state)
        // A window closed from elsewhere (a pairing, five wrong codes, its expiry): the offer goes and the port follows.
        sync.window.close()
        eventually { sync.offer.value == null && sync.status.value.state == SyncServerState.OFF }
        assertFalse(engine.listening)
    }

    @Test
    fun withNoHomeNetworkThereIsACodeButNoQr() = runBlocking<Unit> {
        addresses = emptyList()
        sync.start(scope)
        sync.open()
        val view = sync.offer.value!!
        assertTrue(Regex("[0-9]{6}").matches(view.code))
        assertNull(view.qrText)
        assertNull(view.qr)
    }

    @Test
    fun heldSyncRemovalsAreListedCountedAndKept() = runBlocking<Unit> {
        val id = recipes.save(doc("Soup", "u-1"))
        MealPlanRepository(db).assign(LocalDate.of(2026, 10, 12), "Dinner", id, null)
        db.syncDao().putPending(SyncPendingEntity(SyncTables.RECIPE, "u-1", SyncApplier.MASS_REMOVAL, "{}"))
        sync.start(scope)
        assertEquals(listOf(HeldRemoval("u-1", "Soup")), sync.held.value)
        assertEquals(1, sync.plannedMeals(setOf("u-1")))
        assertEquals(0, sync.plannedMeals(emptySet()))
        sync.keep(setOf("u-1"))
        assertEquals(emptyList<HeldRemoval>(), sync.held.value)
        assertEquals(listOf("Soup"), recipes.allRecipes().map { it.name })
    }

    @Test
    fun aHeldReadThatFailsAtStartStillOpensAPairedPcsPort() = runBlocking<Unit> {
        sync.paired.register(PairRequest(ID, "Den PC", "pc", "0f".repeat(16), "ab".repeat(32)))
        // Without sync_pending, every read of held removals fails.
        db.useWriterConnection { connection -> connection.usePrepared("DROP TABLE sync_pending") { it.step() } }
        sync.start(scope)
        assertEquals(SyncServerState.LISTENING, sync.status.value.state)
        eventually { sync.devices.value.map { it.deviceId } == listOf(ID) }
        assertEquals(emptyList<HeldRemoval>(), sync.held.value)
        // Logged as its class only.
        assertTrue(logs.toString(), logs.any { it.startsWith("Meal Planner: Reading held sync removals failed: ") })
        assertFalse(logs.toString(), logs.any { it.contains("sync_pending") })
    }

    @Test
    fun aRemadeCertificateIsSaidUntilTheNextPairingEvenAfterARestart() = runBlocking<Unit> {
        File(dir, "app-data").mkdirs()
        File(dir, "app-data/${SyncCertificate.STORE_FILE}").writeBytes(byteArrayOf(1, 2, 3))
        sync.start(scope)
        sync.open()
        assertEquals(SyncCertificate.REMADE_NOTICE, sync.certificateNotice.value)
        eventually { runBlocking { db.appMetaDao().get(DesktopSync.CERTIFICATE_REMADE_KEY) } != null }
        sync.stop()
        // The next start reads the remade certificate without trouble, and still says so: no device has paired since.
        val later = desktopSync()
        try {
            later.start(scope)
            assertEquals(SyncCertificate.REMADE_NOTICE, later.certificateNotice.value)
        } finally {
            later.stop()
        }
    }

    @Test
    fun aCertificateThatCantBeReadJustNowFailsTheListenerAndIsKept() = runBlocking<Unit> {
        val folder = File(dir, "app-data")
        val first = SyncCertificate(folder, FakeProtector()).fingerprint
        val store = File(folder, SyncCertificate.STORE_FILE).readBytes()
        sync.paired.register(PairRequest(ID, "Pixel", "phone", "0f".repeat(16), "ab".repeat(32)))
        val flaky = SyncCertificate(folder, FakeProtector(), log = { logs += it }, read = { throw IOException("secret-busy") })
        val broken = desktopSync(certificate = flaky)
        try {
            broken.start(scope)
            assertEquals(SyncServerState.FAILED, broken.status.value.state)
            assertNull(broken.certificateNotice.value)
            assertArrayEquals(store, File(folder, SyncCertificate.STORE_FILE).readBytes())
            assertFalse(logs.toString(), logs.any { it.contains("secret-busy") })
        } finally {
            broken.stop()
        }
        assertEquals(first, SyncCertificate(folder, FakeProtector()).fingerprint)
    }

    @Test
    fun aCertificateThatCantBeWrittenFailsPairingCleanly() = runBlocking<Unit> {
        // A file where the app data folder should be: the certificate can't be written.
        val blocked = File(dir, "blocked").apply { writeText("not a folder") }
        val broken = desktopSync(certificateDir = File(blocked, "app-data"))
        try {
            broken.start(scope)
            assertThrows(Exception::class.java) { runBlocking { broken.open() } }
            assertNull(broken.offer.value)
            assertFalse(broken.window.isOpen())
            assertEquals(SyncServerState.OFF, broken.status.value.state)
            assertEquals(0, engine.attempts())
            // Only class names reach the log.
            assertTrue(logs.toString(), logs.any { it.startsWith("Meal Planner: pairing couldn't start: ") })
            assertFalse(logs.toString(), logs.any { it.contains(blocked.path) || it.contains("not a folder") })
        } finally {
            broken.stop()
        }
    }

    @Test
    fun aDeviceThatLeavesIsRemovedAsRemoveDoesAndThePortStaysOpenToTellIt() = runBlocking<Unit> {
        val key = sync.paired.register(PairRequest(ID, "Pixel", "phone", "0f".repeat(16), "ab".repeat(32)))
        sync.start(scope)
        eventually { sync.devices.value.size == 1 }
        val handler = engine.handler!!
        val response = handler.handle(SyncHttpRequest("POST", SyncPaths.LEAVE, "Bearer $key", 0, { throw AssertionError("read") }, "203.0.113.30"))
        assertEquals(204, response.status)
        // As Remove: its time first, so a sync it still had under way is told "removed" for 30 days.
        assertEquals(now.toString(), db.appMetaDao().get(DesktopSync.LAST_REMOVED_KEY))
        eventually { sync.devices.value.isEmpty() }
        assertEquals(SyncServerState.LISTENING, sync.status.value.state)
        now += DesktopSync.REMOVED_LISTEN_MILLIS
        sync.rebind()
        assertEquals(SyncServerState.OFF, sync.status.value.state)
    }

    private companion object {
        const val ID = "0123456789abcdef0123456789abcdef"
    }
}
