package com.naeblis11.mealplanner.desktop

import com.naeblis11.mealplanner.desktop.google.FakeProtector
import com.naeblis11.mealplanner.desktop.sync.DesktopSync
import com.naeblis11.mealplanner.desktop.sync.FakeSyncEngine
import com.naeblis11.mealplanner.desktop.sync.SyncCertificate
import com.naeblis11.mealplanner.desktop.sync.SyncEngine
import com.naeblis11.mealplanner.desktop.sync.SyncHttpRequest
import com.naeblis11.mealplanner.desktop.sync.SyncHttpResponse
import com.naeblis11.mealplanner.settings.SyncServerState
import com.naeblis11.mealplanner.sync.ExchangeRequest
import com.naeblis11.mealplanner.sync.PairRequest
import com.naeblis11.mealplanner.sync.SyncPaths
import com.naeblis11.mealplanner.sync.SyncWire
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** LAN sync stage 2b (D9): sync starts after the server, closed while nothing is paired, and stops first when the app closes. */
class DesktopSyncLifecycleTest {
    private val dir: File = Files.createTempDirectory("mp-sync-life").toFile()
    private val engine = FakeSyncEngine()

    @Volatile
    private var databaseOpenAtStop: Boolean? = null

    private lateinit var made: DesktopApp

    // The fake engine, noting whether the database still answered when the sync port was stopped.
    private val watched = object : SyncEngine by engine {
        override fun stop() {
            if (engine.listening) {
                databaseOpenAtStop = try {
                    runBlocking { made.container.database.appMetaDao().get("anything") }
                    true
                } catch (e: Exception) {
                    false
                }
            }
            engine.stop()
        }
    }

    private val app = DesktopApp(
        dir,
        settingsFactory = { MapSettings() },
        syncFactory = { desktop ->
            DesktopSync(
                database = { desktop.container.database },
                recipes = { desktop.container.recipes },
                imagesDir = desktop.imagesDir,
                certificate = SyncCertificate(File(dir, "app-data"), FakeProtector()),
                household = { desktop.identity.household.id() },
                pcName = { "KITCHEN" },
                lanAllowed = { true },
                addresses = { emptyList() },
                engine = watched,
                port = 5065,
            )
        },
    ).also { made = it }

    @After
    fun tearDown() {
        app.close()
        dir.deleteRecursively()
    }

    @Test
    fun syncStartsClosedAfterStartupAndStopsWhenTheAppCloses() = runBlocking<Unit> {
        app.start().join()
        val sync = app.sync!!
        assertEquals(SyncServerState.OFF, sync.status.value.state)
        sync.open()
        assertTrue(engine.listening)
        assertTrue(app.close())
        assertFalse(engine.listening)
        assertEquals(SyncServerState.OFF, sync.status.value.state)
        assertEquals(true, databaseOpenAtStop)
    }

    @Test
    fun closeLeavesTheDatabaseOpenUntilARunningExchangeIsDone() = runBlocking<Unit> {
        app.start().join()
        val sync = app.sync!!
        val key = sync.paired.register(PairRequest(PHONE_ID, "Pixel", "phone", "0f".repeat(16), "ab".repeat(32)))
        sync.open()
        val handler = engine.handler!!
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exchange = SyncWire.encode(ExchangeRequest(System.currentTimeMillis(), 0L, false, emptyList(), null)).toByteArray()
        val answered = CompletableFuture<SyncHttpResponse>()
        thread {
            val body: (Int) -> ByteArray = {
                entered.countDown()
                release.await()
                exchange
            }
            val request = SyncHttpRequest("POST", SyncPaths.EXCHANGE, "Bearer $key", exchange.size.toLong(), body, "203.0.113.30")
            answered.complete(runBlocking { handler.handle(request) })
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val closed = CompletableFuture.supplyAsync { app.close() }
        // A short negative wait: close stops the port at once but must not close the database under the exchange.
        assertThrows(TimeoutException::class.java) { closed.get(400, TimeUnit.MILLISECONDS) }
        assertEquals(null, made.container.database.appMetaDao().get("anything"))
        release.countDown()
        // The exchange runs against the open database, then close finishes.
        assertEquals(200, answered.get(5, TimeUnit.SECONDS).status)
        assertTrue(closed.get(5, TimeUnit.SECONDS))
    }

    private companion object {
        const val PHONE_ID = "fedcba9876543210fedcba9876543210"
    }
}
