import unittest
from pathlib import Path

import meal_master
import yaml

FIXTURES = Path(__file__).parent / "fixtures" / "mealmaster"


def _read(name: str) -> str:
    # Fixtures are CP1252-encoded like real MealMaster exports and contain
    # bytes (e.g. the degree sign) that are not valid UTF-8, so every fixture
    # is read as CP1252 -- matching how the upload route decodes .mmf bytes.
    return (FIXTURES / name).read_bytes().decode("cp1252")


class TestSplitChunks(unittest.TestCase):
    def test_splits_two_recipes_on_mmmmm_header(self):
        text = _read("baked-ziti-only.mmf") + "\n" + _read("rice-croquettes-only.mmf")
        chunks = meal_master._split_chunks(text)
        self.assertEqual(len(chunks), 2)
        self.assertIn("Baked Ziti", chunks[0])
        self.assertIn("Rice Croquettes", chunks[1])


class TestFieldHelpers(unittest.TestCase):
    def test_finds_title_categories_and_header_yield(self):
        lines = _read("baked-ziti-only.mmf").splitlines()
        title_idx = meal_master._find_field_line_index(lines, "Title:")
        self.assertEqual(lines[title_idx].strip(), "Title: Baked Ziti")
        self.assertEqual(meal_master._find_field(lines, "Categories:"), "Pasta")
        self.assertEqual(meal_master._find_field(lines, "Yield:"), "0 Servings")

    def test_categories_blank_line_returns_empty_string(self):
        lines = _read("empty-cuisine.mmf").splitlines()
        self.assertEqual(meal_master._find_field(lines, "Categories:"), "")

    def test_missing_title_returns_negative_index(self):
        lines = _read("missing-title.mmf").splitlines()
        self.assertEqual(meal_master._find_field_line_index(lines, "Title:"), -1)


class TestIngredientExtraction(unittest.TestCase):
    def test_extracts_ingredient_lines_after_header(self):
        lines = _read("baked-ziti-only.mmf").splitlines()
        title_idx = meal_master._find_field_line_index(lines, "Title:")
        ingredient_lines, body_start = meal_master._extract_ingredient_lines(lines, title_idx)
        self.assertEqual(len(ingredient_lines), 10)
        self.assertIn("Onion", ingredient_lines[0])
        self.assertTrue(lines[body_start].strip().startswith("Brown the sausage"))

    def test_joins_mid_word_continuation_with_no_space(self):
        lines = _read("rice-croquettes-only.mmf").splitlines()
        title_idx = meal_master._find_field_line_index(lines, "Title:")
        ingredient_lines, _ = meal_master._extract_ingredient_lines(lines, title_idx)
        joined = meal_master._join_continuations(ingredient_lines)
        self.assertIn("3 oz Fresh mozzarella cheese -- diced", joined)
        self.assertIn("1/2  c Peas -- boiled 1 min & drained", joined)


class TestParseIngredientLine(unittest.TestCase):
    def test_amount_and_note_split_on_double_dash(self):
        result = meal_master._parse_ingredient_line("2 Onion -- minced")
        self.assertEqual(
            result,
            {"Onion": {"amounts": [{"amount": "2", "unit": ""}], "notes": ["minced"]}},
        )

    def test_unit_recognized_from_known_vocabulary(self):
        result = meal_master._parse_ingredient_line("1/2  c Tomato sauce")
        self.assertEqual(
            result,
            {"Tomato sauce": {"amounts": [{"amount": "1/2", "unit": "c"}]}},
        )

    def test_mixed_number_amount_with_no_unit(self):
        result = meal_master._parse_ingredient_line("1 1/2 Arborio Rice")
        self.assertEqual(
            result,
            {"Arborio Rice": {"amounts": [{"amount": "1 1/2", "unit": ""}]}},
        )

    def test_no_amount_at_all(self):
        result = meal_master._parse_ingredient_line("Lemon -- small, sliced thin")
        self.assertEqual(
            result,
            {"Lemon": {"amounts": [{"amount": "", "unit": ""}], "notes": ["small, sliced thin"]}},
        )


class TestSectionHeaders(unittest.TestCase):
    def test_header_line_is_not_emitted_as_an_ingredient(self):
        lines = _read("sections-only.mmf").splitlines()
        title_idx = meal_master._find_field_line_index(lines, "Title:")
        ingredient_lines, _ = meal_master._extract_ingredient_lines(lines, title_idx)
        ingredients = meal_master._parse_ingredients(ingredient_lines)
        names = [next(iter(ing)) for ing in ingredients]
        self.assertNotIn("=== SAUCE ONE ===", names)
        self.assertEqual(len(ingredients), 3)

    def test_ingredients_after_a_header_get_tagged_with_its_section(self):
        lines = _read("sections-only.mmf").splitlines()
        title_idx = meal_master._find_field_line_index(lines, "Title:")
        ingredient_lines, _ = meal_master._extract_ingredient_lines(lines, title_idx)
        ingredients = meal_master._parse_ingredients(ingredient_lines)
        by_name = {next(iter(ing)): ing[next(iter(ing))] for ing in ingredients}
        self.assertEqual(by_name["Butter"]["section"], "Sauce One")
        self.assertEqual(by_name["Salt"]["section"], "Sauce One")
        self.assertEqual(by_name["Onion"]["section"], "Sauce Two")

    def test_ingredients_before_the_first_header_get_no_section_key(self):
        result = meal_master._parse_ingredients(["2 Onion -- minced"])
        self.assertNotIn("section", result[0]["Onion"])

    def test_full_recipe_with_sections_parses_with_no_bogus_ingredient(self):
        chunk = _read("sections-only.mmf")
        data = meal_master._parse_chunk(chunk)
        names = [next(iter(ing)) for ing in data["ingredients"]]
        self.assertEqual(names, ["Butter", "Salt", "Onion"])


class TestInstructionExtraction(unittest.TestCase):
    def test_splits_into_paragraphs_and_stops_before_source(self):
        lines = _read("rice-croquettes-only.mmf").splitlines()
        title_idx = meal_master._find_field_line_index(lines, "Title:")
        _, body_start = meal_master._extract_ingredient_lines(lines, title_idx)
        steps, tail_start = meal_master._extract_instructions(lines, body_start)
        self.assertEqual(len(steps), 4)
        self.assertTrue(steps[0]["step"].startswith("Pour the stock"))
        self.assertTrue(lines[tail_start].strip() == "Source:")

    def test_two_consecutive_non_blank_lines_join_into_one_paragraph(self):
        lines = _read("baked-ziti-only.mmf").splitlines()
        title_idx = meal_master._find_field_line_index(lines, "Title:")
        _, body_start = meal_master._extract_ingredient_lines(lines, title_idx)
        steps, _ = meal_master._extract_instructions(lines, body_start)
        self.assertEqual(len(steps), 2)
        self.assertEqual(steps[1]["step"], "Enjoy! Test Kitchen 2020")

    def test_stops_at_footer_when_no_trailing_fields_present(self):
        lines = ["Brown the onion.", "", "MMMMM"]
        steps, tail_start = meal_master._extract_instructions(lines, 0)
        self.assertEqual(steps, [{"step": "Brown the onion."}])
        self.assertEqual(lines[tail_start].strip(), "MMMMM")


class TestTailFields(unittest.TestCase):
    def test_single_line_source_and_stray_quote_yield(self):
        lines = _read("baked-ziti-only.mmf").splitlines()
        title_idx = meal_master._find_field_line_index(lines, "Title:")
        _, body_start = meal_master._extract_ingredient_lines(lines, title_idx)
        _, tail_start = meal_master._extract_instructions(lines, body_start)
        source, real_yield_text, extra_notes, cuisine = meal_master._parse_tail_fields(lines, tail_start)
        self.assertEqual(source, "example.com")
        self.assertEqual(real_yield_text, "6 Servings")
        self.assertEqual(extra_notes, ["Easy to make"])
        self.assertEqual(cuisine, "Italian")

    def test_multiline_notes_block_joins_with_single_space(self):
        lines = _read("multiline-notes.mmf").splitlines()
        title_idx = meal_master._find_field_line_index(lines, "Title:")
        _, body_start = meal_master._extract_ingredient_lines(lines, title_idx)
        _, tail_start = meal_master._extract_instructions(lines, body_start)
        _, _, extra_notes, _ = meal_master._parse_tail_fields(lines, tail_start)
        self.assertEqual(
            extra_notes,
            ["Can replace chicken stock with vegetable stock or water. Very long cook time and prep time."],
        )

    def test_missing_yield_footer_leaves_real_yield_text_empty(self):
        lines = _read("no-yield-footer.mmf").splitlines()
        title_idx = meal_master._find_field_line_index(lines, "Title:")
        _, body_start = meal_master._extract_ingredient_lines(lines, title_idx)
        _, tail_start = meal_master._extract_instructions(lines, body_start)
        _, real_yield_text, _, cuisine = meal_master._parse_tail_fields(lines, tail_start)
        self.assertEqual(real_yield_text, "")
        self.assertEqual(cuisine, "American")

    def test_empty_quoted_cuisine_resolves_to_empty_string(self):
        lines = _read("empty-cuisine.mmf").splitlines()
        title_idx = meal_master._find_field_line_index(lines, "Title:")
        _, body_start = meal_master._extract_ingredient_lines(lines, title_idx)
        _, tail_start = meal_master._extract_instructions(lines, body_start)
        _, _, _, cuisine = meal_master._parse_tail_fields(lines, tail_start)
        self.assertEqual(cuisine, "")


class TestParseYield(unittest.TestCase):
    def test_parses_amount_and_unit(self):
        self.assertEqual(meal_master._parse_yield("6 Servings"), {"amount": 6, "unit": "servings"})

    def test_zero_amount_treated_as_absent(self):
        self.assertIsNone(meal_master._parse_yield("0 Servings"))

    def test_empty_text_returns_none(self):
        self.assertIsNone(meal_master._parse_yield(""))


class TestParseChunk(unittest.TestCase):
    def test_assembles_full_recipe_dict(self):
        chunk = _read("baked-ziti-only.mmf")
        data = meal_master._parse_chunk(chunk)
        self.assertEqual(data["recipe_uuid"], "None")
        self.assertEqual(data["recipe_name"], "Baked Ziti")
        self.assertEqual(data["category"], "None")
        self.assertEqual(data["subcategory"], "Pasta")
        self.assertEqual(len(data["ingredients"]), 10)
        self.assertEqual(len(data["steps"]), 2)
        self.assertEqual(data["yields"], [{"amount": 6, "unit": "servings"}])
        self.assertEqual(
            data["notes"],
            ["Source: example.com", "Easy to make", "Cuisine: Italian"],
        )

    def test_missing_title_raises_value_error(self):
        chunk = _read("missing-title.mmf")
        with self.assertRaises(ValueError):
            meal_master._parse_chunk(chunk)

    def test_no_yield_footer_falls_back_to_no_yields_key(self):
        chunk = _read("no-yield-footer.mmf")
        data = meal_master._parse_chunk(chunk)
        self.assertNotIn("yields", data)

    def test_empty_categories_and_cuisine_omitted_from_notes(self):
        chunk = _read("empty-cuisine.mmf")
        data = meal_master._parse_chunk(chunk)
        self.assertEqual(data["notes"], ["Source: Example Diner", "Serve squares on toasted muffins."])
        self.assertEqual(data["category"], "None")
        self.assertEqual(data["subcategory"], "None")

    def test_subcategory_reflects_raw_mmf_category_when_present(self):
        chunk = _read("rice-croquettes-only.mmf")
        data = meal_master._parse_chunk(chunk)
        self.assertEqual(data["subcategory"], "Rice")
        self.assertEqual(data["category"], "None")


class TestParseMealMaster(unittest.TestCase):
    def test_two_recipe_file_produces_two_yaml_recipes_and_no_errors(self):
        text = _read("baked-ziti-only.mmf") + "\n" + _read("rice-croquettes-only.mmf")
        result = meal_master.parse_meal_master(text)
        self.assertEqual(len(result.recipes), 2)
        self.assertEqual(result.errors, [])
        titles = {r["title"] for r in result.recipes}
        self.assertEqual(titles, {"Baked Ziti", "Rice Croquettes with Ham and Mozzarella"})
        for r in result.recipes:
            parsed_back = yaml.safe_load(r["yaml_text"])
            self.assertEqual(parsed_back["recipe_name"], r["title"])

    def test_unparseable_recipe_is_reported_as_error_without_aborting_others(self):
        text = _read("missing-title.mmf") + "\n" + _read("baked-ziti-only.mmf")
        result = meal_master.parse_meal_master(text)
        self.assertEqual(len(result.recipes), 1)
        self.assertEqual(result.recipes[0]["title"], "Baked Ziti")
        self.assertEqual(len(result.errors), 1)
        self.assertIn("Missing Title", result.errors[0][1])

    def test_collection_file_parses_every_recipe(self):
        result = meal_master.parse_meal_master(_read("collection.mmf"))
        self.assertEqual(len(result.errors), 0)
        titles = {r["title"] for r in result.recipes}
        self.assertEqual(titles, {"Baked Ziti", "Rice Croquettes with Ham and Mozzarella",
                                  "Layered Pasta Bake", "Pan Seared Steak"})

    def test_collection_has_no_bogus_section_header_ingredients(self):
        result = meal_master.parse_meal_master(_read("collection.mmf"))
        self.assertNotIn("===", "".join(r["yaml_text"] for r in result.recipes))

    def test_collection_ingredients_carry_their_sections(self):
        result = meal_master.parse_meal_master(_read("collection.mmf"))
        bake = next(r for r in result.recipes if r["title"] == "Layered Pasta Bake")
        parsed = yaml.safe_load(bake["yaml_text"])
        sections = {next(iter(i)): i[next(iter(i))].get("section") for i in parsed["ingredients"]}
        self.assertEqual(sections["Hot milk"], "White Sauce")
        self.assertEqual(sections["Ground beef"], "Meat Sauce")
        self.assertEqual(sections["Lasagna sheets"], "Pasta")

    def test_generated_yaml_includes_subcategory_from_raw_category(self):
        text = _read("baked-ziti-only.mmf")
        result = meal_master.parse_meal_master(text)
        parsed_back = yaml.safe_load(result.recipes[0]["yaml_text"])
        self.assertEqual(parsed_back["subcategory"], "Pasta")
        self.assertEqual(parsed_back["category"], "None")


if __name__ == "__main__":
    unittest.main()
