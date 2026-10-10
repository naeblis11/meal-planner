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
}
