import unittest
from datetime import date

import voice


class TestGiven(unittest.TestCase):
    def test_blank_and_none_are_not_given(self):
        self.assertIsNone(voice.given(None))
        self.assertIsNone(voice.given(""))
        self.assertIsNone(voice.given("   "))

    def test_skip_words_are_not_given_case_insensitively(self):
        for word in ("skip", "None", "NOT SURE", "no", "nothing", "don't know", " Skip "):
            self.assertIsNone(voice.given(word), word)

    def test_anything_else_comes_back_trimmed(self):
        self.assertEqual(voice.given("  milk "), "milk")


class TestItemName(unittest.TestCase):
    def test_trailing_destination_phrase_is_dropped(self):
        # Alexa sometimes stuffs the tail of the sentence into the item
        # slot: "add milk to cart" -> item "milk to cart".
        for heard in ("milk to cart", "milk to the cart", "milk to the shopping cart",
                      "milk to the shopping list", "milk to my list", "milk to the list",
                      "milk to the pantry", "milk in the pantry", "milk on the list"):
            self.assertEqual(voice.item_name(heard), "milk", heard)

    def test_ordinary_names_are_untouched(self):
        self.assertEqual(voice.item_name("ground beef"), "ground beef")
        self.assertEqual(voice.item_name("tomato paste"), "tomato paste")

    def test_blank_and_skip_are_not_given(self):
        self.assertIsNone(voice.item_name(""))
        self.assertIsNone(voice.item_name("skip"))
        self.assertIsNone(voice.item_name("to the cart"))


class TestNormalizeQuantity(unittest.TestCase):
    def test_digits_and_decimals_become_fraction_text(self):
        self.assertEqual(voice.normalize_quantity("2"), "2")
        self.assertEqual(voice.normalize_quantity("1.5"), "1 1/2")
        self.assertEqual(voice.normalize_quantity("0.25"), "1/4")

    def test_number_words(self):
        self.assertEqual(voice.normalize_quantity("two"), "2")
        self.assertEqual(voice.normalize_quantity("Twelve"), "12")
        self.assertEqual(voice.normalize_quantity("thirty"), "30")

    def test_a_and_an_mean_one(self):
        self.assertEqual(voice.normalize_quantity("a"), "1")
        self.assertEqual(voice.normalize_quantity("an"), "1")

    def test_half_and_dozen(self):
        self.assertEqual(voice.normalize_quantity("half"), "1/2")
        self.assertEqual(voice.normalize_quantity("a half"), "1/2")
        self.assertEqual(voice.normalize_quantity("a dozen"), "12")
        self.assertEqual(voice.normalize_quantity("dozen"), "12")

    def test_skip_blank_and_nonsense_are_not_given(self):
        self.assertIsNone(voice.normalize_quantity("skip"))
        self.assertIsNone(voice.normalize_quantity(""))
        self.assertIsNone(voice.normalize_quantity("?"))
        self.assertIsNone(voice.normalize_quantity("lots"))


class TestNormalizeUnit(unittest.TestCase):
    def test_known_words_normalise_to_the_apps_abbreviations(self):
        self.assertEqual(voice.normalize_unit("pounds", "2"), "lb")
        self.assertEqual(voice.normalize_unit("Gallon", "1"), "gal")
        self.assertEqual(voice.normalize_unit("cups", "3"), "cup")

    def test_unknown_words_are_kept_lowercased(self):
        self.assertEqual(voice.normalize_unit("Bags", "2"), "bags")

    def test_unit_without_a_quantity_is_dropped(self):
        self.assertIsNone(voice.normalize_unit("pounds", None))

    def test_skip_and_blank_are_not_given(self):
        self.assertIsNone(voice.normalize_unit("skip", "2"))
        self.assertIsNone(voice.normalize_unit("", "2"))


class TestNormalizeAisle(unittest.TestCase):
    def test_matches_a_known_aisle_case_insensitively(self):
        self.assertEqual(voice.normalize_aisle("dairy & eggs"), "Dairy & Eggs")
        self.assertEqual(voice.normalize_aisle("PRODUCE"), "Produce")

    def test_and_and_ampersand_are_interchangeable(self):
        # The Alexa slot value is spoken-safe ("and"); the app's own
        # spelling keeps the ampersand.
        self.assertEqual(voice.normalize_aisle("dairy and eggs"), "Dairy & Eggs")
        self.assertEqual(voice.normalize_aisle("DAIRY & EGGS"), "Dairy & Eggs")

    def test_unknown_skip_and_blank_are_not_given(self):
        self.assertIsNone(voice.normalize_aisle("garage"))
        self.assertIsNone(voice.normalize_aisle("skip"))
        self.assertIsNone(voice.normalize_aisle(None))


class TestNormalizeMeal(unittest.TestCase):
    def test_matches_a_slot_case_insensitively(self):
        self.assertEqual(voice.normalize_meal("lunch"), "Lunch")
        self.assertEqual(voice.normalize_meal("BREAKFAST"), "Breakfast")

    def test_blank_skip_and_unknown_default_to_dinner(self):
        self.assertEqual(voice.normalize_meal(""), "Dinner")
        self.assertEqual(voice.normalize_meal("skip"), "Dinner")
        self.assertEqual(voice.normalize_meal("elevenses"), "Dinner")


class TestParseDay(unittest.TestCase):
    TODAY = date(2026, 9, 13)

    def test_iso_day(self):
        self.assertEqual(voice.parse_day("2026-09-17", self.TODAY), date(2026, 9, 17))

    def test_blank_means_today(self):
        self.assertEqual(voice.parse_day("", self.TODAY), self.TODAY)
        self.assertEqual(voice.parse_day(None, self.TODAY), self.TODAY)

    def test_anything_that_is_not_a_single_day_is_rejected(self):
        for value in ("2026-W38", "2026-W38-WE", "2026-09", "2026", "2026-SU", "thursday", "2026-02-30"):
            self.assertIsNone(voice.parse_day(value, self.TODAY), value)


class TestMatchRecipe(unittest.TestCase):
    NAMES = ["Ground Beef Tacos", "Fish Tacos", "Banana Bread", "Chicken Stroganoff", "Beef Stroganoff"]

    def test_exact_match_ignoring_case_and_punctuation(self):
        result = voice.match_recipe("banana bread", self.NAMES)
        self.assertEqual((result.status, result.name), ("found", "Banana Bread"))
        result = voice.match_recipe("Beef Stroganoff!", self.NAMES)
        self.assertEqual(result.name, "Beef Stroganoff")

    def test_spoken_phrase_contained_in_exactly_one_name(self):
        result = voice.match_recipe("banana", self.NAMES)
        self.assertEqual((result.status, result.name), ("found", "Banana Bread"))

    def test_name_contained_in_the_spoken_phrase(self):
        result = voice.match_recipe("the banana bread recipe", self.NAMES)
        self.assertEqual((result.status, result.name), ("found", "Banana Bread"))

    def test_containment_in_several_prefers_the_shortest_name(self):
        result = voice.match_recipe("tacos", self.NAMES)
        self.assertEqual((result.status, result.name), ("found", "Fish Tacos"))

    def test_containment_tie_on_length_is_ambiguous(self):
        names = ["Beef Tacos", "Fish Tacos"]
        result = voice.match_recipe("tacos", names)
        self.assertEqual(result.status, "ambiguous")
        self.assertEqual(sorted(result.candidates), ["Beef Tacos", "Fish Tacos"])

    def test_fuzzy_match_above_threshold(self):
        result = voice.match_recipe("chicken stroganof", self.NAMES)
        self.assertEqual((result.status, result.name), ("found", "Chicken Stroganoff"))

    def test_fuzzy_below_threshold_is_none(self):
        result = voice.match_recipe("lasagna", self.NAMES)
        self.assertEqual(result.status, "none")
        self.assertIsNone(result.name)

    def test_containment_in_several_of_different_lengths_picks_the_shortest(self):
        result = voice.match_recipe("stroganoff", self.NAMES)
        self.assertEqual((result.status, result.name), ("found", "Beef Stroganoff"))

    def test_fuzzy_runner_up_too_close_is_ambiguous(self):
        # "pork chaps" is one letter from each: both score 0.9.
        result = voice.match_recipe("pork chaps", ["Pork Chops", "Pork Chips", "Banana Bread"])
        self.assertEqual(result.status, "ambiguous")
        self.assertEqual(sorted(result.candidates), ["Pork Chips", "Pork Chops"])

    def test_ambiguous_lists_at_most_three_candidates(self):
        names = ["A Tacos", "B Tacos", "C Tacos", "D Tacos"]
        result = voice.match_recipe("tacos", names)
        self.assertEqual(result.status, "ambiguous")
        self.assertEqual(len(result.candidates), 3)

    def test_empty_library_is_none(self):
        self.assertEqual(voice.match_recipe("tacos", []).status, "none")


class TestSpeech(unittest.TestCase):
    def test_spoken_amount_pluralises_known_units(self):
        self.assertEqual(voice.spoken_amount("2", "gal"), "2 gallons")
        self.assertEqual(voice.spoken_amount("1", "lb"), "1 pound")
        self.assertEqual(voice.spoken_amount("1 1/2", "cup"), "1 1/2 cups")
        self.assertEqual(voice.spoken_amount("2", "bags"), "2 bags")
        self.assertEqual(voice.spoken_amount("3", None), "3")
        self.assertEqual(voice.spoken_amount(None, None), "")

    def test_spoken_day(self):
        self.assertEqual(voice.spoken_day(date(2026, 9, 17)), "Thursday, September 17")

    def test_shopping_added_with_amount_and_aisle(self):
        self.assertEqual(
            voice.shopping_speech("added", "milk", "2", "gal", "Dairy & Eggs"),
            "Added 2 gallons of milk to your shopping list, under Dairy & Eggs.",
        )

    def test_shopping_added_without_amount_or_aisle(self):
        self.assertEqual(
            voice.shopping_speech("added", "milk", None, None, None),
            "Added milk to your shopping list.",
        )

    def test_shopping_merged_and_duplicate(self):
        self.assertEqual(
            voice.shopping_speech("merged", "milk", "3", "gal", "Dairy & Eggs"),
            "Milk was already on your shopping list; it's now 3 gallons.",
        )
        self.assertEqual(
            voice.shopping_speech("duplicate", "milk", None, None, None),
            "Milk is already on your shopping list.",
        )

    def test_pantry(self):
        self.assertEqual(voice.pantry_speech("added", "olive oil"), "Added olive oil to your pantry.")
        self.assertEqual(voice.pantry_speech("restored", "olive oil"), "Put olive oil back in your pantry.")
        self.assertEqual(voice.pantry_speech("duplicate", "olive oil"), "Olive oil is already in your pantry.")

    def test_meal(self):
        d = date(2026, 9, 17)
        self.assertEqual(
            voice.meal_speech("Ground Beef Tacos", "Dinner", d, None),
            "Added Ground Beef Tacos for dinner on Thursday, September 17.",
        )
        self.assertEqual(
            voice.meal_speech("Ground Beef Tacos", "Dinner", d, "Chili"),
            "Added Ground Beef Tacos for dinner on Thursday, September 17, replacing Chili.",
        )
        self.assertEqual(
            voice.meal_speech("Ground Beef Tacos", "Dinner", d, "Ground Beef Tacos"),
            "Ground Beef Tacos is already planned for dinner on Thursday, September 17.",
        )

    def test_no_match_and_ambiguous(self):
        self.assertEqual(voice.no_match_speech("lasagna"), "I couldn't find a recipe like 'lasagna'.")
        self.assertEqual(
            voice.ambiguous_speech(["Beef Tacos", "Fish Tacos"]),
            "I found Beef Tacos and Fish Tacos. Which one?",
        )
        self.assertEqual(
            voice.ambiguous_speech(["A", "B", "C"]),
            "I found A, B, and C. Which one?",
        )


if __name__ == "__main__":
    unittest.main()
