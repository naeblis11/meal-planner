package com.naeblis11.mealplanner.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.ui.theme.MealPalette
import com.naeblis11.mealplanner.ui.theme.MealSpacing
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared theme is DESIGN.md's tokens (P3-R4): checked against the file, so the two can't drift apart. */
class DesignTokensTest {
    // DESIGN.md's YAML front matter, between its first two "---" lines.
    private val front: List<String> by lazy {
        val lines = File(System.getProperty("designDoc") ?: error("designDoc is not set; run through Gradle")).readLines()
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" } + 1
        lines.subList(1, end)
    }

    // The quoted values of one top-level block ("colors:", "spacing:").
    private fun block(name: String): Map<String, String> {
        val start = front.indexOf("$name:")
        val values = linkedMapOf<String, String>()
        for (line in front.drop(start + 1)) {
            if (!line.startsWith("  ")) break
            val match = Regex("""^  ([a-z0-9-]+): "([^"]*)"$""").find(line) ?: continue
            values[match.groupValues[1]] = match.groupValues[2]
        }
        return values
    }

    // DESIGN.md's token names, and the palette field each one is.
    private fun tokensOf(p: MealPalette): Map<String, Color> = mapOf(
        "ink" to p.ink,
        "paper" to p.paper,
        "paper-alt" to p.paperAlt,
        "surface" to p.paper,
        "line" to p.line,
        "line-soft" to p.lineSoft,
        "muted" to p.muted,
        "muted-2" to p.muted2,
        "accent" to p.accent,
        "accent-hover" to p.accentHover,
        "accent-bright" to p.accentBright,
        "accent-tint" to p.accentTint,
        "danger" to p.danger,
        "danger-hover" to p.dangerHover,
        "danger-tint" to p.dangerTint,
        "slot-breakfast-tint" to p.breakfastTint,
        "slot-breakfast-ink" to p.breakfastInk,
        "slot-lunch-tint" to p.lunchTint,
        "slot-lunch-ink" to p.lunchInk,
        "slot-dinner-tint" to p.dinnerTint,
        "slot-dinner-ink" to p.dinnerInk,
    )

    private fun assertBlockIs(block: String, palette: MealPalette) {
        val expected = tokensOf(palette)
        val tokens = block(block)
        assertEquals(block, expected.keys, tokens.keys)
        for ((name, colour) in expected) {
            assertEquals("$block.$name", Color(0xFF000000 or tokens.getValue(name).removePrefix("#").toLong(16)), colour)
        }
    }

    @Test
    fun theThemeColoursAreDesignMdsTokens() = assertBlockIs("colors", MealPalette.Light)

    /** Owner, 2026-10-12: dark mode is DESIGN.md's dark-colors, the same names with their after-dark values. */
    @Test
    fun theDarkThemeColoursAreDesignMdsDarkTokens() = assertBlockIs("dark-colors", MealPalette.Dark)

    // WCAG 2.1's contrast ratio between two opaque colours.
    private fun contrast(a: Color, b: Color): Double {
        fun channel(c: Float) = if (c <= 0.03928f) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        fun luminance(c: Color) = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    /** DESIGN.md, Dark Mode: every text pairing the screens use meets WCAG AA (4.5:1) after dark. */
    @Test
    fun everyDarkTextPairingMeetsWcagAa() {
        val p = MealPalette.Dark
        val pairs = mapOf(
            "ink on paper" to (p.ink to p.paper),
            "ink on paper-alt" to (p.ink to p.paperAlt),
            "muted on paper" to (p.muted to p.paper),
            "muted on paper-alt" to (p.muted to p.paperAlt),
            "muted-2 on paper" to (p.muted2 to p.paper),
            "accent on paper" to (p.accent to p.paper),
            "paper on accent" to (p.paper to p.accent),
            "accent-hover on accent-tint" to (p.accentHover to p.accentTint),
            "danger on paper" to (p.danger to p.paper),
            "paper on danger" to (p.paper to p.danger),
            "danger-hover on danger-tint" to (p.dangerHover to p.dangerTint),
            "breakfast ink on tint" to (p.breakfastInk to p.breakfastTint),
            "lunch ink on tint" to (p.lunchInk to p.lunchTint),
            "dinner ink on tint" to (p.dinnerInk to p.dinnerTint),
        )
        for ((name, pair) in pairs) {
            val ratio = contrast(pair.first, pair.second)
            assertTrue("$name is only ${"%.2f".format(ratio)}:1", ratio >= 4.5)
        }
    }

    @Test
    fun theSpacingTokensAreDesignMds() {
        val tokens = block("spacing").mapValues { (_, rem) -> (rem.removeSuffix("rem").toFloat() * 16).dp }
        assertEquals(mapOf("row" to MealSpacing.Row, "section" to MealSpacing.Section, "band" to MealSpacing.Band), tokens)
    }
}
