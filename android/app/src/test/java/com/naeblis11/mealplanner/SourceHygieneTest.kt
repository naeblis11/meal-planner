package com.naeblis11.mealplanner

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** Invisible or non-ASCII characters in source are easy to miss in review; spell them as escapes. */
class SourceHygieneTest {
    @Test
    fun sourcesAreAscii() {
        val root = File(System.getProperty("srcDir") ?: error("srcDir is not set; run through Gradle"))
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "kts", "xml") }
            .flatMap { file ->
                file.readLines(Charsets.UTF_8).withIndex()
                    .filter { (_, line) -> line.any { it.code > 127 } }
                    .map { (i, _) -> "${file.relativeTo(root).invariantSeparatorsPath}:${i + 1}" }
            }
            .toList()
        assertEquals("Write these characters as escapes", emptyList<String>(), offenders)
    }

    /**
     * Unit tests run on the desktop JVM's regex engine, but the phone uses ICU, which
     * rejects the `(?U)` / UNICODE_CHARACTER_CLASS flag (Android's Pattern docs: "not
     * supported on Android"). A pattern using it compiles here and throws on the phone,
     * so no test would catch it: ban it in the app's sources instead.
     */
    @Test
    fun noRegexFlagsThePhoneRejects() {
        val root = File(System.getProperty("srcDir") ?: error("srcDir is not set; run through Gradle"))
        val banned = Regex("""\(\?[a-zA-Z]*U|UNICODE_CHARACTER_CLASS|CANON_EQ""")
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "/src/main/" in it.invariantSeparatorsPath }
            .flatMap { file ->
                file.readLines(Charsets.UTF_8).withIndex()
                    .filter { (_, line) -> banned.containsMatchIn(line) }
                    .map { (i, _) -> "${file.relativeTo(root).invariantSeparatorsPath}:${i + 1}" }
            }
            .toList()
        assertEquals("Android's regex engine rejects these flags", emptyList<String>(), offenders)
    }
}
