package com.naeblis11.mealplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** difflib.SequenceMatcher(None, a, b).ratio(), checked against values Python printed. */
class PyDifflibTest {
    @Test
    fun ratiosMatchPython() {
        assertEquals(0.75, PyDifflib.ratio("abcd", "bcde"), 0.0)
        assertEquals(0.9, PyDifflib.ratio("pork chaps", "pork chops"), 0.0)
        assertEquals(0.9714285714285714, PyDifflib.ratio("chicken stroganof", "chicken stroganoff"), 0.0)
        assertEquals(0.3157894736842105, PyDifflib.ratio("lasagna", "banana bread"), 0.0)
    }

    @Test
    fun emptyStringsAreAlikeAndNothingMatchesEmpty() {
        assertEquals(1.0, PyDifflib.ratio("", ""), 0.0)
        assertEquals(0.0, PyDifflib.ratio("a", ""), 0.0)
    }

    @Test
    fun severalBlocksAddUp() {
        assertEquals(0.5185185185185185, PyDifflib.ratio("ground beef tacos", "fish tacos"), 0.0)
        assertEquals(0.5, PyDifflib.ratio("banana bread", "bread banana"), 0.0)
        assertEquals(0.7272727272727273, PyDifflib.ratio("chicken stroganoff", "beef stroganoff"), 0.0)
        assertEquals(0.8888888888888888, PyDifflib.ratio("abxcd", "abcd"), 0.0)
        assertEquals(0.6666666666666666, PyDifflib.ratio("qabxcd", "abycdf"), 0.0)
    }

    @Test
    fun popularElementsOfALongSecondStringAreNotIndexed() {
        // autojunk: in a b of 200 or more, anything making up more than 1% of it (plus one) isn't indexed, so only
        // the extension of an empty match at the very start finds anything.
        val a = "ab".repeat(150)
        val b = "ab".repeat(120) + "c".repeat(60)
        assertEquals(0.8, PyDifflib.ratio(a, b), 0.0)
        assertEquals(0.0, PyDifflib.ratio("x$a", b), 0.0)
        assertEquals(0.8, PyDifflib.ratio(b, a), 0.0)
    }
}
