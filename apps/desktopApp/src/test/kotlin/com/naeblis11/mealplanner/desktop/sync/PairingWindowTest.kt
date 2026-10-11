package com.naeblis11.mealplanner.desktop.sync

import com.naeblis11.mealplanner.sync.PairingProof
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test

/** LAN sync stage 2b (D5): Pair a device's 5-minute window, its 6-digit code and the five wrong attempts. */
class PairingWindowTest {
    private var now = 1_000L
    private val fp = "ab".repeat(32)
    private val nonce = "0f".repeat(16)

    private fun fixed(code: Int) = object : Random() {
        override fun nextInt(bound: Int): Int = code
    }

    private fun window(code: Int = 123) = PairingWindow(clock = { now }, random = fixed(code))

    @Test
    fun openingMakesASixDigitCodeKeepingLeadingZerosForFiveMinutes() {
        val window = window(42)
        val offer = window.open()
        assertEquals("000042", offer.code)
        assertEquals(1_000L + 5 * 60_000L, offer.expiresAt)
        assertEquals(offer, window.offer.value)
        assertTrue(window.isOpen())
    }

    @Test
    fun reopeningMakesANewCode() {
        var next = 1
        val window = PairingWindow(clock = { now }, random = object : Random() {
            override fun nextInt(bound: Int): Int = next++
        })
        assertEquals("000001", window.open().code)
        assertEquals("000002", window.open().code)
    }

    @Test
    fun theRightProofIsAcceptedAndAnsweredWithTheHubsProof() {
        val window = window()
        val code = window.open().code
        assertEquals(PairingProof.hub(code, fp, nonce), window.claim(PairingProof.device(code, fp, nonce), nonce, fp))
    }

    @Test
    fun aProofOverAnotherFingerprintIsRefused() {
        val window = window()
        val code = window.open().code
        // A machine in the middle showed the phone its own certificate: the phone's proof names that one.
        assertNull(window.claim(PairingProof.device(code, "cd".repeat(32), nonce), nonce, fp))
    }

    @Test
    fun fiveWrongProofsCloseTheWindow() {
        val window = window()
        val code = window.open().code
        val wrong = PairingProof.device("999999", fp, nonce)
        repeat(PairingWindow.MAX_FAILURES - 1) { assertNull(window.claim(wrong, nonce, fp)) }
        assertTrue(window.isOpen())
        assertNull(window.claim(wrong, nonce, fp))
        assertFalse(window.isOpen())
        assertNull(window.offer.value)
        assertNull(window.claim(PairingProof.device(code, fp, nonce), nonce, fp))
    }

    @Test
    fun reopeningForgetsEarlierFailures() {
        val window = window()
        window.open()
        val wrong = PairingProof.device("999999", fp, nonce)
        repeat(PairingWindow.MAX_FAILURES - 1) { window.claim(wrong, nonce, fp) }
        window.open()
        repeat(PairingWindow.MAX_FAILURES - 1) { window.claim(wrong, nonce, fp) }
        assertTrue(window.isOpen())
    }

    @Test
    fun anExpiredWindowIsClosed() {
        val window = window()
        val code = window.open().code
        now += PairingWindow.OPEN_MILLIS
        assertFalse(window.isOpen())
        assertNull(window.offer.value)
        assertNull(window.claim(PairingProof.device(code, fp, nonce), nonce, fp))
    }

    @Test
    fun closeShutsIt() {
        val window = window()
        window.open()
        assertNotNull(window.offer.value)
        window.close()
        assertFalse(window.isOpen())
        assertNull(window.offer.value)
    }

    @Test
    fun anOfferNeverShowsItsCodeInToString() {
        assertFalse(window(123456).open().toString().contains("123456"))
    }

    @Test
    fun aCodeIsUsedUpByTheFirstRightProof() {
        val window = window()
        val code = window.open().code
        val proof = PairingProof.device(code, fp, nonce)
        assertEquals(PairingProof.hub(code, fp, nonce), window.claim(proof, nonce, fp))
        // The window stays open until the pairing route has answered and closes it, so the sync port keeps listening.
        assertTrue(window.isOpen())
        assertNull(window.claim(proof, nonce, fp))
        window.close()
        assertFalse(window.isOpen())
    }

    @Test
    fun reopeningMakesAFreshCodeThatCanBeClaimed() {
        var next = 1
        val window = PairingWindow(clock = { now }, random = object : Random() {
            override fun nextInt(bound: Int): Int = next++
        })
        val first = window.open().code
        assertNotNull(window.claim(PairingProof.device(first, fp, nonce), nonce, fp))
        val second = window.open().code
        assertEquals(PairingProof.hub(second, fp, nonce), window.claim(PairingProof.device(second, fp, nonce), nonce, fp))
    }

    @Test
    fun aUsedUpCodeCountsNoMoreFailures() {
        val window = window()
        val code = window.open().code
        assertNotNull(window.claim(PairingProof.device(code, fp, nonce), nonce, fp))
        val wrong = PairingProof.device("999999", fp, nonce)
        repeat(PairingWindow.MAX_FAILURES) { assertNull(window.claim(wrong, nonce, fp)) }
        assertTrue(window.isOpen())
    }

    private fun race(threads: Int, task: () -> Unit) {
        val start = CountDownLatch(1)
        val pool = List(threads) { Thread { start.await(); task() } }
        pool.forEach { it.start() }
        start.countDown()
        pool.forEach { it.join() }
    }

    @Test
    fun manyWrongProofsAtOnceCloseTheWindowAfterFive() {
        val window = window()
        val code = window.open().code
        val wrong = PairingProof.device("999999", fp, nonce)
        val wins = AtomicInteger()
        race(16) { if (window.claim(wrong, nonce, fp) != null) wins.incrementAndGet() }
        assertEquals(0, wins.get())
        assertFalse(window.isOpen())
        assertNull(window.offer.value)
        assertNull(window.claim(PairingProof.device(code, fp, nonce), nonce, fp))
    }

    @Test
    fun manyRightProofsAtOnceGiveExactlyOneWinner() {
        val window = window()
        val proof = PairingProof.device(window.open().code, fp, nonce)
        val wins = AtomicInteger()
        race(8) { if (window.claim(proof, nonce, fp) != null) wins.incrementAndGet() }
        assertEquals(1, wins.get())
        assertTrue(window.isOpen())
        assertNull(window.claim(proof, nonce, fp))
    }
}
