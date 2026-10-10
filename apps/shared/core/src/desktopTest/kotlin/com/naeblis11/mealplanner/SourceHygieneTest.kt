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
            .onEnter { it.name != "build" && !it.name.startsWith(".") }
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
            .onEnter { it.name != "build" && !it.name.startsWith(".") }
            .filter { it.isFile && it.extension == "kt" && isShippedSource(it.invariantSeparatorsPath) }
            .flatMap { file ->
                file.readLines(Charsets.UTF_8).withIndex()
                    .filter { (_, line) -> banned.containsMatchIn(line) }
                    .map { (i, _) -> "${file.relativeTo(root).invariantSeparatorsPath}:${i + 1}" }
            }
            .toList()
        assertEquals("Android's regex engine rejects these flags", emptyList<String>(), offenders)
    }

    /**
     * The ported parsers' digit and space classes are Python's: any Unicode digit or space (Py.RE_DIGIT,
     * Py.RE_SPACE). A bare `\d` or `\s` is ASCII only on the desktop JVM but Unicode on the phone's ICU, so a
     * pattern spelled with one reads "\u0663" differently on the two, and no unit test (the desktop JVM) would
     * see it. Ban the bare classes in shared/core's shipped sources; ASCII by intent is spelled `[0-9]`.
     */
    @Test
    fun noBareRegexClassesInTheSharedCore() {
        val root = File(System.getProperty("srcDir") ?: error("srcDir is not set; run through Gradle"))
        val bare = Regex("""\\[dswDSW]""")
        val offenders = root.walkTopDown()
            .onEnter { it.name != "build" && !it.name.startsWith(".") }
            .filter { it.isFile && it.extension == "kt" && "/shared/core/src/commonMain/" in it.invariantSeparatorsPath }
            .flatMap { file ->
                file.readLines(Charsets.UTF_8).withIndex()
                    .filter { (_, line) -> bare.containsMatchIn(code(line)) }
                    .map { (i, _) -> "${file.relativeTo(root).invariantSeparatorsPath}:${i + 1}" }
            }
            .toList()
        assertEquals("Spell Python's classes as Py.RE_DIGIT / Py.RE_SPACE, or ASCII as [0-9]", emptyList<String>(), offenders)
    }

    // The line without its comment, so a KDoc or a trailing comment may still name `\d`.
    private fun code(line: String): String {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("*") || trimmed.startsWith("/*")) return ""
        return line.substringBefore("//")
    }

    /** Code that runs on the phone: src/main, src/commonMain, src/androidMain (never a test source set). */
    private fun isShippedSource(path: String): Boolean =
        Regex("/src/(main|commonMain|androidMain)/").containsMatchIn(path)
}
