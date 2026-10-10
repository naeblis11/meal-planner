package com.naeblis11.mealplanner.domain

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of tests/test_recipe_extraction.py (the photo download is tested on the desktop), then cases Python printed. */
class WebExtractionTest {
    private fun parse(line: String) = WebExtraction.parseIngredientLine(line)

    // parse_ingredient_line

    @Test
    fun aSimpleAmountAndUnit() {
        assertEquals(WebIngredient("flour", "2", "cups", null), parse("2 cups flour"))
    }

    @Test
    fun aCommaSplitsOffANote() {
        assertEquals(WebIngredient("flour", "2", "cups", listOf("sifted")), parse("2 cups flour, sifted"))
    }

    @Test
    fun aParentheticalSizeBecomesANote() {
        assertEquals(
            WebIngredient("black beans", "1", "can", listOf("15 oz", "rinsed and drained")),
            parse("1 (15 oz) can black beans, rinsed and drained"),
        )
    }

    @Test
    fun noLeadingAmountKeepsTheWholeLineAsTheName() {
        assertEquals(WebIngredient("Kosher salt, to taste", "", "", null), parse("Kosher salt, to taste"))
    }

    @Test
    fun aUnicodeFraction() {
        val result = parse("\u00BD cup sugar")
        assertEquals("1/2", result.amount)
        assertEquals("cup", result.unit)
        assertEquals("sugar", result.name)
    }

    @Test
    fun aTwoWordUnit() {
        val result = parse("1 fl oz vodka")
        assertEquals("fl oz", result.unit)
        assertEquals("vodka", result.name)
    }

    @Test
    fun aRangeKeepsOnlyTheFirstNumber() {
        assertEquals(WebIngredient("butter", "1", "tbsp", null), parse("1-2 tbsp butter"))
    }

    @Test
    fun onlyTheFirstCommaSplitsALineWithSeveral() {
        val result = parse("3 large chicken breasts, boneless, skinless")
        assertEquals("chicken breasts", result.name)
        assertEquals(listOf("boneless, skinless"), result.notes)
    }

    @Test
    fun aMixedNumberAmount() {
        val result = parse("1 1/2 cups sugar")
        assertEquals("1 1/2", result.amount)
        assertEquals("cups", result.unit)
    }

    @Test
    fun aSpacedUnicodeMixedNumber() {
        assertEquals(WebIngredient("flour", "1 1/2", "cups", null), parse("1 \u00BD cups flour"))
    }

    @Test
    fun aParentheticalAfterTheUnitBecomesANote() {
        val result = parse("1 can (15 oz) black beans, rinsed")
        assertEquals("black beans", result.name)
        assertEquals("can", result.unit)
        assertTrue("15 oz" in result.notes!!)
        assertTrue("rinsed" in result.notes)
    }

    @Test
    fun anAbbreviatedUnitWithATrailingPeriod() {
        val result = parse("1 tbsp. olive oil")
        assertEquals("tbsp", result.unit)
        assertEquals("olive oil", result.name)
    }

    @Test
    fun lbWithATrailingPeriod() {
        val result = parse("1 lb. ground beef")
        assertEquals("lb", result.unit)
        assertEquals("ground beef", result.name)
    }

    @Test
    fun tspWithATrailingPeriod() {
        val result = parse("1 tsp. vanilla extract")
        assertEquals("tsp", result.unit)
        assertEquals("vanilla extract", result.name)
    }

    @Test
    fun aUnitFollowedStraightByACommaNote() {
        assertEquals(WebIngredient("", "2", "cups", listOf("sifted")), parse("2 cups, sifted"))
    }

    // domain_from_url

    @Test
    fun theDomainOfAnAddress() {
        assertEquals("www.example.com", WebExtraction.domainFromUrl("https://www.example.com/a/recipe"))
    }

    @Test
    fun noAddressIsAnEmptyDomain() {
        assertEquals("", WebExtraction.domainFromUrl(""))
        assertEquals("", WebExtraction.domainFromUrl(null))
    }

    // build_recipe_data

    private fun payload(vararg extra: Pair<String, Any?>): Map<Any?, Any?> = linkedMapOf<Any?, Any?>(
        "name" to "Test Soup",
        "ingredients" to listOf("2 cups flour, sifted", "Kosher salt, to taste"),
        "steps" to listOf("Boil water.", "Add flour."),
    ).apply { putAll(extra) }

    @Test
    fun itBuildsTheWholeOrfRecipe() {
        val data = WebExtraction.buildRecipeData(
            payload("yield_text" to "4 servings", "author" to "Jane Doe", "source_url" to "https://example.com/soup"),
            "test-uuid",
        )
        assertEquals("test-uuid", data["recipe_uuid"])
        assertEquals("Test Soup", data["recipe_name"])
        assertEquals("None", data["category"])
        assertEquals("None", data["subcategory"])
        val ingredients = data["ingredients"] as List<*>
        assertEquals(2, ingredients.size)
        assertTrue((ingredients[0] as Map<*, *>).containsKey("flour"))
        assertEquals(listOf(mapOf("step" to "Boil water."), mapOf("step" to "Add flour.")), data["steps"])
        assertEquals(listOf(mapOf("amount" to 4L, "unit" to "servings")), data["yields"])
        assertEquals("Jane Doe", data["author"])
        assertEquals("https://example.com/soup", data["source_url"])
        assertFalse(data.containsKey("image"))
    }

    @Test
    fun noYieldsWhenTheYieldTextHasNoNumber() {
        assertFalse(WebExtraction.buildRecipeData(payload("yield_text" to "Serves a crowd"), "test-uuid").containsKey("yields"))
    }

    @Test
    fun noAuthorOrSourceWhenTheyAreAbsent() {
        val data = WebExtraction.buildRecipeData(payload(), "test-uuid")
        assertFalse(data.containsKey("author"))
        assertFalse(data.containsKey("source_url"))
    }

    // Extra cases: the expected values are what recipe_extraction.py returned for the same input.

    @Test
    fun moreLinesAgreeWithPython() {
        assertEquals(WebIngredient("eggs", "3", "", null), parse("3 eggs"))
        assertEquals(WebIngredient("ground beef", "1 1/2", "lb", listOf("browned")), parse("1 1/2 lb. ground beef, browned"))
        assertEquals(WebIngredient("garlic", "2", "cloves", listOf("minced")), parse("2 to 3 cloves garlic, minced"))
        assertEquals(WebIngredient("Salt", "", "", null), parse("Salt"))
        assertEquals(WebIngredient("potatoes", "1.5", "kg", null), parse("1.5 kg potatoes"))
        assertEquals(WebIngredient("", "", "", null), parse("  "))
        assertEquals(WebIngredient("milk", "1 3/4", "cups", null), parse("1 \u00BE cups milk"))
        assertEquals(WebIngredient("eggs (room temperature)", "4", "large", null), parse("4 large eggs (room temperature)"))
    }

    @Test
    fun moreAddressesAgreeWithPythonExceptANonString() {
        assertEquals("user:pw@host:8080", WebExtraction.domainFromUrl("http://user:pw@host:8080/x?y#z"))
        assertEquals("", WebExtraction.domainFromUrl("example.com/soup"))
        assertEquals("Ex.com", WebExtraction.domainFromUrl("HTTPS://Ex.com"))
        assertEquals("cdn.site.org", WebExtraction.domainFromUrl("//cdn.site.org/a"))
        assertEquals("", WebExtraction.domainFromUrl("mailto:x@y"))
        assertEquals("a.b", WebExtraction.domainFromUrl("  https://a.b/c"))
        // Python raises on a non-string address; Kotlin returns "" (the extension only ever sends strings).
        assertEquals("", WebExtraction.domainFromUrl(42L))
    }

    @Test
    fun aCompleteRecipeHasANameAndLinesOfIngredientsAndSteps() {
        assertTrue(WebExtraction.isComplete(payload()))
        assertFalse(WebExtraction.isComplete(payload("name" to "")))
        assertFalse(WebExtraction.isComplete(payload("name" to "   ")))
        assertFalse(WebExtraction.isComplete(payload("name" to null)))
        assertFalse(WebExtraction.isComplete(payload("ingredients" to emptyList<String>())))
        assertFalse(WebExtraction.isComplete(payload("ingredients" to "2 cups flour")))
        assertFalse(WebExtraction.isComplete(payload("ingredients" to listOf("2 cups flour", 5L))))
        assertFalse(WebExtraction.isComplete(payload("steps" to emptyList<String>())))
        assertFalse(WebExtraction.isComplete(payload("steps" to null)))
        assertFalse(WebExtraction.isComplete(emptyMap<Any?, Any?>()))
    }

    @Test
    fun everyStepMustBeText() {
        assertFalse(WebExtraction.isComplete(payload("steps" to listOf("Boil water.", 5L))))
        assertFalse(WebExtraction.isComplete(payload("steps" to listOf("Boil water.", null))))
        assertFalse(WebExtraction.isComplete(payload("steps" to "Boil water.")))
    }

    @Test
    fun anOverCapPayloadIsIncomplete() {
        // Python has no caps; these keep hostile input away from the regexes.
        val atCap = "a".repeat(2000)
        val over = "a".repeat(2001)
        assertTrue(WebExtraction.isComplete(payload("name" to atCap)))
        assertFalse(WebExtraction.isComplete(payload("name" to over)))
        assertTrue(WebExtraction.isComplete(payload("ingredients" to listOf(atCap))))
        assertFalse(WebExtraction.isComplete(payload("ingredients" to listOf("2 cups flour", over))))
        assertTrue(WebExtraction.isComplete(payload("steps" to listOf(atCap))))
        assertFalse(WebExtraction.isComplete(payload("steps" to listOf("Boil.", over))))
        assertTrue(WebExtraction.isComplete(payload("ingredients" to List(500) { "1 egg" })))
        assertFalse(WebExtraction.isComplete(payload("ingredients" to List(501) { "1 egg" })))
        assertTrue(WebExtraction.isComplete(payload("steps" to List(500) { "Stir." })))
        assertFalse(WebExtraction.isComplete(payload("steps" to List(501) { "Stir." })))
    }

    @Test
    fun anOverCapYieldAuthorOrSourceIsIncomplete() {
        // parseYieldText reads every leading digit as one number, so an unbounded yield_text is refused before it.
        val atCap = "a".repeat(2000)
        val over = "a".repeat(2001)
        for (field in listOf("yield_text", "author")) {
            assertTrue(field, WebExtraction.isComplete(payload(field to atCap)))
            assertFalse(field, WebExtraction.isComplete(payload(field to over)))
            assertEquals(field, WebPayloadCheck.FIELD_TOO_LONG, WebExtraction.checkPayload(payload(field to over)))
            assertTrue(field, WebExtraction.isComplete(payload(field to null)))
            assertTrue(field, WebExtraction.isComplete(payload(field to "")))
        }
        assertTrue(WebExtraction.isComplete(payload("yield_text" to "4".repeat(2000))))
        assertFalse(WebExtraction.isComplete(payload("yield_text" to "4".repeat(2001))))
        assertTrue(WebExtraction.isComplete(payload("yield_text" to 4L)))
        assertEquals(
            WebPayloadCheck.FIELD_TOO_LONG,
            WebExtraction.checkPayload(payload("yield_text" to java.math.BigInteger("9".repeat(2001)))),
        )
    }

    @Test
    fun theAddressHasItsOwnLongerCap() {
        // A page's address (tracking parameters and all) may well pass 2000 characters; 8 KB is far past any real one.
        val atCap = "https://www.example.com/soup?" + "a".repeat(8192 - 29)
        assertEquals(8192, atCap.length)
        assertEquals(WebPayloadCheck.COMPLETE, WebExtraction.checkPayload(payload("source_url" to atCap)))
        assertEquals(WebPayloadCheck.ADDRESS_TOO_LONG, WebExtraction.checkPayload(payload("source_url" to atCap + "a")))
        assertTrue(WebExtraction.isComplete(payload("source_url" to null)))
        assertTrue(WebExtraction.isComplete(payload("source_url" to "")))
    }

    @Test
    fun thePhotosAddressHasTheSameCapAsThePages() {
        // The desktop fetches this address; without a cap it was the one field the extension could send unbounded.
        val atCap = "https://www.example.com/soup.jpg?" + "a".repeat(8192 - 33)
        assertEquals(8192, atCap.length)
        assertEquals(WebPayloadCheck.COMPLETE, WebExtraction.checkPayload(payload("image_url" to atCap)))
        assertEquals(WebPayloadCheck.IMAGE_ADDRESS_TOO_LONG, WebExtraction.checkPayload(payload("image_url" to atCap + "a")))
        assertFalse(WebExtraction.isComplete(payload("image_url" to "a".repeat(8193))))
        assertTrue(WebExtraction.isComplete(payload("image_url" to null)))
        assertTrue(WebExtraction.isComplete(payload("image_url" to "")))
        assertTrue(WebExtraction.isComplete(payload("image_url" to 5L)))
        // A list or a map, as for the page's address: unreadable, not too long.
        assertEquals(WebPayloadCheck.MISSING, WebExtraction.checkPayload(payload("image_url" to listOf("a.jpg"))))
        assertEquals(WebPayloadCheck.MISSING, WebExtraction.checkPayload(payload("image_url" to mapOf("url" to "a.jpg"))))
        // Reported after the page's address and before a long field.
        assertEquals(
            WebPayloadCheck.ADDRESS_TOO_LONG,
            WebExtraction.checkPayload(payload("source_url" to "a".repeat(8193), "image_url" to "a".repeat(8193))),
        )
        assertEquals(
            WebPayloadCheck.IMAGE_ADDRESS_TOO_LONG,
            WebExtraction.checkPayload(payload("image_url" to "a".repeat(8193), "author" to "a".repeat(2001))),
        )
    }

    @Test
    fun aMissingPartIsReportedBeforeALongField() {
        assertEquals(WebPayloadCheck.COMPLETE, WebExtraction.checkPayload(payload()))
        assertEquals(WebPayloadCheck.MISSING, WebExtraction.checkPayload(payload("name" to "", "author" to "a".repeat(2001))))
        assertEquals(WebPayloadCheck.MISSING, WebExtraction.checkPayload(emptyMap<Any?, Any?>()))
        // The title, a line or a step over its cap is still "missing", as before.
        assertEquals(WebPayloadCheck.MISSING, WebExtraction.checkPayload(payload("name" to "a".repeat(2001))))
        assertEquals(
            WebPayloadCheck.ADDRESS_TOO_LONG,
            WebExtraction.checkPayload(payload("source_url" to "a".repeat(8193), "author" to "a".repeat(2001))),
        )
    }

    @Test
    fun aListOrMapForYieldAuthorOrSourceIsIncomplete() {
        // The extension sends text or null; a nested value's text has no bound short of walking all of it (a
        // deliberate difference from Python, which would str() it).
        for (field in listOf("yield_text", "author", "source_url")) {
            assertFalse(field, WebExtraction.isComplete(payload(field to listOf("Jane"))))
            assertFalse(field, WebExtraction.isComplete(payload(field to mapOf("name" to "Jane"))))
            // Not text, as a line or step that isn't text: the payload is unreadable, not too long.
            assertEquals(field, WebPayloadCheck.MISSING, WebExtraction.checkPayload(payload(field to listOf("Jane"))))
        }
    }

    // A pattern that backtracks on these takes minutes or longer, so a generous timeout still catches it, where a
    // wall-clock bound of a second failed on a loaded machine with no code change.
    @Test(timeout = 30_000)
    fun aHugeHostileLineReturnsQuickly() {
        val spaced = "1 ".repeat(100_000)
        val spacedResult = parse(spaced)
        val parens = parse("(".repeat(200_000))
        val spaces = parse("1" + " ".repeat(200_000) + "x")
        val digits = parse("1".repeat(200_000))
        assertEquals(WebIngredient(spaced.trim(), "", "", null), spacedResult)
        assertEquals("", parens.amount)
        assertEquals("", spaces.amount)
        assertEquals("", digits.amount)
    }

    @Test
    fun aLineAtTheCapStillSplits() {
        val result = parse("2 cups " + "a".repeat(1990))
        assertEquals("2", result.amount)
        assertEquals("cups", result.unit)
    }

    @Test
    fun aHugeLineGivenStraightToBuildRecipeDataIsKeptWhole() {
        // isComplete rejects it first; a direct caller gets the whole line as the name, which is as written. (The
        // 4300-digit limit on amounts is covered in FractionTest: a line this size never reaches the amount parser.)
        val line = "1".repeat(5000) + " cups flour"
        val data = WebExtraction.buildRecipeData(
            linkedMapOf<Any?, Any?>("name" to "S", "ingredients" to listOf(line), "steps" to listOf("Mix.")),
            "u",
        )
        val body = (data["ingredients"] as List<*>)[0] as Map<*, *>
        val amounts = (body[line] as Map<*, *>)["amounts"] as List<*>
        assertEquals(mapOf("amount" to "", "unit" to ""), amounts[0])
    }

    // More parity cases; the expected values are what recipe_extraction.py returned.

    @Test
    fun nonAsciiDigitsAreAnAmountLikePythonsD() {
        assertEquals(WebIngredient("x", "\u0663", "cups", null), parse("\u0663 cups x"))
    }

    @Test
    fun anInteriorNewlineLeavesTheWholeLineAsTheName() {
        // Python's "." stops at a newline and "$" does not match before a non-final one, so the amount pattern fails.
        assertEquals(WebIngredient("2 cups\nflour", "", "", null), parse("2 cups\nflour"))
        assertEquals(WebIngredient("1 cup\nflour, sifted\nfine", "", "", null), parse("1 cup\nflour, sifted\nfine"))
        // ...but a newline inside the amount or between amount and unit is just whitespace.
        assertEquals(WebIngredient("x", "1\n2", "cups", null), parse("1\n2 cups x"))
        assertEquals(WebIngredient("flour", "2", "cups", null), parse("2\ncups flour"))
    }

    @Test
    fun aGluedMixedFractionIsNotNormalized() {
        // Pinned as Python does it: only a leading fraction or the spaced "1 <fraction>" form is rewritten.
        assertEquals(WebIngredient("\u00BD cups flour", "1", "", null), parse("1\u00BD cups flour"))
    }

    @Test
    fun emptyParenthesesAddAnEmptyNote() {
        assertEquals(WebIngredient("x", "1", "", listOf("")), parse("1 ( ) x"))
    }

    @Test
    fun aSingleTokenUnitHasNoName() {
        assertEquals(WebIngredient("", "1", "can", null), parse("1 can"))
    }

    @Test
    fun theWholeRecipeMatchesPython() {
        val data = WebExtraction.buildRecipeData(
            linkedMapOf<Any?, Any?>(
                "name" to " Test Soup ",
                "ingredients" to listOf("2 cups flour, sifted", "Kosher salt, to taste", "1 (15 oz) can black beans"),
                "steps" to listOf("Boil water.", "Add flour."),
                "yield_text" to "4 servings",
                "author" to "Jane Doe",
                "source_url" to "https://example.com/soup",
            ),
            "test-uuid",
        )
        val python = Json.parseToJsonElement(
            """{"recipe_uuid": "test-uuid", "recipe_name": "Test Soup", "category": "None", "subcategory": "None", "ingredients": [{"flour": {"amounts": [{"amount": "2", "unit": "cups"}], "notes": ["sifted"]}}, {"Kosher salt, to taste": {"amounts": [{"amount": "", "unit": ""}]}}, {"black beans": {"amounts": [{"amount": "1", "unit": "can"}], "notes": ["15 oz"]}}], "steps": [{"step": "Boil water."}, {"step": "Add flour."}], "yields": [{"amount": 4, "unit": "servings"}], "author": "Jane Doe", "source_url": "https://example.com/soup"}""",
        )
        assertEquals(python, JsonTree.toJson(data))
        assertEquals(
            listOf("recipe_uuid", "recipe_name", "category", "subcategory", "ingredients", "steps", "yields", "author", "source_url"),
            data.keys.toList(),
        )
    }

    @Test
    fun metricAmountsAreConvertedAsTheyComeIn() {
        val data = WebExtraction.buildRecipeData(
            linkedMapOf<Any?, Any?>("name" to "S", "ingredients" to listOf("1 l milk", "500 g flour"), "steps" to listOf("Mix."), "yield_text" to "Makes 12"),
            "u",
        )
        val python = Json.parseToJsonElement(
            """[{"milk": {"amounts": [{"amount": "1 1/24", "unit": "qt"}]}}, {"flour": {"amounts": [{"amount": "1 13/112", "unit": "lb"}]}}]""",
        )
        assertEquals(python, JsonTree.toJson(data["ingredients"]))
        assertFalse(data.containsKey("yields"))
    }

    @Test
    fun itIsAValidRecipeWithItsMissingAmountFlagged() {
        val data = WebExtraction.buildRecipeData(payload(), "test-uuid")
        Orf.parse(RecipeYaml.dump(data))
        // recipe_sync.find_unparseable_amount_slots flags "Kosher salt, to taste" (slot 1), and only that.
        assertEquals(1, OrfEditing.findUnparseableAmountSlots(data).size)
    }
}
