import unittest
from unittest.mock import patch

import recipe_extraction


class TestParseIngredientLine(unittest.TestCase):
    def test_simple_amount_and_unit(self):
        result = recipe_extraction.parse_ingredient_line("2 cups flour")
        self.assertEqual(
            result, {"name": "flour", "amount": "2", "unit": "cups", "notes": None}
        )

    def test_comma_note_split(self):
        result = recipe_extraction.parse_ingredient_line("2 cups flour, sifted")
        self.assertEqual(
            result, {"name": "flour", "amount": "2", "unit": "cups", "notes": ["sifted"]}
        )

    def test_parenthetical_size_becomes_a_note(self):
        result = recipe_extraction.parse_ingredient_line(
            "1 (15 oz) can black beans, rinsed and drained"
        )
        self.assertEqual(result["name"], "black beans")
        self.assertEqual(result["amount"], "1")
        self.assertEqual(result["unit"], "can")
        self.assertEqual(result["notes"], ["15 oz", "rinsed and drained"])

    def test_no_leading_amount_keeps_whole_line_as_name(self):
        result = recipe_extraction.parse_ingredient_line("Kosher salt, to taste")
        self.assertEqual(
            result,
            {"name": "Kosher salt, to taste", "amount": "", "unit": "", "notes": None},
        )

    def test_unicode_fraction(self):
        result = recipe_extraction.parse_ingredient_line("½ cup sugar")
        self.assertEqual(result["amount"], "1/2")
        self.assertEqual(result["unit"], "cup")
        self.assertEqual(result["name"], "sugar")

    def test_two_word_unit(self):
        result = recipe_extraction.parse_ingredient_line("1 fl oz vodka")
        self.assertEqual(result["unit"], "fl oz")
        self.assertEqual(result["name"], "vodka")

    def test_range_keeps_only_first_number(self):
        result = recipe_extraction.parse_ingredient_line("1-2 tbsp butter")
        self.assertEqual(result["amount"], "1")
        self.assertEqual(result["unit"], "tbsp")
        self.assertEqual(result["name"], "butter")

    def test_only_first_comma_splits_a_multi_comma_line(self):
        result = recipe_extraction.parse_ingredient_line(
            "3 large chicken breasts, boneless, skinless"
        )
        self.assertEqual(result["name"], "chicken breasts")
        self.assertEqual(result["notes"], ["boneless, skinless"])

    def test_mixed_number_amount(self):
        result = recipe_extraction.parse_ingredient_line("1 1/2 cups sugar")
        self.assertEqual(result["amount"], "1 1/2")
        self.assertEqual(result["unit"], "cups")

    def test_spaced_unicode_mixed_number(self):
        result = recipe_extraction.parse_ingredient_line("1 ½ cups flour")
        self.assertEqual(result["amount"], "1 1/2")
        self.assertEqual(result["unit"], "cups")
        self.assertEqual(result["name"], "flour")

    def test_parenthetical_after_unit_becomes_a_note(self):
        result = recipe_extraction.parse_ingredient_line(
            "1 can (15 oz) black beans, rinsed"
        )
        self.assertEqual(result["name"], "black beans")
        self.assertEqual(result["unit"], "can")
        self.assertIn("15 oz", result["notes"])
        self.assertIn("rinsed", result["notes"])

    def test_abbreviated_unit_with_trailing_period(self):
        result = recipe_extraction.parse_ingredient_line("1 tbsp. olive oil")
        self.assertEqual(result["unit"], "tbsp")
        self.assertEqual(result["name"], "olive oil")

    def test_lb_abbreviation_with_trailing_period(self):
        result = recipe_extraction.parse_ingredient_line("1 lb. ground beef")
        self.assertEqual(result["unit"], "lb")
        self.assertEqual(result["name"], "ground beef")

    def test_tsp_abbreviation_with_trailing_period(self):
        result = recipe_extraction.parse_ingredient_line("1 tsp. vanilla extract")
        self.assertEqual(result["unit"], "tsp")
        self.assertEqual(result["name"], "vanilla extract")

    def test_unit_immediately_followed_by_comma_note(self):
        result = recipe_extraction.parse_ingredient_line("2 cups, sifted")
        self.assertEqual(result["unit"], "cups")
        self.assertEqual(result["name"], "")
        self.assertEqual(result["notes"], ["sifted"])


class TestDomainFromUrl(unittest.TestCase):
    def test_extracts_domain(self):
        self.assertEqual(
            recipe_extraction.domain_from_url("https://www.example.com/a/recipe"),
            "www.example.com",
        )

    def test_empty_url_returns_empty_string(self):
        self.assertEqual(recipe_extraction.domain_from_url(""), "")
        self.assertEqual(recipe_extraction.domain_from_url(None), "")


class TestFetchImageBytes(unittest.TestCase):
    @patch("recipe_extraction.urllib.request.urlopen")
    def test_returns_response_body(self, mock_urlopen):
        mock_urlopen.return_value.__enter__.return_value.read.return_value = b"fake-bytes"
        result = recipe_extraction.fetch_image_bytes("https://example.com/a.jpg")
        self.assertEqual(result, b"fake-bytes")

    @patch("recipe_extraction.urllib.request.urlopen")
    def test_rejects_non_http_scheme_without_calling_urlopen(self, mock_urlopen):
        with self.assertRaises(ValueError):
            recipe_extraction.fetch_image_bytes("file:///etc/passwd")
        mock_urlopen.assert_not_called()

    @patch("recipe_extraction.urllib.request.urlopen")
    def test_rejects_oversized_response(self, mock_urlopen):
        oversized = b"x" * (recipe_extraction._MAX_IMAGE_BYTES + 1)
        mock_urlopen.return_value.__enter__.return_value.read.return_value = oversized
        with self.assertRaises(ValueError):
            recipe_extraction.fetch_image_bytes("https://example.com/big.jpg")


class TestBuildRecipeData(unittest.TestCase):
    def test_builds_full_orf_dict(self):
        payload = {
            "name": "Test Soup",
            "ingredients": ["2 cups flour, sifted", "Kosher salt, to taste"],
            "steps": ["Boil water.", "Add flour."],
            "yield_text": "4 servings",
            "author": "Jane Doe",
            "source_url": "https://example.com/soup",
        }
        data = recipe_extraction.build_recipe_data(payload, "test-uuid")

        self.assertEqual(data["recipe_uuid"], "test-uuid")
        self.assertEqual(data["recipe_name"], "Test Soup")
        self.assertEqual(data["category"], "None")
        self.assertEqual(data["subcategory"], "None")
        self.assertEqual(len(data["ingredients"]), 2)
        self.assertIn("flour", data["ingredients"][0])
        self.assertEqual(data["steps"], [{"step": "Boil water."}, {"step": "Add flour."}])
        self.assertEqual(data["yields"], [{"amount": 4, "unit": "servings"}])
        self.assertEqual(data["author"], "Jane Doe")
        self.assertEqual(data["source_url"], "https://example.com/soup")
        self.assertNotIn("image", data)

    def test_omits_yields_when_unparseable(self):
        payload = {
            "name": "Test Soup",
            "ingredients": ["Salt"],
            "steps": ["Cook."],
            "yield_text": "Serves a crowd",
        }
        data = recipe_extraction.build_recipe_data(payload, "test-uuid")
        self.assertNotIn("yields", data)

    def test_omits_author_and_source_url_when_absent(self):
        payload = {"name": "Test Soup", "ingredients": ["Salt"], "steps": ["Cook."]}
        data = recipe_extraction.build_recipe_data(payload, "test-uuid")
        self.assertNotIn("author", data)
        self.assertNotIn("source_url", data)


if __name__ == "__main__":
    unittest.main()
