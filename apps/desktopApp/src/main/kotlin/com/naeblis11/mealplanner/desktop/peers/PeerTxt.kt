package com.naeblis11.mealplanner.desktop.peers

import java.net.InetAddress
import java.security.MessageDigest
import java.text.Normalizer

/**
 * The record on the wire (P6-R3) and its bounds (P6-R11). A peer's record is untrusted: [decode] returns null, and the
 * peer is ignored, for any missing or garbled value, and never throws.
 */
object PeerTxt {
    const val SERVICE_TYPE = "_mealplanner._tcp.local."
    const val VERSION = "1"
    const val ROLE_MASTER = "master"
    const val MAX_NAME_BYTES = 63
    const val MAX_VALUE_CHARS = 64
    const val MAX_PEERS = 32
    const val MAX_TXT_KEYS = 16
    const val FALLBACK_NAME = "Meal Planner PC"

    private val HASH = Regex("[0-9a-f]{16}")
    private val ID = Regex("[0-9a-f]{32}")
    private val SINCE = Regex("[0-9]{1,18}")
    private val DROPPED_TYPES = setOf(
        Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
        Character.PRIVATE_USE, Character.UNASSIGNED, Character.SURROGATE,
    )
    private val MARK_TYPES = setOf(Character.NON_SPACING_MARK, Character.ENCLOSING_MARK)

    /** The six TXT keys, in this order. */
    fun encode(record: PeerRecord): Map<String, String> = linkedMapOf(
        "v" to VERSION,
        "role" to ROLE_MASTER,
        "hh" to record.householdHash,
        "since" to record.since.toString(),
        "id" to record.instanceId,
        "app" to cleanApp(record.app),
    )

    /** A peer's record from its instance [name] and [txt]; null when anything is missing or garbled. Keys it doesn't know are ignored. */
    fun decode(name: String?, txt: Map<String, String?>): PeerRecord? {
        fun value(key: String): String? = txt[key]?.takeIf { it.length <= MAX_VALUE_CHARS }
        if (value("v") != VERSION || value("role") != ROLE_MASTER) return null
        val hash = value("hh")?.takeIf { HASH.matches(it) } ?: return null
        val since = value("since")?.takeIf { SINCE.matches(it) }?.toLong()?.takeIf { it > 0 } ?: return null
        val id = value("id")?.takeIf { ID.matches(it) } ?: return null
        val app = value("app")?.takeIf { it.isNotEmpty() && it.all { c -> c in ' '..'~' } } ?: return null
        val shown = cleanName(name) ?: return null
        return PeerRecord(shown, hash, since, id, app)
    }

    /** SHA-256 of the household id, as its first 16 lower-case hex digits: enough to tell households apart, and not the id. */
    fun householdHash(householdId: String): String =
        MessageDigest.getInstance("SHA-256").digest(householdId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)

    /**
     * A name as shown and sent: no control, format (bidi and zero-width), line or paragraph separator, private-use,
     * unassigned or lone surrogate characters, and no combining marks (non-spacing or enclosing, which can pile over the
     * text around the name) once a letter and its accent have been composed into one (NFC); trimmed, at most
     * MAX_NAME_BYTES in UTF-8; null when nothing is left.
     */
    fun cleanName(raw: String?): String? {
        if (raw == null) return null
        val composed = Normalizer.normalize(without(raw, DROPPED_TYPES), Normalizer.Form.NFC)
        val shown = without(composed, DROPPED_TYPES + MARK_TYPES)
        return truncateUtf8(shown.trim(), MAX_NAME_BYTES).trim().takeIf { it.isNotEmpty() }
    }

    // [text] without the code points whose Unicode general category is one of [types].
    private fun without(text: String, types: Set<Byte>): String {
        val kept = StringBuilder()
        var at = 0
        while (at < text.length) {
            val codePoint = text.codePointAt(at)
            at += Character.charCount(codePoint)
            if (Character.getType(codePoint).toByte() !in types) kept.appendCodePoint(codePoint)
        }
        return kept.toString()
    }

    /** The app version as sent: printable ASCII, at most MAX_VALUE_CHARS; "dev" when nothing is left. */
    fun cleanApp(raw: String): String = raw.filter { it in ' '..'~' }.trim().take(MAX_VALUE_CHARS).ifEmpty { "dev" }

    /** The longest start of [text] that is at most [maxBytes] in UTF-8, never cutting a character in two. */
    fun truncateUtf8(text: String, maxBytes: Int): String {
        var bytes = 0
        var end = 0
        while (end < text.length) {
            val codePoint = text.codePointAt(end)
            val size = when {
                codePoint < 0x80 -> 1
                codePoint < 0x800 -> 2
                codePoint < 0x10000 -> 3
                else -> 4
            }
            if (bytes + size > maxBytes) break
            bytes += size
            end += Character.charCount(codePoint)
        }
        return text.substring(0, end)
    }

    /**
     * This PC's name (P6-R3): COMPUTERNAME, else the host name, cleaned and bounded as [cleanName]; FALLBACK_NAME when
     * neither gives one. Tests pass both lambdas; only Main uses the defaults.
     */
    fun pcName(
        env: (String) -> String? = System::getenv,
        host: () -> String? = { InetAddress.getLocalHost().hostName },
    ): String {
        cleanName(env("COMPUTERNAME"))?.let { return it }
        val fromHost = try {
            cleanName(host())
        } catch (e: Exception) {
            null
        }
        return fromHost ?: FALLBACK_NAME
    }
}
