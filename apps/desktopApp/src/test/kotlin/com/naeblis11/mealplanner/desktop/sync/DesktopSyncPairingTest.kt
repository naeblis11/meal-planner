package com.naeblis11.mealplanner.desktop.sync

import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.AppMetaEntity
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.data.openAt
import com.naeblis11.mealplanner.desktop.google.FakeProtector
import com.naeblis11.mealplanner.desktop.server.PortProbe
import com.naeblis11.mealplanner.desktop.server.eventually
import com.naeblis11.mealplanner.desktop.server.freeLoopbackPort
import com.naeblis11.mealplanner.settings.SyncServerState
import com.naeblis11.mealplanner.sync.ExchangeRequest
import com.naeblis11.mealplanner.sync.NotPairingException
import com.naeblis11.mealplanner.sync.PairingWire
import com.naeblis11.mealplanner.sync.SyncHttp
import com.naeblis11.mealplanner.sync.UnpairedException
import com.naeblis11.mealplanner.sync.WrongCodeException
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LAN sync stage 2b (D9): DesktopSync over the real routes and a real JdkHttpsEngine on 127.0.0.1 and an ephemeral port.
 * A pairing or a fifth wrong code changes whether the port is wanted from inside a request; the port must follow from
 * another thread, never cutting that request's own reply.
 */
class DesktopSyncPairingTest {
    private val dir: File = Files.createTempDirectory("mp-desktop-pair").toFile()
    private val images = File(dir, "images").apply { mkdirs() }
    private val db = AppDatabase.openAt(File(dir, "pc.db"))
    private val recipes = RecipeRepository(db, images)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logs = CopyOnWriteArrayList<String>()
    private val port = freeLoopbackPort()

    private val sync = DesktopSync(
        database = { db },
        recipes = { recipes },
        imagesDir = images,
        certificate = SyncCertificate(File(dir, "app-data"), FakeProtector(), log = { logs += it }),
        household = { HOUSEHOLD },
        pcName = { "KITCHEN" },
        lanAllowed = { true },
        addresses = { listOf(LOOPBACK) },
        engine = JdkHttpsEngine(portFree = { PortProbe.isFree(it, InetAddress.getLoopbackAddress()) }, log = { logs += it }),
        port = port,
        lanHost = LOOPBACK,
        log = { logs += it },
    )

    @After
    fun tearDown() {
        sync.stop()
        scope.cancel()
        db.close()
        dir.deleteRecursively()
    }

    @Test
    fun theFirstDeviceGetsItsPairingReplyAndThePortStaysOpenForIt() = runBlocking<Unit> {
        sync.start(scope)
        assertEquals(SyncServerState.OFF, sync.status.value.state)
        sync.open()
        assertEquals(SyncServerState.LISTENING, sync.status.value.state)
        val code = sync.offer.value!!.code
        // Wanted moves from "the window is open" to "a device is paired" inside this request.
        val paired = SyncHttp(LOOPBACK, port, pin = null).pair(code, PHONE_ID, "Pixel", PairingWire.KIND_PHONE)
        assertEquals(sync.certificate.fingerprint, paired.fingerprint)
        assertEquals(HOUSEHOLD, paired.reply.householdId)
        assertNotNull(sync.paired.authenticate("Bearer ${paired.reply.deviceKey}"))
        eventually { sync.offer.value == null && sync.devices.value.map { it.deviceId } == listOf(PHONE_ID) }
        assertEquals(SyncServerState.LISTENING, sync.status.value.state)
        // Still serving: the used-up window says so over the same port.
        assertThrows(NotPairingException::class.java) {
            runBlocking { SyncHttp(LOOPBACK, port, paired.fingerprint).pair(code, PHONE_ID, "Pixel", PairingWire.KIND_PHONE) }
        }
        assertEquals(SyncServerState.LISTENING, sync.status.value.state)
        assertFalse(logs.toString(), logs.any { it.contains("failed") })
    }

    @Test
    fun theFifthWrongCodeGetsItsReplyThenThePortCloses() = runBlocking<Unit> {
        sync.start(scope)
        sync.open()
        val code = sync.offer.value!!.code
        val wrong = ((code.toInt() + 1) % 1_000_000).toString().padStart(6, '0')
        repeat(PairingWindow.MAX_FAILURES - 1) {
            assertThrows(WrongCodeException::class.java) {
                runBlocking { SyncHttp(LOOPBACK, port, pin = null).pair(wrong, PHONE_ID, "Pixel", PairingWire.KIND_PHONE) }
            }
        }
        // The fifth closes the window inside its request: its own answer still arrives, then nothing is paired and the port closes.
        assertThrows(NotPairingException::class.java) {
            runBlocking { SyncHttp(LOOPBACK, port, pin = null).pair(wrong, PHONE_ID, "Pixel", PairingWire.KIND_PHONE) }
        }
        eventually { sync.offer.value == null && sync.status.value.state == SyncServerState.OFF }
        assertTrue(PortProbe.isFree(port, InetAddress.getLoopbackAddress()))
        assertEquals(0, db.pairedDeviceDao().count())
    }

    @Test
    fun aRemovedPhoneIsToldItWasRemovedOverAPortThatStaysOpen() = runBlocking<Unit> {
        sync.start(scope)
        sync.open()
        val paired = SyncHttp(LOOPBACK, port, pin = null).pair(sync.offer.value!!.code, PHONE_ID, "Pixel", PairingWire.KIND_PHONE)
        eventually { sync.devices.value.map { it.deviceId } == listOf(PHONE_ID) }
        val transport = SyncHttp(LOOPBACK, port, paired.fingerprint).transport(paired.reply.deviceKey)
        transport.exchange(ExchangeRequest(System.currentTimeMillis(), 0L, false, emptyList(), null))
        // The only device is removed: the port stays open for it, and its next sync hears why.
        sync.remove(PHONE_ID)
        eventually { sync.devices.value.isEmpty() }
        assertEquals(SyncServerState.LISTENING, sync.status.value.state)
        assertThrows(UnpairedException::class.java) {
            runBlocking { transport.exchange(ExchangeRequest(System.currentTimeMillis(), 0L, false, emptyList(), null)) }
        }
    }

    @Test
    fun aPairingEndsTheRemadeCertificatesNotice() = runBlocking<Unit> {
        db.appMetaDao().put(AppMetaEntity(DesktopSync.CERTIFICATE_REMADE_KEY, "1"))
        sync.start(scope)
        assertEquals(SyncCertificate.REMADE_NOTICE, sync.certificateNotice.value)
        sync.open()
        SyncHttp(LOOPBACK, port, pin = null).pair(sync.offer.value!!.code, PHONE_ID, "Pixel", PairingWire.KIND_PHONE)
        eventually { sync.certificateNotice.value == null && runBlocking { db.appMetaDao().get(DesktopSync.CERTIFICATE_REMADE_KEY) } == null }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val HOUSEHOLD = "0123456789abcdef0123456789abcdef"
        const val PHONE_ID = "fedcba9876543210fedcba9876543210"
    }
}
