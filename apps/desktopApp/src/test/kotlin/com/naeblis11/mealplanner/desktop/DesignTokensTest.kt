package com.naeblis11.mealplanner.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.ui.theme.MealColors
import com.naeblis11.mealplanner.ui.theme.MealSpacing
import java.io.File
import org.junit.Assert.assertEquals
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

    @Test
    fun theThemeColoursAreDesignMdsTokens() {
        val expected = mapOf(
            "ink" to MealColors.Ink,
            "paper" to MealColors.Paper,
            "paper-alt" to MealColors.PaperAlt,
            "surface" to MealColors.Paper,
            "line" to MealColors.Line,
            "line-soft" to MealColors.LineSoft,
            "muted" to MealColors.Muted,
            "muted-2" to MealColors.Muted2,
            "accent" to MealColors.Accent,
            "accent-hover" to MealColors.AccentHover,
            "accent-bright" to MealColors.AccentBright,
            "accent-tint" to MealColors.AccentTint,
            "danger" to MealColors.Danger,
            "danger-hover" to MealColors.DangerHover,
            "danger-tint" to MealColors.DangerTint,
            "slot-breakfast-tint" to MealColors.BreakfastTint,
            "slot-breakfast-ink" to MealColors.BreakfastInk,
            "slot-lunch-tint" to MealColors.LunchTint,
            "slot-lunch-ink" to MealColors.LunchInk,
            "slot-dinner-tint" to MealColors.DinnerTint,
            "slot-dinner-ink" to MealColors.DinnerInk,
        )
        val tokens = block("colors")
        assertEquals(expected.keys, tokens.keys)
        for ((name, colour) in expected) {
            assertEquals(name, Color(0xFF000000 or tokens.getValue(name).removePrefix("#").toLong(16)), colour)
        }
    }

    @Test
    fun theSpacingTokensAreDesignMds() {
        val tokens = block("spacing").mapValues { (_, rem) -> (rem.removeSuffix("rem").toFloat() * 16).dp }
        assertEquals(mapOf("row" to MealSpacing.Row, "section" to MealSpacing.Section, "band" to MealSpacing.Band), tokens)
    }
}
