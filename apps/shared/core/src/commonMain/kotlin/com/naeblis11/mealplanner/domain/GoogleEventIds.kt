package com.naeblis11.mealplanner.domain

import java.security.MessageDigest
import java.time.LocalDate

/**
 * Google event ids for planned meals (P5-R2): [PREFIX] and the first [HASH_CHARS] characters of base32hex (RFC 4648,
 * lower case, no padding) of SHA-256(householdId + "|" + date + "|" + slot), 42 characters in all. Google takes 5 to
 * 1024 characters of a-v and 0-9, so an insert can name its own event, and two devices sending the same week name the
 * same event instead of making two.
 */
object GoogleEventIds {
    const val PREFIX = "mp"
    const val HASH_CHARS = 40
    private const val ALPHABET = "0123456789abcdefghijklmnopqrstuv"
    private val VALID = Regex("[a-v0-9]{5,1024}")

    fun forMeal(householdId: String, date: LocalDate, slot: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$householdId|$date|$slot".toByteArray(Charsets.UTF_8))
        return PREFIX + base32Hex(digest).substring(0, HASH_CHARS)
    }

    /** An id Google accepts for an event it is given. */
    fun isValid(id: String): Boolean = VALID.matches(id)

    /** RFC 4648's base32hex, lower case and without the "=" padding. */
    fun base32Hex(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
            // Only the bits not yet written are kept, so the buffer never grows past 12 bits.
            buffer = buffer and ((1 shl bits) - 1)
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return out.toString()
    }
}
