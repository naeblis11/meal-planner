package com.naeblis11.mealplanner.sync

/**
 * A name as a device sends it when it pairs, and as the phone shows a PC it found (LAN sync stage 2c). It keeps no
 * control, format (bidi, zero-width), separator, private-use, unassigned or lone surrogate characters, and is trimmed.
 * It is at most PairingWire.MAX_NAME UTF-16 characters (what PairingWire.isName counts) and is never cut through a
 * character (an emoji is two of them). It is [fallback] when nothing is left, so the result always passes
 * PairingWire.isName.
 */
object DeviceNames {
    const val PHONE = "Android phone"

    private val DROPPED = setOf(
        Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
        Character.PRIVATE_USE, Character.UNASSIGNED, Character.SURROGATE,
    ).map { it.toInt() }.toSet()

    fun clean(raw: String?, fallback: String = PHONE): String {
        if (raw == null) return fallback
        val kept = StringBuilder()
        var at = 0
        while (at < raw.length) {
            val codePoint = raw.codePointAt(at)
            at += Character.charCount(codePoint)
            if (Character.getType(codePoint) !in DROPPED) kept.appendCodePoint(codePoint)
        }
        val text = kept.toString().trim()
        var end = 0
        while (end < text.length) {
            val next = end + Character.charCount(text.codePointAt(end))
            if (next > PairingWire.MAX_NAME) break
            end = next
        }
        return text.substring(0, end).trim().ifEmpty { fallback }
    }
}
