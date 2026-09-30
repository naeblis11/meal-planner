import unittest
from fractions import Fraction

import unit_conversion


class TestParseAmount(unittest.TestCase):
    def test_whole_number(self):
        self.assertEqual(unit_conversion.parse_amount("2"), Fraction(2))

    def test_simple_fraction(self):
        self.assertEqual(unit_conversion.parse_amount("1/2"), Fraction(1, 2))

    def test_mixed_number(self):
        self.assertEqual(unit_conversion.parse_amount("1 1/2"), Fraction(3, 2))

    def test_decimal(self):
        self.assertEqual(unit_conversion.parse_amount("1.5"), Fraction(3, 2))

    def test_blank_returns_none(self):
        self.assertIsNone(unit_conversion.parse_amount(""))
        self.assertIsNone(unit_conversion.parse_amount("   "))
        self.assertIsNone(unit_conversion.parse_amount(None))

    def test_non_numeric_text_returns_none(self):
        self.assertIsNone(unit_conversion.parse_amount("to taste"))

    def test_two_whole_numbers_is_not_a_mixed_number(self):
        # Real garbage from recipes/sangria-blanca-white-sangria.yaml -- two
        # whole-number tokens, neither a fraction, must not be silently
        # summed into a wrong answer.
        self.assertIsNone(unit_conversion.parse_amount("750    750"))

    def test_non_string_input_is_coerced(self):
        # PyYAML parses a bare `amount: 2` as a Python int, not a string.
        self.assertEqual(unit_conversion.parse_amount(2), Fraction(2))


class TestFormatAmount(unittest.TestCase):
    def test_whole_number(self):
        self.assertEqual(unit_conversion.format_amount(Fraction(3)), "3")

    def test_simple_fraction(self):
        self.assertEqual(unit_conversion.format_amount(Fraction(1, 2)), "1/2")

    def test_mixed_number(self):
        self.assertEqual(unit_conversion.format_amount(Fraction(3, 2)), "1 1/2")

    def test_ugly_fraction_is_not_rounded(self):
        self.assertEqual(unit_conversion.format_amount(Fraction(25, 16)), "1 9/16")


class TestNormalizeUnit(unittest.TestCase):
    def test_volume_aliases(self):
        for spelling in ("ts", "tsp", "teaspoon", "teaspoons", "TSP"):
            self.assertEqual(unit_conversion.normalize_unit(spelling), "tsp")
        for spelling in ("tb", "tbs", "tbsp", "tablespoon", "tablespoons"):
            self.assertEqual(unit_conversion.normalize_unit(spelling), "tbsp")
        for spelling in ("c", "cup", "cups", "Cups"):
            self.assertEqual(unit_conversion.normalize_unit(spelling), "cup")
        for spelling in ("fl oz", "floz", "fluid ounce", "fluid ounces"):
            self.assertEqual(unit_conversion.normalize_unit(spelling), "fl oz")
        for spelling in ("pt", "pint", "pints"):
            self.assertEqual(unit_conversion.normalize_unit(spelling), "pt")
        for spelling in ("qt", "quart", "quarts"):
            self.assertEqual(unit_conversion.normalize_unit(spelling), "qt")
        for spelling in ("ga", "gal", "gallon", "gallons"):
            self.assertEqual(unit_conversion.normalize_unit(spelling), "gal")

    def test_weight_aliases(self):
        for spelling in ("oz", "ounce", "ounces"):
            self.assertEqual(unit_conversion.normalize_unit(spelling), "oz")
        for spelling in ("lb", "lbs", "pound", "pounds"):
            self.assertEqual(unit_conversion.normalize_unit(spelling), "lb")

    def test_metric_aliases(self):
        for spelling in ("ml", "milliliter", "milliliters", "millilitre", "millilitres"):
            self.assertEqual(unit_conversion.normalize_unit(spelling), "ml")
        for spelling in ("l", "liter", "liters", "litre", "litres"):
            self.assertEqual(unit_conversion.normalize_unit(spelling), "l")
        for spelling in ("g", "gram", "grams"):
            self.assertEqual(unit_conversion.normalize_unit(spelling), "g")
        for spelling in ("kg", "kilogram", "kilograms", "kilo", "kilos"):
            self.assertEqual(unit_conversion.normalize_unit(spelling), "kg")

    def test_non_measurable_aliases(self):
        self.assertEqual(unit_conversion.normalize_unit("ea"), "each")
        self.assertEqual(unit_conversion.normalize_unit("each"), "each")
        self.assertEqual(unit_conversion.normalize_unit("cl"), "clove")
        self.assertEqual(unit_conversion.normalize_unit("cloves"), "clove")
        self.assertEqual(unit_conversion.normalize_unit("sm"), "small")
        self.assertEqual(unit_conversion.normalize_unit("lg"), "large")

    def test_unrecognized_unit_passes_through_lowercased_and_stripped(self):
        self.assertEqual(unit_conversion.normalize_unit("  Xyzzy  "), "xyzzy")

    def test_blank_and_none_pass_through_as_empty_string(self):
        self.assertEqual(unit_conversion.normalize_unit(""), "")
        self.assertEqual(unit_conversion.normalize_unit(None), "")


class TestUnitCategory(unittest.TestCase):
    def test_volume_units(self):
        for unit in ("tsp", "tbsp", "fl oz", "cup", "pt", "qt", "gal"):
            self.assertEqual(unit_conversion.unit_category(unit), "volume")

    def test_weight_units(self):
        for unit in ("oz", "lb"):
            self.assertEqual(unit_conversion.unit_category(unit), "weight")

    def test_non_measurable_units_return_none(self):
        for unit in ("each", "clove", "slice", "can", "package", "pinch", "dash",
                     "small", "medium", "large", "", "ml", "l", "g", "kg", "xyzzy"):
            self.assertIsNone(unit_conversion.unit_category(unit))


class TestToBase(unittest.TestCase):
    def test_volume_factors(self):
        self.assertEqual(unit_conversion.to_base("volume", "tbsp", Fraction(2)), Fraction(6))
        self.assertEqual(unit_conversion.to_base("volume", "cup", Fraction(1)), Fraction(48))

    def test_weight_factors(self):
        self.assertEqual(unit_conversion.to_base("weight", "lb", Fraction(1)), Fraction(16))


class TestBestUnit(unittest.TestCase):
    def test_picks_largest_unit_that_stays_at_least_one(self):
        # 2 tbsp (6 tsp) + 1 cup (48 tsp) = 54 tsp -> 1 1/8 cups.
        unit, amount = unit_conversion.best_unit("volume", Fraction(54))
        self.assertEqual(unit, "cup")
        self.assertEqual(amount, Fraction(9, 8))

    def test_falls_back_to_smallest_unit_when_amount_is_under_one(self):
        unit, amount = unit_conversion.best_unit("volume", Fraction(1, 4))
        self.assertEqual(unit, "tsp")
        self.assertEqual(amount, Fraction(1, 4))

    def test_weight_category(self):
        unit, amount = unit_conversion.best_unit("weight", Fraction(32))
        self.assertEqual(unit, "lb")
        self.assertEqual(amount, Fraction(2))


class TestToImperial(unittest.TestCase):
    def test_ml_converts_to_cup(self):
        amount, unit = unit_conversion.to_imperial("240", "ml")
        self.assertEqual(amount, "1")
        self.assertEqual(unit, "cup")

    def test_ml_converts_to_pint(self):
        amount, unit = unit_conversion.to_imperial("480", "ml")
        self.assertEqual(amount, "1")
        self.assertEqual(unit, "pt")

    def test_grams_converts_to_ounces(self):
        amount, unit = unit_conversion.to_imperial("56", "g")
        self.assertEqual(amount, "2")
        self.assertEqual(unit, "oz")

    def test_kilograms_converts_to_pounds_scale(self):
        amount, unit = unit_conversion.to_imperial("1", "kg")
        self.assertEqual(unit, "lb")
        self.assertEqual(amount, "2 13/56")

    def test_already_imperial_unit_is_unchanged(self):
        amount, unit = unit_conversion.to_imperial("2", "cups")
        self.assertEqual((amount, unit), ("2", "cups"))

    def test_non_measurable_unit_is_unchanged(self):
        amount, unit = unit_conversion.to_imperial("4", "each")
        self.assertEqual((amount, unit), ("4", "each"))

    def test_unparseable_amount_is_unchanged(self):
        amount, unit = unit_conversion.to_imperial("750    750", "ml")
        self.assertEqual((amount, unit), ("750    750", "ml"))

    def test_non_string_pass_through_preserves_original_type(self):
        # A bare `amount: 4` in YAML parses as a Python int; an unconverted
        # (non-metric) amount must come back exactly as given, not coerced
        # to a string, so existing recipe data (e.g. "4 each") is untouched.
        amount, unit = unit_conversion.to_imperial(4, "each")
        self.assertEqual((amount, unit), (4, "each"))


class TestCombineLines(unittest.TestCase):
    def test_same_unit_amounts_are_summed(self):
        rows = [("Flour", "2", "cups"), ("Flour", "1", "cup")]
        result = unit_conversion.combine_lines(rows)
        self.assertEqual(result, [("Flour", "3", "cups")])

    def test_same_unit_abbreviation_amounts_are_summed_without_incorrect_plural(self):
        rows = [("Olive Oil", "1", "tbsp"), ("Olive Oil", "2", "tbsp")]
        result = unit_conversion.combine_lines(rows)
        self.assertEqual(result, [("Olive Oil", "3", "tbsp")])

    def test_cross_unit_same_category_amounts_are_converted_and_summed(self):
        rows = [("Butter", "2", "tbsp"), ("Butter", "1", "cup")]
        result = unit_conversion.combine_lines(rows)
        self.assertEqual(result, [("Butter", "1 1/8", "cup")])

    def test_non_measurable_unit_exact_match_is_summed(self):
        rows = [("Onion", "2", "each"), ("Onion", "3", "each")]
        result = unit_conversion.combine_lines(rows)
        self.assertEqual(result, [("Onion", "5", "each")])

    def test_incompatible_units_are_not_merged(self):
        rows = [("Garlic", "2", "each"), ("Garlic", "1", "clove")]
        result = unit_conversion.combine_lines(rows)
        self.assertEqual(set(result), {("Garlic", "2", "each"), ("Garlic", "1", "clove")})

    def test_unparseable_amount_is_not_merged_and_does_not_block_the_rest(self):
        rows = [("Wine", "750    750", "ml"), ("Wine", "1", "cup"), ("Wine", "1", "cup")]
        result = unit_conversion.combine_lines(rows)
        self.assertIn(("Wine", "750    750", "ml"), result)
        self.assertIn(("Wine", "2", "cups"), result)
        self.assertEqual(len(result), 2)

    def test_single_row_passes_through_unchanged(self):
        rows = [("Salt", "1", "tsp")]
        self.assertEqual(unit_conversion.combine_lines(rows), [("Salt", "1", "tsp")])

    def test_case_insensitive_name_match_keeps_first_seen_casing(self):
        rows = [("Flour", "2", "cups"), ("flour", "1", "cup")]
        result = unit_conversion.combine_lines(rows)
        self.assertEqual(result, [("Flour", "3", "cups")])

    def test_different_ingredients_never_merge(self):
        rows = [("Flour", "2", "cups"), ("Sugar", "1", "cup")]
        result = unit_conversion.combine_lines(rows)
        self.assertEqual(set(result), {("Flour", "2", "cups"), ("Sugar", "1", "cup")})

    def test_duplicate_unparseable_amounts_are_deduped(self):
        rows = [
            ("Salt", "to taste", ""),
            ("Salt", "to taste", ""),
            ("Salt", "to taste", ""),
        ]
        result = unit_conversion.combine_lines(rows)
        self.assertEqual(result, [("Salt", "to taste", "")])

    def test_distinct_unparseable_amounts_for_same_ingredient_are_not_collapsed(self):
        rows = [
            ("Pepper", "to taste", ""),
            ("Pepper", "a pinch", ""),
        ]
        result = unit_conversion.combine_lines(rows)
        self.assertEqual(
            set(result), {("Pepper", "to taste", ""), ("Pepper", "a pinch", "")}
        )


class TestIsKnownUnitWord(unittest.TestCase):
    def test_recognizes_a_plural_alias(self):
        self.assertTrue(unit_conversion.is_known_unit_word("cups"))

    def test_recognizes_a_two_word_unit(self):
        self.assertTrue(unit_conversion.is_known_unit_word("fl oz"))

    def test_is_case_insensitive(self):
        self.assertTrue(unit_conversion.is_known_unit_word("CUP"))

    def test_rejects_a_non_unit_word(self):
        self.assertFalse(unit_conversion.is_known_unit_word("banana"))

    def test_rejects_empty_string(self):
        self.assertFalse(unit_conversion.is_known_unit_word(""))


if __name__ == "__main__":
    unittest.main()
