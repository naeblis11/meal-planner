package com.naeblis11.mealplanner.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Port of tests/test_voice.py, then extra cases whose expected values Python's voice.py printed. */
class VoiceTest {
    private val today: LocalDate = LocalDate.of(2026, 9, 13)
    private val names = listOf("Ground Beef Tacos", "Fish Tacos", "Banana Bread", "Chicken Stroganoff", "Beef Stroganoff")

    // given

    @Test
    fun blankAndNoneAreNotGiven() {
        assertNull(Voice.given(null))
        assertNull(Voice.given(""))
        assertNull(Voice.given("   "))
    }

    @Test
    fun skipWordsAreNotGivenCaseInsensitively() {
        for (word in listOf("skip", "None", "NOT SURE", "no", "nothing", "don't know", " Skip ")) assertNull(word, Voice.given(word))
    }

    @Test
    fun anythingElseComesBackTrimmed() {
        assertEquals("milk", Voice.given("  milk "))
    }

    // itemName

    @Test
    fun aTrailingDestinationPhraseIsDropped() {
        // Alexa sometimes stuffs the tail of the sentence into the item slot: "add milk to cart" -> "milk to cart".
        for (heard in listOf(
            "milk to cart", "milk to the cart", "milk to the shopping cart", "milk to the shopping list", "milk to my list",
            "milk to the list", "milk to the pantry", "milk in the pantry", "milk on the list",
        )) {
            assertEquals(heard, "milk", Voice.itemName(heard))
        }
    }

    @Test
    fun ordinaryNamesAreUntouched() {
        assertEquals("ground beef", Voice.itemName("ground beef"))
        assertEquals("tomato paste", Voice.itemName("tomato paste"))
    }

    @Test
    fun blankAndSkipItemsAreNotGiven() {
        assertNull(Voice.itemName(""))
        assertNull(Voice.itemName("skip"))
        assertNull(Voice.itemName("to the cart"))
    }

    // normalizeQuantity

    @Test
    fun digitsAndDecimalsBecomeFractionText() {
        assertEquals("2", Voice.normalizeQuantity("2"))
        assertEquals("1 1/2", Voice.normalizeQuantity("1.5"))
        assertEquals("1/4", Voice.normalizeQuantity("0.25"))
    }

    @Test
    fun numberWords() {
        assertEquals("2", Voice.normalizeQuantity("two"))
        assertEquals("12", Voice.normalizeQuantity("Twelve"))
        assertEquals("30", Voice.normalizeQuantity("thirty"))
    }

    @Test
    fun aAndAnMeanOne() {
        assertEquals("1", Voice.normalizeQuantity("a"))
        assertEquals("1", Voice.normalizeQuantity("an"))
    }

    @Test
    fun halfAndDozen() {
        assertEquals("1/2", Voice.normalizeQuantity("half"))
        assertEquals("1/2", Voice.normalizeQuantity("a half"))
        assertEquals("12", Voice.normalizeQuantity("a dozen"))
        assertEquals("12", Voice.normalizeQuantity("dozen"))
    }

    @Test
    fun skipBlankAndNonsenseQuantitiesAreNotGiven() {
        assertNull(Voice.normalizeQuantity("skip"))
        assertNull(Voice.normalizeQuantity(""))
        assertNull(Voice.normalizeQuantity("?"))
        assertNull(Voice.normalizeQuantity("lots"))
    }

    // normalizeUnit

    @Test
    fun knownWordsNormaliseToTheAppsAbbreviations() {
        assertEquals("lb", Voice.normalizeUnit("pounds", "2"))
        assertEquals("gal", Voice.normalizeUnit("Gallon", "1"))
        assertEquals("cup", Voice.normalizeUnit("cups", "3"))
    }

    @Test
    fun unknownUnitWordsAreKeptLowercased() {
        assertEquals("bags", Voice.normalizeUnit("Bags", "2"))
    }

    @Test
    fun aUnitWithoutAQuantityIsDropped() {
        assertNull(Voice.normalizeUnit("pounds", null))
    }

    @Test
    fun skipAndBlankUnitsAreNotGiven() {
        assertNull(Voice.normalizeUnit("skip", "2"))
        assertNull(Voice.normalizeUnit("", "2"))
    }

    // normalizeAisle

    @Test
    fun aKnownAisleMatchesCaseInsensitively() {
        assertEquals("Dairy & Eggs", Voice.normalizeAisle("dairy & eggs"))
        assertEquals("Produce", Voice.normalizeAisle("PRODUCE"))
    }

    @Test
    fun andAndAmpersandAreInterchangeable() {
        // The Alexa slot value is spoken-safe ("and"); the app's own spelling keeps the ampersand.
        assertEquals("Dairy & Eggs", Voice.normalizeAisle("dairy and eggs"))
        assertEquals("Dairy & Eggs", Voice.normalizeAisle("DAIRY & EGGS"))
    }

    @Test
    fun unknownSkipAndBlankAislesAreNotGiven() {
        assertNull(Voice.normalizeAisle("garage"))
        assertNull(Voice.normalizeAisle("skip"))
        assertNull(Voice.normalizeAisle(null))
    }

    // normalizeMeal

    @Test
    fun aSlotMatchesCaseInsensitively() {
        assertEquals("Lunch", Voice.normalizeMeal("lunch"))
        assertEquals("Breakfast", Voice.normalizeMeal("BREAKFAST"))
    }

    @Test
    fun blankSkipAndUnknownMealsMeanDinner() {
        assertEquals("Dinner", Voice.normalizeMeal(""))
        assertEquals("Dinner", Voice.normalizeMeal("skip"))
        assertEquals("Dinner", Voice.normalizeMeal("elevenses"))
    }

    // parseDay

    @Test
    fun anIsoDay() {
        assertEquals(LocalDate.of(2026, 9, 17), Voice.parseDay("2026-09-17", today))
    }

    @Test
    fun blankMeansToday() {
        assertEquals(today, Voice.parseDay("", today))
        assertEquals(today, Voice.parseDay(null, today))
    }

    @Test
    fun anythingThatIsNotASingleDayIsRejected() {
        for (value in listOf("2026-W38", "2026-W38-WE", "2026-09", "2026", "2026-SU", "thursday", "2026-02-30")) {
            assertNull(value, Voice.parseDay(value, today))
        }
    }

    // matchRecipe

    @Test
    fun anExactMatchIgnoresCaseAndPunctuation() {
        assertEquals(Voice.Match(Voice.MatchStatus.FOUND, "Banana Bread"), Voice.matchRecipe("banana bread", names))
        assertEquals("Beef Stroganoff", Voice.matchRecipe("Beef Stroganoff!", names).name)
    }

    @Test
    fun aSpokenPhraseContainedInExactlyOneName() {
        assertEquals(Voice.Match(Voice.MatchStatus.FOUND, "Banana Bread"), Voice.matchRecipe("banana", names))
    }

    @Test
    fun aNameContainedInTheSpokenPhrase() {
        assertEquals(Voice.Match(Voice.MatchStatus.FOUND, "Banana Bread"), Voice.matchRecipe("the banana bread recipe", names))
    }

    @Test
    fun containmentInSeveralPrefersTheShortestName() {
        assertEquals(Voice.Match(Voice.MatchStatus.FOUND, "Fish Tacos"), Voice.matchRecipe("tacos", names))
    }

    @Test
    fun aContainmentTieOnLengthIsAmbiguous() {
        val result = Voice.matchRecipe("tacos", listOf("Beef Tacos", "Fish Tacos"))
        assertEquals(Voice.MatchStatus.AMBIGUOUS, result.status)
        assertEquals(listOf("Beef Tacos", "Fish Tacos"), result.candidates)
    }

    @Test
    fun aFuzzyMatchAboveTheThreshold() {
        assertEquals(Voice.Match(Voice.MatchStatus.FOUND, "Chicken Stroganoff"), Voice.matchRecipe("chicken stroganof", names))
    }

    @Test
    fun aFuzzyMatchBelowTheThresholdIsNone() {
        val result = Voice.matchRecipe("lasagna", names)
        assertEquals(Voice.MatchStatus.NONE, result.status)
        assertNull(result.name)
    }

    @Test
    fun containmentInSeveralOfDifferentLengthsPicksTheShortest() {
        assertEquals(Voice.Match(Voice.MatchStatus.FOUND, "Beef Stroganoff"), Voice.matchRecipe("stroganoff", names))
    }

    @Test
    fun aFuzzyRunnerUpTooCloseIsAmbiguous() {
        // "pork chaps" is one letter from each: both score 0.9.
        val result = Voice.matchRecipe("pork chaps", listOf("Pork Chops", "Pork Chips", "Banana Bread"))
        assertEquals(Voice.MatchStatus.AMBIGUOUS, result.status)
        assertEquals(listOf("Pork Chops", "Pork Chips"), result.candidates)
    }

    @Test
    fun anAmbiguousMatchListsAtMostThreeCandidates() {
        val result = Voice.matchRecipe("tacos", listOf("A Tacos", "B Tacos", "C Tacos", "D Tacos"))
        assertEquals(Voice.MatchStatus.AMBIGUOUS, result.status)
        assertEquals(listOf("A Tacos", "B Tacos", "C Tacos"), result.candidates)
    }

    @Test
    fun anEmptyLibraryIsNone() {
        assertEquals(Voice.MatchStatus.NONE, Voice.matchRecipe("tacos", emptyList()).status)
    }

    // speech

    @Test
    fun spokenAmountPluralisesKnownUnits() {
        assertEquals("2 gallons", Voice.spokenAmount("2", "gal"))
        assertEquals("1 pound", Voice.spokenAmount("1", "lb"))
        assertEquals("1 1/2 cups", Voice.spokenAmount("1 1/2", "cup"))
        assertEquals("2 bags", Voice.spokenAmount("2", "bags"))
        assertEquals("3", Voice.spokenAmount("3", null))
        assertEquals("", Voice.spokenAmount(null, null))
    }

    @Test
    fun spokenDay() {
        assertEquals("Thursday, September 17", Voice.spokenDay(LocalDate.of(2026, 9, 17)))
    }

    @Test
    fun shoppingAddedWithAmountAndAisle() {
        assertEquals(
            "Added 2 gallons of milk to your shopping list, under Dairy & Eggs.",
            Voice.shoppingSpeech(Voice.ShoppingStatus.ADDED, "milk", "2", "gal", "Dairy & Eggs"),
        )
    }

    @Test
    fun shoppingAddedWithoutAmountOrAisle() {
        assertEquals("Added milk to your shopping list.", Voice.shoppingSpeech(Voice.ShoppingStatus.ADDED, "milk", null, null, null))
    }

    @Test
    fun shoppingMergedAndDuplicate() {
        assertEquals(
            "Milk was already on your shopping list; it's now 3 gallons.",
            Voice.shoppingSpeech(Voice.ShoppingStatus.MERGED, "milk", "3", "gal", "Dairy & Eggs"),
        )
        assertEquals("Milk is already on your shopping list.", Voice.shoppingSpeech(Voice.ShoppingStatus.DUPLICATE, "milk", null, null, null))
    }

    @Test
    fun pantrySentences() {
        assertEquals("Added olive oil to your pantry.", Voice.pantrySpeech(Voice.PantryStatus.ADDED, "olive oil"))
        assertEquals("Put olive oil back in your pantry.", Voice.pantrySpeech(Voice.PantryStatus.RESTORED, "olive oil"))
        assertEquals("Olive oil is already in your pantry.", Voice.pantrySpeech(Voice.PantryStatus.DUPLICATE, "olive oil"))
    }

    @Test
    fun mealSentences() {
        val day = LocalDate.of(2026, 9, 17)
        assertEquals(
            "Added Ground Beef Tacos for dinner on Thursday, September 17.",
            Voice.mealSpeech("Ground Beef Tacos", "Dinner", day, null),
        )
        assertEquals(
            "Added Ground Beef Tacos for dinner on Thursday, September 17, replacing Chili.",
            Voice.mealSpeech("Ground Beef Tacos", "Dinner", day, "Chili"),
        )
        assertEquals(
            "Ground Beef Tacos is already planned for dinner on Thursday, September 17.",
            Voice.mealSpeech("Ground Beef Tacos", "Dinner", day, "Ground Beef Tacos"),
        )
    }

    @Test
    fun noMatchAndAmbiguousSentences() {
        assertEquals("I couldn't find a recipe like 'lasagna'.", Voice.noMatchSpeech("lasagna"))
        assertEquals("I found Beef Tacos and Fish Tacos. Which one?", Voice.ambiguousSpeech(listOf("Beef Tacos", "Fish Tacos")))
        assertEquals("I found A, B, and C. Which one?", Voice.ambiguousSpeech(listOf("A", "B", "C")))
    }

    // Extra cases: the expected values are what voice.py returned for the same input.

    @Test
    fun quantityEdgesMatchPython() {
        assertNull(Voice.normalizeQuantity("1/0"))
        assertEquals("3/4", Voice.normalizeQuantity("3/4"))
        assertEquals("-2", Voice.normalizeQuantity("-2"))
        assertEquals("100", Voice.normalizeQuantity("1e2"))
    }

    @Test
    fun theDestinationTailIgnoresCaseAndOnlyComesOffTheEnd() {
        assertEquals("milk", Voice.itemName("milk TO THE CART"))
        assertEquals("tomatoes in a can", Voice.itemName("tomatoes in a can"))
    }

    @Test
    fun datesAndDaysAgreeWithPython() {
        assertNull(Voice.parseDay("20260917", today))
        assertEquals(LocalDate.of(2026, 9, 17), Voice.parseDay(" 2026-09-17 ", today))
        assertEquals("Thursday, January 1", Voice.spokenDay(LocalDate.of(2026, 1, 1)))
    }

    @Test
    fun onlyAPlainIsoDayInPythonsYearsIsADay() {
        // date.fromisoformat, then the round trip: four ASCII digits of year 1 to 9999, no sign, no other digits.
        assertEquals(LocalDate.of(1, 1, 1), Voice.parseDay("0001-01-01", today))
        assertEquals(LocalDate.of(9999, 12, 31), Voice.parseDay("9999-12-31", today))
        assertEquals(LocalDate.of(2024, 2, 29), Voice.parseDay("2024-02-29", today))
        assertEquals(LocalDate.of(2026, 9, 17), Voice.parseDay("2026-09-17\n", today))
        for (value in listOf(
            "0000-01-01", "-0001-01-01", "+2026-09-17", "+10000-01-01", "10000-01-01", "2026-0917", "2026-9-17",
            "2026-09-17T00", "2026-W38-4", "\u0662\u0660\u0662\u0666-09-17",
        )) {
            assertNull(value, Voice.parseDay(value, today))
        }
    }

    @Test
    fun ambiguousCandidatesComeInPythonsOrderWhateverTheLibrarysOrder() {
        assertEquals(listOf("Beef Tacos", "Fish Tacos"), Voice.matchRecipe("tacos", listOf("Fish Tacos", "Beef Tacos")).candidates)
        assertEquals(
            listOf("A Tacos", "B Tacos", "C Tacos"),
            Voice.matchRecipe("tacos", listOf("D Tacos", "C Tacos", "B Tacos", "A Tacos")).candidates,
        )
    }

    @Test
    fun anEmptyPreviousRecipeIsNoRecipe() {
        // meal_speech's `if previous`: "" replaces nothing.
        assertEquals("Added X for dinner on Sunday, September 13.", Voice.mealSpeech("X", "Dinner", today, ""))
    }

    @Test
    fun moreMatchesAgreeWithPython() {
        assertEquals(Voice.Match(Voice.MatchStatus.FOUND, "Chicken Tacos"), Voice.matchRecipe("chiken tacos", listOf("Ground Beef Tacos", "Fish Tacos", "Chicken Tacos")))
        assertEquals(Voice.Match(Voice.MatchStatus.FOUND, "Beef Stroganoff"), Voice.matchRecipe("beef strogan", listOf("Chicken Stroganoff", "Beef Stroganoff")))
        assertEquals(Voice.Match(Voice.MatchStatus.FOUND, "Banana Bread"), Voice.matchRecipe("Banana-Bread", listOf("Banana Bread")))
        assertEquals(Voice.Match(Voice.MatchStatus.FOUND, "Pot Roast Sandwich"), Voice.matchRecipe("pot roast", listOf("Pot Roast Sandwich", "Pork Roast")))
        assertEquals(Voice.Match(Voice.MatchStatus.FOUND, "Chili"), Voice.matchRecipe("chilli", listOf("Chili", "Chicken Chili")))
    }

    @Test
    fun aFieldIsTextOrANumberAndNothingElse() {
        // app.py's _voice_field: Home Assistant's templates can turn "2" into a number before it is sent.
        val body = mapOf<Any?, Any?>("item" to "milk", "quantity" to 12L, "half" to 1.5, "flag" to true, "nested" to mapOf("a" to 1L), "none" to null)
        assertEquals("milk", Voice.field(body, "item"))
        assertEquals("12", Voice.field(body, "quantity"))
        assertEquals("1.5", Voice.field(body, "half"))
        assertEquals("", Voice.field(body, "flag"))
        assertEquals("", Voice.field(body, "nested"))
        assertEquals("", Voice.field(body, "none"))
        assertEquals("", Voice.field(body, "missing"))
    }
}
