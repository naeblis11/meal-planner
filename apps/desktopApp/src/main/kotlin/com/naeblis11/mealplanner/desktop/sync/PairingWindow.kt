package com.naeblis11.mealplanner.desktop.sync

import com.naeblis11.mealplanner.sync.PairingProof
import java.security.SecureRandom
import java.util.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** An open pairing window: its 6-digit [code] and when it closes. The code is never in [toString]. */
data class PairingOffer(val code: String, val expiresAt: Long) {
    override fun toString(): String = "PairingOffer(expiresAt=$expiresAt)"
}

/**
 * Settings' Pair a device (LAN sync spec, section 2): a window of [openMillis] with a random 6-digit code. Opening again
 * makes a new code. A device proves the code with [PairingProof.device] over the fingerprint it saw; [MAX_FAILURES]
 * wrong proofs close the window, and so does its expiry, noticed whenever it is asked. One right proof ([claim]) uses the
 * code up but leaves the window open: the pairing route closes it once its reply is ready, so the sync port (wanted while
 * the window is open or a device is paired) keeps listening for that reply. Thread-safe.
 */
class PairingWindow(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = SecureRandom(),
    private val openMillis: Long = OPEN_MILLIS,
) {
    private val _offer = MutableStateFlow<PairingOffer?>(null)

    /** The open window, or null. */
    val offer: StateFlow<PairingOffer?> = _offer.asStateFlow()

    private var failures = 0

    /** The code was claimed by a right proof: every later claim is null until [open] makes a new code. */
    private var claimed = false

    @Synchronized
    fun open(): PairingOffer {
        val made = PairingOffer(random.nextInt(1_000_000).toString().padStart(6, '0'), clock() + openMillis)
        failures = 0
        claimed = false
        _offer.value = made
        return made
    }

    @Synchronized
    fun close() {
        failures = 0
        _offer.value = null
    }

    /** Open and not expired; an expired window is closed here. */
    @Synchronized
    fun isOpen(): Boolean {
        val open = _offer.value ?: return false
        if (clock() >= open.expiresAt) {
            close()
            return false
        }
        return true
    }

    /**
     * Uses the code up: for the right [proof] over [fingerprint] and [nonce] it marks the code claimed in the same step and
     * returns the master's proof of the code (PairingProof.hub), so one code pairs one device. The window stays open until
     * [close]. A wrong proof counts, and the [MAX_FAILURES]th closes the window. Null while closed, expired, already
     * claimed or wrong; a claim after the code is used up counts no failure. The pairing route calls this, then
     * PairedDevices.register, then [close]; a second device needs the window opened again.
     */
    @Synchronized
    fun claim(proof: String, nonce: String, fingerprint: String): String? {
        if (!isOpen() || claimed) return null
        val code = _offer.value?.code ?: return null
        if (PairingProof.matches(PairingProof.device(code, fingerprint, nonce), proof)) {
            claimed = true
            return PairingProof.hub(code, fingerprint, nonce)
        }
        failures++
        if (failures >= MAX_FAILURES) close()
        return null
    }

    companion object {
        const val OPEN_MILLIS = 5 * 60_000L
        const val MAX_FAILURES = 5
    }
}
