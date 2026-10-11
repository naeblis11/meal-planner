package com.naeblis11.mealplanner.desktop.sync

import com.naeblis11.mealplanner.desktop.server.PortProbe
import com.naeblis11.mealplanner.desktop.server.freeLoopbackPort
import com.naeblis11.mealplanner.desktop.sync.SyncRig.Companion.HOUSEHOLD
import com.naeblis11.mealplanner.desktop.sync.SyncRig.Companion.LOOPBACK
import com.naeblis11.mealplanner.desktop.sync.SyncRig.Companion.PC_NAME
import com.naeblis11.mealplanner.desktop.sync.SyncRig.Companion.PHONE_ID
import com.naeblis11.mealplanner.sync.ExchangeRequest
import com.naeblis11.mealplanner.sync.NotMasterException
import com.naeblis11.mealplanner.sync.SyncErrors
import com.naeblis11.mealplanner.sync.NotPairingException
import com.naeblis11.mealplanner.sync.PairReply
import com.naeblis11.mealplanner.sync.PairingWire
import com.naeblis11.mealplanner.sync.SyncHttp
import com.naeblis11.mealplanner.sync.SyncHttpException
import com.naeblis11.mealplanner.sync.UnpairedException
import com.naeblis11.mealplanner.sync.WrongCodeException
import com.naeblis11.mealplanner.sync.WrongPcException
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * LAN sync stage 2b (D4, D7): the shared client against the real sync port on 127.0.0.1. Pinned, it refuses any other
 * certificate before a byte of the request is sent, and never falls back to an unpinned connection.
 */
class SyncHttpTest {
    private val rig = SyncRig().start()
    private var extra: JdkHttpsEngine? = null

    @After
    fun tearDown() {
        extra?.stop()
        rig.close()
    }

    private fun emptyRequest() = ExchangeRequest(System.currentTimeMillis(), 0L, false, emptyList(), null)

    private suspend fun pairedKey(): String = rig.client().pair(rig.window.open().code, PHONE_ID, "Pixel", PairingWire.KIND_PHONE).reply.deviceKey

    // Another listener with the rig's certificate and a handler of the test's own.
    private fun custom(handler: SyncHandler): Int {
        val port = freeLoopbackPort()
        extra = JdkHttpsEngine(portFree = { PortProbe.isFree(it, InetAddress.getLoopbackAddress()) }).also {
            it.start(LOOPBACK, port, rig.certificate.sslContext(), handler)
        }
        return port
    }

    @Test
    fun theQrPathPairsPinnedToThePc() = runBlocking<Unit> {
        val paired = rig.client().pair(rig.window.open().code, PHONE_ID, "Pixel", PairingWire.KIND_PHONE)
        assertEquals(rig.certificate.fingerprint, paired.fingerprint)
        assertEquals(PC_NAME, paired.reply.pcName)
        assertEquals(HOUSEHOLD, paired.reply.householdId)
        assertEquals(rig.hub.hubId(), paired.reply.hubId)
        assertEquals(PHONE_ID, rig.devices.authenticate("Bearer ${paired.reply.deviceKey}")!!.deviceId)
    }

    @Test
    fun theTypedPathLearnsThePcsFingerprint() = runBlocking<Unit> {
        val paired = rig.client(pin = null).pair(rig.window.open().code, PHONE_ID, "Pixel", PairingWire.KIND_PHONE)
        assertEquals(rig.certificate.fingerprint, paired.fingerprint)
    }

    @Test
    fun aWrongPinStopsBeforeAnythingIsSent() {
        val code = rig.window.open().code
        assertThrows(WrongPcException::class.java) {
            runBlocking { rig.client(pin = "cd".repeat(32)).pair(code, PHONE_ID, "Pixel", PairingWire.KIND_PHONE) }
        }
        assertEquals(0, rig.requests.get())
        // The code was never tried, so the window is still open and nothing was counted.
        assertNotNull(rig.window.offer.value)
    }

    @Test
    fun aWrongCodeIsSaid() {
        rig.window.open()
        assertThrows(WrongCodeException::class.java) { runBlocking { rig.client().pair("999999", PHONE_ID, "Pixel", "phone") } }
        assertNotNull(rig.window.offer.value)
    }

    @Test
    fun aClosedWindowIsSaid() {
        assertThrows(NotPairingException::class.java) { runBlocking { rig.client().pair("123456", PHONE_ID, "Pixel", "phone") } }
    }

    @Test
    fun aPairedDeviceExchangesAndARemovedOneIsTold() = runBlocking<Unit> {
        val key = pairedKey()
        val transport = rig.client().transport(key)
        assertEquals(rig.hub.hubId(), transport.exchange(emptyRequest()).hubId)
        assertNull(transport.photo("u-none"))
        rig.devices.remove(PHONE_ID)
        assertThrows(UnpairedException::class.java) { runBlocking { transport.exchange(emptyRequest()) } }
    }

    @Test
    fun aWrongPinNeverFallsBackOnASync() = runBlocking<Unit> {
        val key = pairedKey()
        val before = rig.requests.get()
        assertThrows(WrongPcException::class.java) { runBlocking { rig.client(pin = "cd".repeat(32)).transport(key).exchange(emptyRequest()) } }
        assertEquals(before, rig.requests.get())
    }

    @Test
    fun aPcThatCantProveTheCodeIsNotTrusted() {
        val port = custom { SyncHttpResponse.json(200, PairingWire.encode(PairReply("00".repeat(32), "11".repeat(32), "hub", HOUSEHOLD, "EVIL"))) }
        assertThrows(WrongPcException::class.java) { runBlocking { SyncHttp(LOOPBACK, port, null).pair("123456", PHONE_ID, "Pixel", "phone") } }
    }

    @Test
    fun aReplyOverItsCapIsRefused() {
        val port = custom { SyncHttpResponse.json(200, "{\"x\":\"" + "a".repeat(2_048) + "\"}") }
        val small = SyncHttp(LOOPBACK, port, rig.certificate.fingerprint, maxExchangeReplyBytes = 1_024)
        val thrown = assertThrows(SyncHttpException::class.java) { runBlocking { small.transport("0".repeat(64)).exchange(emptyRequest()) } }
        assertEquals(SyncHttp.TOO_BIG, thrown.message)
    }

    @Test
    fun aGarbledReplyIsSaidPlainly() {
        val port = custom { SyncHttpResponse.json(200, "not json") }
        val thrown = assertThrows(SyncHttpException::class.java) {
            runBlocking { SyncHttp(LOOPBACK, port, rig.certificate.fingerprint).transport("0".repeat(64)).exchange(emptyRequest()) }
        }
        assertEquals(SyncHttp.UNREADABLE, thrown.message)
    }

    @Test
    fun nothingListeningIsUnreachable() {
        val thrown = assertThrows(SyncHttpException::class.java) {
            runBlocking { SyncHttp(LOOPBACK, freeLoopbackPort(), "ab".repeat(32)).transport("0".repeat(64)).exchange(emptyRequest()) }
        }
        assertEquals(SyncHttp.UNREACHABLE, thrown.message)
    }

    @Test
    fun aPcThatYieldsAnswersAPhotoUploadWithNotMaster() {
        val yielding = SyncRoutes(rig.hub, rig.window, rig.devices, fingerprint = { rig.certificate.fingerprint }, household = { HOUSEHOLD }, pcName = { PC_NAME }, lanAllowed = { false }, log = { rig.logs += it })
        val port = custom(yielding)
        val transport = SyncHttp(LOOPBACK, port, rig.certificate.fingerprint).transport("0".repeat(64))
        assertThrows(NotMasterException::class.java) { runBlocking { transport.sendPhoto("u-pc", ByteArray(8)) } }
    }

    @Test
    fun aRefusedPhotoIsDoneButOtherConflictsAreNot() = runBlocking<Unit> {
        val key = pairedKey()
        // The hub refuses a photo for a recipe it doesn't have: 409 refused, nothing to resend.
        rig.client().transport(key).sendPhoto("u-none", ByteArray(8))
    }

    @Test
    fun anExchangeWaitsLongerForItsReplyThanOtherRequests() {
        // The PC sends nothing until a big Merge's hub.exchange is done, which can take minutes: the exchange waits past
        // the server's own 120 s reply phase, while pair and photo keep 30 s.
        assertEquals(150_000, SyncHttp.EXCHANGE_READ_TIMEOUT_MILLIS)
        assertEquals(30_000, SyncHttp.READ_TIMEOUT_MILLIS)
        assert(SyncHttp.EXCHANGE_READ_TIMEOUT_MILLIS > JdkHttpsEngine.RESPONSE_MILLIS)
        // Scaled down: a reply 1.5 s late is waited for by the exchange alone.
        val port = custom { _ ->
            Thread.sleep(1_500)
            SyncHttpResponse.json(200, "not json")
        }
        val slow = SyncHttp(LOOPBACK, port, rig.certificate.fingerprint, readTimeoutMillis = 300, exchangeReadTimeoutMillis = 10_000).transport("0".repeat(64))
        val exchanged = assertThrows(SyncHttpException::class.java) { runBlocking { slow.exchange(emptyRequest()) } }
        assertEquals("the exchange got its (garbled) reply", SyncHttp.UNREADABLE, exchanged.message)
        val photo = assertThrows(SyncHttpException::class.java) { runBlocking { slow.photo("u-1") } }
        assertEquals("the photo timed out", SyncHttp.UNREACHABLE, photo.message)
    }

    @Test
    fun theTypedPairBodyIsSentWithItsLength() {
        val lengths = java.util.concurrent.CopyOnWriteArrayList<Long>()
        val port = custom { request ->
            lengths += request.contentLength
            SyncHttpResponse.error(403, SyncErrors.WRONG_CODE)
        }
        assertThrows(WrongCodeException::class.java) { runBlocking { SyncHttp(LOOPBACK, port, rig.certificate.fingerprint).pair("123456", PHONE_ID, "Pixel", "phone") } }
        assertEquals(1, lengths.size)
        assert(lengths[0] > 0) { "declared length ${lengths[0]}" }
    }
}
