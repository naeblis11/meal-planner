package com.naeblis11.mealplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The editors' moves, as static/ingredient-editor.js and step-editor.js make them. */
class EditorMovesTest {
    @Test
    fun aSplitKeepsWhatIsBeforeTheCursorAndMovesTheRestToANewStep() {
        assertEquals("Brown the onions." to "Add the stock.", EditorMoves.splitStep("Brown the onions. Add the stock.", 17))
        assertEquals("Brown the onions." to "Add the stock.", EditorMoves.splitStep("Brown the onions.  Add the stock.", 18))
    }

    @Test
    fun theCursorAtTheEndAddsAnEmptyStepAfter() {
        assertEquals("Simmer." to "", EditorMoves.splitStep("Simmer.", 7))
        assertEquals("" to "", EditorMoves.splitStep("", 0))
    }

    @Test
    fun theCursorAtTheStartLeavesTheStepAlone() {
        assertNull(EditorMoves.splitStep("Simmer.", 0))
        assertNull(EditorMoves.splitStep("   Simmer.", 2))
    }

    @Test
    fun textOfOnlySpacesSplitsIntoTwoEmptySteps() {
        assertEquals("" to "", EditorMoves.splitStep("   ", 1))
        assertEquals("" to "", EditorMoves.splitStep("   ", 3))
        assertEquals("" to "", EditorMoves.splitStep("   ", 0))
    }

    @Test
    fun bothHalvesAreTrimmed() {
        // Two spaces before "Brown it.", two between it and "Then": the cursor sits at the start of "Then".
        assertEquals("Brown it." to "Then", EditorMoves.splitStep("  Brown it.  Then", 13))
        // The cursor in the middle of the first run of spaces: the same two halves.
        assertEquals("Brown it." to "Then", EditorMoves.splitStep("  Brown it.  Then", 12))
    }

    @Test
    fun aLineBreakAtTheCursorIsTrimmedAway() {
        assertEquals("One." to "Two.", EditorMoves.splitStep("One.\r\nTwo.", 4))
        // Between the CR and the LF, as a text field can put it.
        assertEquals("One." to "Two.", EditorMoves.splitStep("One.\r\nTwo.", 5))
        assertEquals("One." to "Two.", EditorMoves.splitStep("One.\nTwo.", 5))
    }

    @Test
    fun theCursorCountsUtf16UnitsSoAnEmojiIsTwoOfThem() {
        // U+1F600, one code point and two UTF-16 units.
        val smile = String(Character.toChars(0x1F600))
        assertEquals("Hi $smile" to "there", EditorMoves.splitStep("Hi $smile there", 5))
        assertEquals("A$smile" to "B", EditorMoves.splitStep("A${smile}B", 3))
        // A cursor between the two halves of the pair cuts the pair, as the server's JavaScript string slicing does.
        assertEquals("A\uD83D" to "\uDE00B", EditorMoves.splitStep("A${smile}B", 2))
    }

    @Test
    fun aCursorOutsideTheTextIsKeptInside() {
        assertEquals("Simmer." to "", EditorMoves.splitStep("Simmer.", 99))
        assertNull(EditorMoves.splitStep("Simmer.", -1))
    }

    @Test
    fun upAndDownMoveOnePlaceAndStopAtTheEnds() {
        val rows = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), EditorMoves.moved(rows, 0, 1))
        assertEquals(listOf("a", "c", "b"), EditorMoves.moved(rows, 2, -1))
        assertEquals(rows, EditorMoves.moved(rows, 0, -1))
        assertEquals(rows, EditorMoves.moved(rows, 2, 1))
        assertEquals(rows, EditorMoves.moved(rows, -1, 1))
    }

    @Test
    fun aDropPutsTheRowWhereItLandsAndKeepsTheRestInOrder() {
        val rows = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "a", "d"), EditorMoves.movedTo(rows, 0, 2))
        assertEquals(listOf("d", "a", "b", "c"), EditorMoves.movedTo(rows, 3, 0))
        assertEquals(rows, EditorMoves.movedTo(rows, 1, 1))
        assertEquals(rows, EditorMoves.movedTo(rows, 1, 9))
    }

    @Test
    fun aDraggedRowLandsPastEveryMiddleItCrosses() {
        // Rows 100 high: their middles at 50, 150, 250, 350.
        val middles = listOf(50f, 150f, 250f, 350f)
        assertEquals(0, EditorMoves.dropIndex(middles, 0, 40f))
        assertEquals(1, EditorMoves.dropIndex(middles, 0, 120f))
        assertEquals(3, EditorMoves.dropIndex(middles, 0, 5_000f))
        assertEquals(2, EditorMoves.dropIndex(middles, 3, -120f))
        assertEquals(0, EditorMoves.dropIndex(middles, 3, -5_000f))
    }
}
