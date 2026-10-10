package com.naeblis11.mealplanner.domain

import java.math.BigDecimal
import java.math.BigInteger
import java.nio.charset.Charset

/**
 * The Python built-ins the ported modules lean on, reproduced so the phone
 * reads recipe text exactly as the Pi does. Checked against Python itself by
 * tests/fixtures/parity/pycompat.json.
 */
object Py {
    // str.splitlines() boundaries (\r\n is handled as one break).
    private const val LINE_BREAKS = "\n\r\u000B\u000C\u001C\u001D\u001E\u0085\u2028\u2029"
    // int() reads any Unicode digit ("\u0663" is 3), as BigInteger(String) does.
    private val INT_TEXT = Regex("[+-]?$RE_DIGIT+(?:_$RE_DIGIT+)*")
    /** CPython's default int/str conversion limit: `int()` of a longer digit run raises ValueError. */
    const val MAX_INT_DIGITS = 4300

    /** Whether Python's `int()` would refuse [digits] (optional sign, digits of any script, underscores) as too long. */
    fun overIntLimit(digits: String): Boolean = digits.count { it.isDigit() } > MAX_INT_DIGITS

    private val CP1252: Charset = Charset.forName("windows-1252")

    /**
     * Python's `\d` and `\s` for str patterns, spelled out. The phone's regex engine (ICU)
     * rejects the Unicode-classes flag that gives the desktop JVM the same classes (see
     * SourceHygieneTest), so patterns use these and match identically on the phone, in
     * unit tests and on the Pi.
     */
    const val RE_DIGIT = "\\p{Nd}"
    const val RE_SPACE = "[\\t\\n\\u000B\\f\\r\\u001C-\\u001F \\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]"

    /** `str.isspace()` for one character: Kotlin's whitespace plus NEL (U+0085). */
    fun isSpace(c: Char): Boolean = c.isWhitespace() || c == '\u0085'

    fun strip(text: String): String = text.trim { isSpace(it) }

    fun lstrip(text: String): String = text.trimStart { isSpace(it) }

    fun rstrip(text: String): String = text.trimEnd { isSpace(it) }

    /**
     * `str.split(None, maxSplit)`: runs of whitespace separate, no empty
     * parts. A negative [maxSplit] means no limit. As in Python, the
     * remainder after the last split keeps its trailing whitespace.
     */
    fun split(text: String, maxSplit: Int = -1): List<String> {
        val parts = mutableListOf<String>()
        var i = 0
        while (true) {
            while (i < text.length && isSpace(text[i])) i++
            if (i >= text.length) break
            if (maxSplit >= 0 && parts.size == maxSplit) {
                parts += text.substring(i)
                break
            }
            val start = i
            while (i < text.length && !isSpace(text[i])) i++
            parts += text.substring(start, i)
        }
        return parts
    }

    /** `str.splitlines()`: Python's full set of line breaks, and no empty line after a final one. */
    fun splitLines(text: String): List<String> {
        val lines = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') {
                lines += text.substring(start, i)
                i += 2
                start = i
            } else if (c in LINE_BREAKS) {
                lines += text.substring(start, i)
                i++
                start = i
            } else {
                i++
            }
        }
        if (start < text.length) lines += text.substring(start)
        return lines
    }

    /** `str.title()`: a letter after a cased letter is lowercased, any other is titlecased ("DON'T" -> "Don'T"). */
    fun title(text: String): String {
        val out = StringBuilder()
        var previousCased = false
        for (c in text) {
            out.append(if (previousCased) c.lowercase() else c.titlecase())
            previousCased = c.isUpperCase() || c.isLowerCase() || c.isTitleCase()
        }
        return out.toString()
    }

    /**
     * `str(value)` for the values YAML produces. Floats print like Python's
     * repr ("2.0", "0.1", "-0.0"); very large or tiny floats would print
     * with an exponent differently, which recipe files never contain.
     */
    fun str(value: Any?): String = when (value) {
        null -> "None"
        is Boolean -> if (value) "True" else "False"
        is Double -> floatRepr(value)
        is Float -> floatRepr(value.toDouble())
        else -> value.toString()
    }

    private fun floatRepr(d: Double): String = when {
        d.isNaN() -> "nan"
        d.isInfinite() -> if (d > 0) "inf" else "-inf"
        d == 0.0 -> if (1.0 / d < 0) "-0.0" else "0.0"
        d == Math.rint(d) && Math.abs(d) < 1e16 -> BigDecimal(d).toBigInteger().toString() + ".0"
        else -> d.toString()
    }

    /** Python truthiness: None, False, zero, and empty strings/lists/maps are false. */
    fun truthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is String -> value.isNotEmpty()
        is Collection<*> -> value.isNotEmpty()
        is Map<*, *> -> value.isNotEmpty()
        is Int -> value != 0
        is Long -> value != 0L
        is BigInteger -> value.signum() != 0
        is Double -> value != 0.0
        else -> true
    }

    /** `int(text)` for a string: optional sign, digits with single underscores between, surrounding whitespace allowed; null where Python raises. */
    fun parseInt(text: String): BigInteger? {
        val stripped = strip(text)
        if (!INT_TEXT.matches(stripped) || overIntLimit(stripped)) return null
        return BigInteger(stripped.replace("_", ""))
    }

    /** A Python int as the JSON/YAML layers expect it: Long when it fits, else BigInteger. */
    fun intValue(n: BigInteger): Any = if (n.bitLength() < 64) n.toLong() else n

    /** `bytes.decode("cp1252", errors="replace")`: the five undefined bytes become U+FFFD. */
    fun decodeCp1252(bytes: ByteArray): String = String(bytes, CP1252)
}
