package com.naeblis11.mealplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** recipe_sync.patch_yaml_field's tests (TestPatchYamlField), plus CRLF, a byte order mark and nested keys. */
class YamlPatchTest {
    private fun load(text: String) = RecipeYaml.load(text) as Map<*, *>

    @Test
    fun replacesAnExistingLine() {
        assertEquals(
            "category: Main Dishes\nrecipe_name: X\n",
            YamlPatch.patchField("category: None\nrecipe_name: X\n", "category", "Main Dishes"),
        )
    }

    @Test
    fun insertsAtTheTopWhenTheFieldIsAbsent() {
        assertEquals("category: Desserts\nrecipe_name: X\n", YamlPatch.patchField("recipe_name: X\n", "category", "Desserts"))
    }

    @Test
    fun aValueWithAColonAndQuotesIsYamlEncoded() {
        val result = YamlPatch.patchField("subcategory: None\nrecipe_name: X\n", "subcategory", "Beef: \"the best\"")
        assertEquals("Beef: \"the best\"", load(result)["subcategory"])
        assertEquals("X", load(result)["recipe_name"])
    }

    @Test
    fun aBackslashIsInsertedLiterally() {
        val result = YamlPatch.patchField("subcategory: None\nrecipe_name: X\n", "subcategory", "Beef\\Pork")
        assertEquals("Beef\\Pork", load(result)["subcategory"])
    }

    @Test
    fun aLongValueStaysOnOneLineSoRepatchingLeavesNoFragments() {
        val first = YamlPatch.patchField("recipe_name: X\n", "category", "A".repeat(100))
        assertEquals(2, first.lines().count { it.isNotEmpty() })
        val second = YamlPatch.patchField(first, "category", "B".repeat(50))
        assertEquals("B".repeat(50), load(second)["category"])
        assertEquals("X", load(second)["recipe_name"])
        assertFalse('A' in second)
    }

    @Test
    fun onlyTheFirstTopLevelLineIsReplaced() {
        val raw = "notes:\n  recipe_uuid: nested\nrecipe_uuid: old\nrecipe_uuid: again\n"
        assertEquals(
            "notes:\n  recipe_uuid: nested\nrecipe_uuid: new-uuid-123\nrecipe_uuid: again\n",
            YamlPatch.patchField(raw, "recipe_uuid", "new-uuid-123"),
        )
    }

    @Test
    fun crlfLineEndingsAreKept() {
        assertEquals(
            "recipe_uuid: u-1\r\nrecipe_name: X\r\n",
            YamlPatch.patchField("recipe_uuid: None\r\nrecipe_name: X\r\n", "recipe_uuid", "u-1"),
        )
        assertEquals("recipe_uuid: u-1\r\nrecipe_name: X\r\n", YamlPatch.patchField("recipe_name: X\r\n", "recipe_uuid", "u-1"))
    }

    @Test
    fun aByteOrderMarkStaysFirst() {
        assertEquals("\uFEFFrecipe_uuid: u-1\nrecipe_name: X\n", YamlPatch.patchField("\uFEFFrecipe_name: X\n", "recipe_uuid", "u-1"))
        assertEquals(
            "\uFEFFrecipe_uuid: u-1\nrecipe_name: X\n",
            YamlPatch.patchField("\uFEFFrecipe_uuid: None\nrecipe_name: X\n", "recipe_uuid", "u-1"),
        )
    }

    @Test
    fun theWordNoneIsWrittenPlain() {
        assertEquals("rating: None\nrecipe_name: X\n", YamlPatch.patchField("rating: 4\nrecipe_name: X\n", "rating", "None"))
    }

    // A line above a `---` marker would start a second document, which RecipeYaml.load refuses (the desktop's
    // patchUuid then gives up and the file sits in Needs attention as "no recipe_uuid line").

    @Test
    fun insertsBelowALeadingDocumentMarker() {
        val result = YamlPatch.patchField("---\nrecipe_name: X\n", "recipe_uuid", "u-1")
        assertEquals("---\nrecipe_uuid: u-1\nrecipe_name: X\n", result)
        assertEquals(mapOf("recipe_uuid" to "u-1", "recipe_name" to "X"), load(result))
        assertEquals("--- \nrecipe_uuid: u-1\nrecipe_name: X\n", YamlPatch.patchField("--- \nrecipe_name: X\n", "recipe_uuid", "u-1"))
    }

    @Test
    fun insertsBelowADirectiveAndItsDocumentMarker() {
        val result = YamlPatch.patchField("%YAML 1.1\n---\nrecipe_name: X\n", "recipe_uuid", "u-1")
        assertEquals("%YAML 1.1\n---\nrecipe_uuid: u-1\nrecipe_name: X\n", result)
        assertEquals(mapOf("recipe_uuid" to "u-1", "recipe_name" to "X"), load(result))
        assertEquals(
            "%YAML 1.1\n%TAG ! tag:example.com,2026:\n---\nrecipe_uuid: u-1\nrecipe_name: X\n",
            YamlPatch.patchField("%YAML 1.1\n%TAG ! tag:example.com,2026:\n---\nrecipe_name: X\n", "recipe_uuid", "u-1"),
        )
    }

    @Test
    fun aLeadingCommentStaysBelowTheInsertedLine() {
        val result = YamlPatch.patchField("# Grandma's\nrecipe_name: X\n", "recipe_uuid", "u-1")
        assertEquals("recipe_uuid: u-1\n# Grandma's\nrecipe_name: X\n", result)
        assertEquals(mapOf("recipe_uuid" to "u-1", "recipe_name" to "X"), load(result))
        // Unless a marker follows it: comments and blank lines may come before `---`.
        val marked = YamlPatch.patchField("# Grandma's\n\n---\nrecipe_name: X\n", "recipe_uuid", "u-1")
        assertEquals("# Grandma's\n\n---\nrecipe_uuid: u-1\nrecipe_name: X\n", marked)
        assertEquals(mapOf("recipe_uuid" to "u-1", "recipe_name" to "X"), load(marked))
    }

    @Test
    fun crlfWithADocumentMarkerKeepsItsEndings() {
        val result = YamlPatch.patchField("---\r\nrecipe_name: X\r\n", "recipe_uuid", "u-1")
        assertEquals("---\r\nrecipe_uuid: u-1\r\nrecipe_name: X\r\n", result)
        assertEquals(mapOf("recipe_uuid" to "u-1", "recipe_name" to "X"), load(result))
    }

    @Test
    fun aMarkerThatEndsTheTextGetsALineBreakFirst() {
        assertEquals("---\nrecipe_uuid: u-1\n", YamlPatch.patchField("---", "recipe_uuid", "u-1"))
        assertEquals("\uFEFF---\nrecipe_uuid: u-1\nrecipe_name: X\n", YamlPatch.patchField("\uFEFF---\nrecipe_name: X\n", "recipe_uuid", "u-1"))
    }

    @Test
    fun aMarkerLineIsNotReplacedWhenTheFieldExistsBelowIt() {
        assertEquals("---\nrecipe_uuid: u-1\nrecipe_name: X\n", YamlPatch.patchField("---\nrecipe_uuid: None\nrecipe_name: X\n", "recipe_uuid", "u-1"))
    }
}
