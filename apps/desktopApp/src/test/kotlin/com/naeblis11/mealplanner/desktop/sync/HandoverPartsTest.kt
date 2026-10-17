package com.naeblis11.mealplanner.desktop.sync

import com.naeblis11.mealplanner.desktop.google.FakeProtector
import com.naeblis11.mealplanner.desktop.server.ApiToken
import com.naeblis11.mealplanner.desktop.server.SecretsFile
import com.naeblis11.mealplanner.settings.HandoverView
import com.naeblis11.mealplanner.sync.HandedDevice
import com.naeblis11.mealplanner.sync.HandoverBundle
import com.naeblis11.mealplanner.sync.HandoverRequest
import com.naeblis11.mealplanner.sync.PairingWire
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 4b's parts: the hand-over's steps, the certificate moved between PCs, the token, and the pending file. */
class HandoverPartsTest {
    private val dir: File = Files.createTempDirectory("mp-handover-parts").toFile()
    private var now = 1_000L
    private val state = HandoverState(clock = { now })
    private val request = HandoverRequest("STUDY", listOf("192.0.2.30"), 5001, "h".repeat(32), "ab".repeat(32))

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun anOfferIsTakenOnlyByItsPcThenDoneOnce() {
        assertTrue(state.offer("pc-1", "STUDY"))
        assertEquals(HandoverView("pc-1", "STUDY", now + HandoverState.OFFER_MILLIS, handing = false), state.view.value)
        assertTrue(state.offeredTo("pc-1"))
        assertFalse(state.offeredTo("pc-2"))
        assertFalse(state.take("pc-2", request))
        assertTrue(state.take("pc-1", request))
        assertTrue(state.handing())
        assertTrue(state.view.value!!.handing)
        // Handing over: no new offer, and a cancel leaves it to run its course.
        assertFalse(state.offer("pc-2", "LAPTOP"))
        state.cancel()
        assertTrue(state.handing())
        assertNull(state.done("pc-2"))
        assertEquals(request, state.done("pc-1"))
        assertNull(state.done("pc-1"))
        assertNull(state.view.value)
    }

    @Test
    fun anOfferAndAWaitRunOut() {
        state.offer("pc-1", "STUDY")
        now += HandoverState.OFFER_MILLIS
        assertFalse(state.offeredTo("pc-1"))
        assertNull(state.view.value)
        state.offer("pc-1", "STUDY")
        state.take("pc-1", request)
        assertFalse(state.expireHanding())
        now += HandoverState.WAIT_MILLIS
        assertTrue(state.expireHanding())
        assertFalse(state.handing())
        assertNull(state.done("pc-1"))
    }

    @Test
    fun removingThePcCancelsItsOfferOnly() {
        state.offer("pc-1", "STUDY")
        state.cancel("pc-2")
        assertTrue(state.offeredTo("pc-1"))
        state.cancel("pc-1")
        assertFalse(state.offeredTo("pc-1"))
    }

    @Test
    fun aCertificateMovesToAnotherPcAndIsForgottenOnTheOld() {
        val old = SyncCertificate(File(dir, "old"), FakeProtector(), log = {})
        val fingerprint = old.fingerprint
        val (store, password) = old.export()
        val taker = SyncCertificate(File(dir, "new"), FakeProtector(), log = {})
        taker.install(store, password)
        assertEquals(fingerprint, taker.fingerprint)
        // Read again from its files, sealed with the taker's own DPAPI.
        assertEquals(fingerprint, SyncCertificate(File(dir, "new"), FakeProtector(), log = {}).fingerprint)
        assertArrayEquals(store, taker.storeFile.readBytes())
        old.discard()
        assertFalse(old.storeFile.exists())
        assertFalse(old.passwordFile.exists())
        // Its next need makes a new one: never the one handed over.
        assertTrue(old.fingerprint != fingerprint)
    }

    @Test
    fun aCertificateThatDoesNotOpenIsRefusedAndNothingChanges() {
        val taker = SyncCertificate(dir, FakeProtector(), log = {})
        val mine = taker.fingerprint
        assertThrows(IllegalArgumentException::class.java) { taker.install(byteArrayOf(1, 2, 3), "pw-1234") }
        val (store, _) = SyncCertificate(File(dir, "other"), FakeProtector(), log = {}).export()
        assertThrows(IllegalArgumentException::class.java) { taker.install(store, "wrong-password") }
        assertEquals(mine, taker.fingerprint)
        assertEquals(mine, SyncCertificate(dir, FakeProtector(), log = {}).fingerprint)
    }

    @Test
    fun aTokenIsAdoptedAndClearedInTheSecretsFile() {
        val secrets = File(dir, ".env")
        val token = ApiToken(SecretsFile(secrets), log = {})
        token.adopt("t".repeat(64))
        assertEquals("t".repeat(64), ApiToken(SecretsFile(secrets), log = {}).value)
        token.clear()
        assertNull(token.value)
        assertNull(ApiToken(SecretsFile(secrets), log = {}).value)
        assertThrows(IllegalArgumentException::class.java) { token.adopt("short") }
    }

    @Test
    fun aPendingHandOverIsSealedAndComesBack() = runBlocking<Unit> {
        val file = File(dir, HandoverPending.FILE_NAME)
        val pending = HandoverPending(file, FakeProtector())
        assertNull(pending.load())
        val bundle = HandoverBundle(
            listOf(HandedDevice("1".repeat(32), "Pixel", PairingWire.KIND_PHONE, "2".repeat(64), 1L, null)),
            byteArrayOf(1, 2, 3), "secret-password", "t".repeat(64), "5".repeat(32), "KITCHEN",
        )
        pending.save(PendingHandover(bundle, "ab".repeat(32)))
        val text = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse("sealed", text.contains("secret-password"))
        val back = pending.load()!!
        assertEquals(bundle, back.bundle)
        assertEquals("ab".repeat(32), back.masterKey)
        // Sealed by another Windows user, or damaged: nothing pending.
        val other = HandoverPending(file, object : com.naeblis11.mealplanner.desktop.google.SecretProtector {
            override fun protect(plain: ByteArray) = plain

            override fun unprotect(sealed: ByteArray): ByteArray = throw IllegalStateException("not this user's")
        })
        assertNull(other.load())
        pending.clear()
        assertFalse(file.exists())
    }
}
