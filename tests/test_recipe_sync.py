import re
import shutil
import sqlite3
import tempfile
import time
import unittest
import yaml
from pathlib import Path

import db
import meal_calendar
import recipe_sync

FIXTURES = Path(__file__).parent / "fixtures"


class TestEnsureRecipeUuid(unittest.TestCase):
    def test_leaves_existing_uuid_untouched(self):
        raw = "recipe_uuid: blah-001\nrecipe_name: X\n"
        result = recipe_sync._ensure_recipe_uuid(raw)
        self.assertEqual(result, raw)

    def test_generates_uuid_when_value_is_none_token(self):
        raw = "recipe_uuid: None\nrecipe_name: X\n"
        result = recipe_sync._ensure_recipe_uuid(raw)
        self.assertNotEqual(result, raw)
        self.assertNotIn("recipe_uuid: None", result)
        match = re.search(r"^recipe_uuid: (.+)$", result, re.MULTILINE)
        self.assertIsNotNone(match)
        self.assertRegex(match.group(1).strip(), r"^[0-9a-f-]{36}$")

    def test_generates_uuid_and_prepends_when_key_absent(self):
        raw = "recipe_name: X\nsteps: []\n"
        result = recipe_sync._ensure_recipe_uuid(raw)
        self.assertTrue(result.startswith("recipe_uuid: "))
        self.assertIn("recipe_name: X\nsteps: []\n", result)


class TestReassignRecipeUuid(unittest.TestCase):
    def test_forces_new_uuid_replacing_existing_value(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "recipe.yaml"
            path.write_text("recipe_uuid: old-value\nrecipe_name: X\n", encoding="utf-8")

            recipe_sync.reassign_recipe_uuid(path)

            content = path.read_text(encoding="utf-8")
            self.assertNotIn("recipe_uuid: old-value", content)
            match = re.search(r"^recipe_uuid: (.+)$", content, re.MULTILINE)
            self.assertIsNotNone(match)
            self.assertRegex(match.group(1).strip(), r"^[0-9a-f-]{36}$")

    def test_two_calls_produce_different_uuids(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "recipe.yaml"
            path.write_text("recipe_uuid: old-value\nrecipe_name: X\n", encoding="utf-8")

            recipe_sync.reassign_recipe_uuid(path)
            first = re.search(r"^recipe_uuid: (.+)$", path.read_text(encoding="utf-8"), re.MULTILINE).group(1)
            recipe_sync.reassign_recipe_uuid(path)
            second = re.search(r"^recipe_uuid: (.+)$", path.read_text(encoding="utf-8"), re.MULTILINE).group(1)

            self.assertNotEqual(first, second)


class TestFindDuplicateUuids(unittest.TestCase):
    def test_no_duplicates_returns_empty_dict(self):
        with tempfile.TemporaryDirectory() as tmp:
            recipes_dir = Path(tmp) / "recipes"
            recipes_dir.mkdir()
            shutil.copy(FIXTURES / "banana-bread.yaml", recipes_dir)
            shutil.copy(FIXTURES / "orf-sample-1.yaml", recipes_dir)

            self.assertEqual(recipe_sync.find_duplicate_uuids(recipes_dir), {})

    def test_two_files_sharing_a_uuid_are_grouped(self):
        with tempfile.TemporaryDirectory() as tmp:
            recipes_dir = Path(tmp) / "recipes"
            recipes_dir.mkdir()
            (recipes_dir / "a.yaml").write_text(
                "recipe_uuid: dupe-1\nrecipe_name: A\nsteps: []\ningredients: []\n",
                encoding="utf-8",
            )
            (recipes_dir / "b.yaml").write_text(
                "recipe_uuid: dupe-1\nrecipe_name: B\nsteps: []\ningredients: []\n",
                encoding="utf-8",
            )

            result = recipe_sync.find_duplicate_uuids(recipes_dir)

            self.assertEqual(len(result), 1)
            files = result["dupe-1"]
            self.assertEqual(set(files), {"a.yaml", "b.yaml"})

    def test_malformed_file_is_skipped_not_raised(self):
        with tempfile.TemporaryDirectory() as tmp:
            recipes_dir = Path(tmp) / "recipes"
            recipes_dir.mkdir()
            shutil.copy(FIXTURES / "invalid-missing-name.yaml", recipes_dir)

            self.assertEqual(recipe_sync.find_duplicate_uuids(recipes_dir), {})


class TestPatchYamlField(unittest.TestCase):
    def test_replaces_existing_line(self):
        raw = "category: None\nrecipe_name: X\n"
        result = recipe_sync.patch_yaml_field(raw, "category", "Main Dishes")
        self.assertIn("category: Main Dishes", result)
        self.assertIn("recipe_name: X", result)

    def test_inserts_when_field_absent(self):
        raw = "recipe_name: X\n"
        result = recipe_sync.patch_yaml_field(raw, "category", "Desserts")
        self.assertTrue(result.startswith("category: Desserts\n"))
        self.assertIn("recipe_name: X", result)

    def test_value_with_colon_and_quote_is_safely_yaml_encoded(self):
        raw = "subcategory: None\nrecipe_name: X\n"
        result = recipe_sync.patch_yaml_field(raw, "subcategory", 'Beef: "the best"')
        parsed = yaml.safe_load(result)
        self.assertEqual(parsed["subcategory"], 'Beef: "the best"')

    def test_value_with_backslash_does_not_break_substitution(self):
        raw = "subcategory: None\nrecipe_name: X\n"
        result = recipe_sync.patch_yaml_field(raw, "subcategory", "Beef\\Pork")
        parsed = yaml.safe_load(result)
        self.assertEqual(parsed["subcategory"], "Beef\\Pork")

    def test_repatching_a_previously_wrapped_long_value_leaves_no_fragments(self):
        # yaml.safe_dump wraps scalars longer than ~80 chars onto multiple
        # indented lines by default. patch_yaml_field's regex for finding an
        # existing field only matches the FIRST line of that field, so if a
        # prior patch_yaml_field call had produced a wrapped value (i.e. the
        # bug were still present), re-patching it would leave the old
        # value's continuation lines behind as orphaned fragments. The fix
        # disables wrapping in patch_yaml_field's own dump so this never
        # happens, however long the value.
        raw = "recipe_name: X\n"
        long_value = "A" * 100
        first_result = recipe_sync.patch_yaml_field(raw, "category", long_value)
        # Sanity check the fix: patch_yaml_field's own output must never
        # wrap, even for a value this long — otherwise the very next patch
        # of this field would corrupt it.
        self.assertEqual(len(first_result.splitlines()), 2)

        other_value = "B" * 50
        result = recipe_sync.patch_yaml_field(first_result, "category", other_value)

        parsed = yaml.safe_load(result)
        self.assertEqual(parsed["category"], other_value)
        self.assertEqual(parsed["recipe_name"], "X")
        self.assertNotIn("A", result)

    def test_write_uuid_line_still_works_via_generalized_function(self):
        raw = "recipe_uuid: old\nrecipe_name: X\n"
        result = recipe_sync._write_uuid_line(raw, "new-uuid-123")
        self.assertIn("recipe_uuid: new-uuid-123", result)
        self.assertNotIn("recipe_uuid: old", result)


class TestNormalizeIngredientSection(unittest.TestCase):
    def test_section_passes_through_when_present(self):
        result = recipe_sync._normalize_ingredient(
            {"Butter": {"amounts": [], "section": "Sauce One"}}
        )
        self.assertEqual(result["section"], "Sauce One")

    def test_section_is_none_when_absent(self):
        result = recipe_sync._normalize_ingredient({"Butter": {"amounts": []}})
        self.assertIsNone(result["section"])


class TestParseRecipeYaml(unittest.TestCase):
    def test_banana_bread_required_fields(self):
        text = (FIXTURES / "banana-bread.yaml").read_text(encoding="utf-8")
        parsed = recipe_sync.parse_recipe_yaml(text)
        self.assertEqual(parsed["name"], "Banana Bread")
        self.assertEqual(len(parsed["ingredients"]), 13)
        self.assertEqual(len(parsed["steps"]), 9)

    def test_banana_bread_none_string_fields_become_none(self):
        text = (FIXTURES / "banana-bread.yaml").read_text(encoding="utf-8")
        parsed = recipe_sync.parse_recipe_yaml(text)
        self.assertIsNone(parsed["recipe_uuid"])
        self.assertIsNone(parsed["source_url"])
        self.assertIsNone(parsed["source_book"])

    def test_banana_bread_source_authors_wrapped_in_list(self):
        text = (FIXTURES / "banana-bread.yaml").read_text(encoding="utf-8")
        parsed = recipe_sync.parse_recipe_yaml(text)
        self.assertEqual(parsed["source_authors"], ["Joseph Hall <perlhoser@gmail.com>"])

    def test_banana_bread_usda_num_preserved_as_text_with_leading_zero(self):
        text = (FIXTURES / "banana-bread.yaml").read_text(encoding="utf-8")
        parsed = recipe_sync.parse_recipe_yaml(text)
        salt = next(i for i in parsed["ingredients"] if i["name"] == "Salt")
        self.assertEqual(salt["usda_num"], "02047")
        flour = next(i for i in parsed["ingredients"] if i["name"] == "All Purpose Flour")
        self.assertEqual(flour["usda_num"], "20581")  # unquoted int, no leading zero to lose

    def test_banana_bread_substitutions_preserved(self):
        text = (FIXTURES / "banana-bread.yaml").read_text(encoding="utf-8")
        parsed = recipe_sync.parse_recipe_yaml(text)
        flour = next(i for i in parsed["ingredients"] if i["name"] == "All Purpose Flour")
        self.assertEqual(len(flour["substitutions"]), 1)
        self.assertEqual(flour["substitutions"][0]["name"], "Oat Flour")
        self.assertEqual(flour["substitutions"][0]["usda_num"], "08122")

    def test_banana_bread_yields(self):
        text = (FIXTURES / "banana-bread.yaml").read_text(encoding="utf-8")
        parsed = recipe_sync.parse_recipe_yaml(text)
        self.assertEqual(parsed["yields"], [{"amount": 3, "unit": "loaves"}])

    def test_orf_sample_shorthand_yields_normalized(self):
        text = (FIXTURES / "orf-sample-1.yaml").read_text(encoding="utf-8")
        parsed = recipe_sync.parse_recipe_yaml(text)
        self.assertEqual(
            parsed["yields"],
            [{"amount": 4, "unit": "servings"}, {"amount": 10, "unit": "servings"}],
        )

    def test_orf_sample_lowercase_none_tokens(self):
        text = (FIXTURES / "orf-sample-1.yaml").read_text(encoding="utf-8")
        parsed = recipe_sync.parse_recipe_yaml(text)
        self.assertIsNone(parsed["oven_temp"])
        self.assertIsNone(parsed["oven_fan"])
        self.assertIsNone(parsed["oven_time"])
        self.assertIsNone(parsed["source_book"])

    def test_orf_sample_multi_yield_ingredient_amounts(self):
        text = (FIXTURES / "orf-sample-1.yaml").read_text(encoding="utf-8")
        parsed = recipe_sync.parse_recipe_yaml(text)
        apple = next(i for i in parsed["ingredients"] if i["name"] == "apple")
        self.assertEqual(
            apple["amounts"],
            [{"amount": 4, "unit": "each"}, {"amount": 10, "unit": "each"}],
        )
        self.assertEqual(apple["processing"], ["whole", "raw"])

    def test_orf_sample_steps_with_haccp_and_notes(self):
        text = (FIXTURES / "orf-sample-1.yaml").read_text(encoding="utf-8")
        parsed = recipe_sync.parse_recipe_yaml(text)
        self.assertEqual(parsed["steps"][0]["step_text"], "Hand out the apples")
        self.assertEqual(
            parsed["steps"][0]["haccp"], {"control_point": "The apples must be clean"}
        )
        self.assertEqual(len(parsed["steps"][0]["notes"]), 2)
        self.assertIsNone(parsed["steps"][2]["haccp"])  # "Enjoy" step has no haccp

    def test_category_and_subcategory_present(self):
        text = "recipe_name: X\nsteps: []\ningredients: []\ncategory: Main Dishes\nsubcategory: Beef\n"
        parsed = recipe_sync.parse_recipe_yaml(text)
        self.assertEqual(parsed["category"], "Main Dishes")
        self.assertEqual(parsed["subcategory"], "Beef")

    def test_category_and_subcategory_absent_default_to_none(self):
        text = "recipe_name: X\nsteps: []\ningredients: []\n"
        parsed = recipe_sync.parse_recipe_yaml(text)
        self.assertIsNone(parsed["category"])
        self.assertIsNone(parsed["subcategory"])

    def test_category_none_string_token_becomes_none(self):
        text = "recipe_name: X\nsteps: []\ningredients: []\ncategory: None\nsubcategory: ''\n"
        parsed = recipe_sync.parse_recipe_yaml(text)
        self.assertIsNone(parsed["category"])
        self.assertIsNone(parsed["subcategory"])

    def test_missing_recipe_name_raises(self):
        with self.assertRaises(ValueError) as ctx:
            recipe_sync.parse_recipe_yaml(
                (FIXTURES / "invalid-missing-name.yaml").read_text(encoding="utf-8")
            )
        self.assertIn("recipe_name", str(ctx.exception))

    def test_missing_steps_raises(self):
        with self.assertRaises(ValueError) as ctx:
            recipe_sync.parse_recipe_yaml("recipe_name: X\ningredients: []\n")
        self.assertIn("steps", str(ctx.exception))

    def test_null_ingredients_raises_value_error_not_type_error(self):
        with self.assertRaises(ValueError) as ctx:
            recipe_sync.parse_recipe_yaml("recipe_name: X\nsteps:\n  - step: hi\ningredients:\n")
        self.assertIn("ingredients", str(ctx.exception))

    def test_null_steps_raises_value_error_not_type_error(self):
        with self.assertRaises(ValueError) as ctx:
            recipe_sync.parse_recipe_yaml("recipe_name: X\nsteps:\ningredients: []\n")
        self.assertIn("steps", str(ctx.exception))


class TestImperialConversionOnParse(unittest.TestCase):
    def _parse_fixture(self):
        text = (FIXTURES / "metric-ingredients.yaml").read_text(encoding="utf-8")
        return recipe_sync.parse_recipe_yaml(text)

    def test_metric_volume_converted_to_imperial(self):
        parsed = self._parse_fixture()
        milk = next(i for i in parsed["ingredients"] if i["name"] == "Milk")
        self.assertEqual(milk["amounts"], [{"amount": "1", "unit": "pt"}])

    def test_metric_weight_converted_to_imperial(self):
        parsed = self._parse_fixture()
        butter = next(i for i in parsed["ingredients"] if i["name"] == "Butter")
        self.assertEqual(butter["amounts"], [{"amount": "2", "unit": "oz"}])

    def test_substitution_metric_amount_also_converted(self):
        parsed = self._parse_fixture()
        butter = next(i for i in parsed["ingredients"] if i["name"] == "Butter")
        margarine = butter["substitutions"][0]
        self.assertEqual(margarine["name"], "Margarine")
        self.assertEqual(margarine["amounts"], [{"amount": "4", "unit": "oz"}])

    def test_already_imperial_amount_is_unchanged(self):
        parsed = self._parse_fixture()
        flour = next(i for i in parsed["ingredients"] if i["name"] == "Flour")
        self.assertEqual(flour["amounts"], [{"amount": 2, "unit": "cups"}])


class TestSyncRecipes(unittest.TestCase):
    def _copy_fixture(self, name, dest_dir):
        shutil.copy(FIXTURES / name, Path(dest_dir) / name)

    def test_category_and_subcategory_persisted_to_db(self):
        with tempfile.TemporaryDirectory() as tmp:
            recipes_dir = Path(tmp) / "recipes"
            recipes_dir.mkdir()
            (recipes_dir / "a.yaml").write_text(
                "recipe_name: A\nsteps: []\ningredients: []\n"
                "category: Main Dishes\nsubcategory: Beef\n",
                encoding="utf-8",
            )
            db_path = Path(tmp) / "mealplanner.db"

            recipe_sync.sync_recipes(recipes_dir, db_path)

            conn = db.get_connection(db_path)
            try:
                row = conn.execute(
                    'SELECT "category", "subcategory" FROM "recipe" WHERE "file_path" = ?',
                    ("a.yaml",),
                ).fetchone()
            finally:
                conn.close()
            self.assertEqual(row["category"], "Main Dishes")
            self.assertEqual(row["subcategory"], "Beef")

    def test_indexes_new_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            recipes_dir = Path(tmp) / "recipes"
            recipes_dir.mkdir()
            self._copy_fixture("banana-bread.yaml", recipes_dir)
            self._copy_fixture("orf-sample-1.yaml", recipes_dir)
            db_path = Path(tmp) / "mealplanner.db"

            result = recipe_sync.sync_recipes(recipes_dir, db_path)

            self.assertEqual(result.indexed, 2)
            self.assertEqual(result.unchanged, 0)
            self.assertEqual(result.removed, 0)
            self.assertEqual(result.errors, [])

            conn = sqlite3.connect(str(db_path))
            try:
                self.assertEqual(conn.execute("SELECT COUNT(*) FROM recipe").fetchone()[0], 2)
                self.assertEqual(
                    conn.execute("SELECT COUNT(*) FROM recipe_ingredient").fetchone()[0], 15
                )
                self.assertEqual(
                    conn.execute("SELECT COUNT(*) FROM recipe_step").fetchone()[0], 12
                )
            finally:
                conn.close()

    def test_second_sync_with_no_changes_reports_unchanged(self):
        with tempfile.TemporaryDirectory() as tmp:
            recipes_dir = Path(tmp) / "recipes"
            recipes_dir.mkdir()
            self._copy_fixture("banana-bread.yaml", recipes_dir)
            db_path = Path(tmp) / "mealplanner.db"

            recipe_sync.sync_recipes(recipes_dir, db_path)
            result = recipe_sync.sync_recipes(recipes_dir, db_path)

            self.assertEqual(result.indexed, 0)
            self.assertEqual(result.unchanged, 1)

    def test_touched_file_is_reindexed(self):
        with tempfile.TemporaryDirectory() as tmp:
            recipes_dir = Path(tmp) / "recipes"
            recipes_dir.mkdir()
            self._copy_fixture("banana-bread.yaml", recipes_dir)
            self._copy_fixture("orf-sample-1.yaml", recipes_dir)
            db_path = Path(tmp) / "mealplanner.db"
            recipe_sync.sync_recipes(recipes_dir, db_path)

            # Capture the recipe id of banana bread before modification
            conn = sqlite3.connect(str(db_path))
            try:
                banana_id_before = conn.execute(
                    "SELECT id FROM recipe WHERE file_path = 'banana-bread.yaml'"
                ).fetchone()[0]
            finally:
                conn.close()

            recipe_path = recipes_dir / "banana-bread.yaml"
            content = recipe_path.read_text(encoding="utf-8")
            time.sleep(0.01)
            recipe_path.write_text(content.replace("Banana Bread", "Banana Bread v2"), encoding="utf-8")

            result = recipe_sync.sync_recipes(recipes_dir, db_path)

            self.assertEqual(result.indexed, 1)
            self.assertEqual(result.unchanged, 1)
            conn = sqlite3.connect(str(db_path))
            try:
                name = conn.execute("SELECT name FROM recipe WHERE file_path = 'banana-bread.yaml'").fetchone()[0]
                self.assertEqual(name, "Banana Bread v2")
                # Verify recipe id is preserved (not deleted and recreated)
                banana_id_after = conn.execute(
                    "SELECT id FROM recipe WHERE file_path = 'banana-bread.yaml'"
                ).fetchone()[0]
                self.assertEqual(banana_id_after, banana_id_before)
                # same recipe_id reused, not duplicated
                self.assertEqual(conn.execute("SELECT COUNT(*) FROM recipe").fetchone()[0], 2)
            finally:
                conn.close()

    def test_deleted_file_removes_recipe_and_children(self):
        with tempfile.TemporaryDirectory() as tmp:
            recipes_dir = Path(tmp) / "recipes"
            recipes_dir.mkdir()
            self._copy_fixture("banana-bread.yaml", recipes_dir)
            db_path = Path(tmp) / "mealplanner.db"
            recipe_sync.sync_recipes(recipes_dir, db_path)

            (recipes_dir / "banana-bread.yaml").unlink()
            result = recipe_sync.sync_recipes(recipes_dir, db_path)

            self.assertEqual(result.removed, 1)
            conn = sqlite3.connect(str(db_path))
            try:
                self.assertEqual(conn.execute("SELECT COUNT(*) FROM recipe").fetchone()[0], 0)
                self.assertEqual(
                    conn.execute("SELECT COUNT(*) FROM recipe_ingredient").fetchone()[0], 0
                )
            finally:
                conn.close()

    def test_invalid_file_reported_as_error_without_stopping_sync(self):
        with tempfile.TemporaryDirectory() as tmp:
            recipes_dir = Path(tmp) / "recipes"
            recipes_dir.mkdir()
            self._copy_fixture("banana-bread.yaml", recipes_dir)
            self._copy_fixture("invalid-missing-name.yaml", recipes_dir)
            db_path = Path(tmp) / "mealplanner.db"

            result = recipe_sync.sync_recipes(recipes_dir, db_path)

            self.assertEqual(result.indexed, 1)
            self.assertEqual(len(result.errors), 1)
            self.assertEqual(result.errors[0][0], "invalid-missing-name.yaml")
            self.assertIn("recipe_name", result.errors[0][1])

    def test_renamed_file_preserves_recipe_id_and_meal_plan(self):
        with tempfile.TemporaryDirectory() as tmp:
            recipes_dir = Path(tmp) / "recipes"
            recipes_dir.mkdir()
            self._copy_fixture("orf-sample-1.yaml", recipes_dir)  # already has a real uuid
            db_path = Path(tmp) / "mealplanner.db"
            recipe_sync.sync_recipes(recipes_dir, db_path)

            conn = db.get_connection(db_path)
            try:
                recipe_id_before = conn.execute(
                    "SELECT id FROM recipe WHERE file_path = 'orf-sample-1.yaml'"
                ).fetchone()[0]
                meal_calendar.assign_meal(conn, "2026-09-01", "Dinner", recipe_id_before)
            finally:
                conn.close()

            (recipes_dir / "orf-sample-1.yaml").rename(recipes_dir / "renamed-recipe.yaml")
            result = recipe_sync.sync_recipes(recipes_dir, db_path)

            self.assertEqual(len(result.errors), 0)
            conn = db.get_connection(db_path)
            try:
                row = conn.execute(
                    "SELECT id, file_path FROM recipe WHERE recipe_uuid = 'blah-001'"
                ).fetchone()
                self.assertEqual(row["file_path"], "renamed-recipe.yaml")
                self.assertEqual(row["id"], recipe_id_before)
                self.assertEqual(conn.execute("SELECT COUNT(*) FROM recipe").fetchone()[0], 1)

                plan_row = conn.execute(
                    'SELECT "recipe_id" FROM "meal_plan" WHERE "date" = ? AND "slot" = ?',
                    ("2026-09-01", "Dinner"),
                ).fetchone()
                self.assertIsNotNone(plan_row)
                self.assertEqual(plan_row["recipe_id"], recipe_id_before)
            finally:
                conn.close()

    def test_sync_writes_generated_uuid_back_to_disk(self):
        with tempfile.TemporaryDirectory() as tmp:
            recipes_dir = Path(tmp) / "recipes"
            recipes_dir.mkdir()
            self._copy_fixture("banana-bread.yaml", recipes_dir)  # recipe_uuid: None
            db_path = Path(tmp) / "mealplanner.db"

            recipe_sync.sync_recipes(recipes_dir, db_path)

            content = (recipes_dir / "banana-bread.yaml").read_text(encoding="utf-8")
            self.assertNotIn("recipe_uuid: None", content)
            match = re.search(r"^recipe_uuid: (.+)$", content, re.MULTILINE)
            self.assertIsNotNone(match)
            self.assertRegex(match.group(1).strip(), r"^[0-9a-f-]{36}$")

    def test_duplicate_uuid_within_same_sync_is_reported_and_skipped(self):
        with tempfile.TemporaryDirectory() as tmp:
            recipes_dir = Path(tmp) / "recipes"
            recipes_dir.mkdir()
            (recipes_dir / "a.yaml").write_text(
                "recipe_uuid: dupe-1\nrecipe_name: A\nsteps: []\ningredients: []\n",
                encoding="utf-8",
            )
            (recipes_dir / "b.yaml").write_text(
                "recipe_uuid: dupe-1\nrecipe_name: B\nsteps: []\ningredients: []\n",
                encoding="utf-8",
            )
            db_path = Path(tmp) / "mealplanner.db"

            result = recipe_sync.sync_recipes(recipes_dir, db_path)

            self.assertEqual(result.indexed, 1)
            self.assertEqual(len(result.errors), 1)
            self.assertIn("dupe-1", result.errors[0][1])

            conn = db.get_connection(db_path)
            try:
                self.assertEqual(conn.execute("SELECT COUNT(*) FROM recipe").fetchone()[0], 1)
            finally:
                conn.close()

    def test_duplicate_uuid_against_existing_unchanged_recipe_is_reported(self):
        with tempfile.TemporaryDirectory() as tmp:
            recipes_dir = Path(tmp) / "recipes"
            recipes_dir.mkdir()
            (recipes_dir / "a.yaml").write_text(
                "recipe_uuid: dupe-1\nrecipe_name: A\nsteps: []\ningredients: []\n",
                encoding="utf-8",
            )
            db_path = Path(tmp) / "mealplanner.db"
            recipe_sync.sync_recipes(recipes_dir, db_path)  # a.yaml indexed, unchanged next time

            (recipes_dir / "b.yaml").write_text(
                "recipe_uuid: dupe-1\nrecipe_name: B\nsteps: []\ningredients: []\n",
                encoding="utf-8",
            )
            result = recipe_sync.sync_recipes(recipes_dir, db_path)

            self.assertEqual(result.indexed, 0)
            self.assertEqual(len(result.errors), 1)
            self.assertEqual(result.errors[0][0], "b.yaml")

            conn = db.get_connection(db_path)
            try:
                self.assertEqual(conn.execute("SELECT COUNT(*) FROM recipe").fetchone()[0], 1)
            finally:
                conn.close()

    def test_regenerated_blank_uuid_on_resync_updates_existing_row_by_file_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            recipes_dir = Path(tmp) / "recipes"
            recipes_dir.mkdir()
            recipe_path = recipes_dir / "a.yaml"
            blank_uuid_yaml = "recipe_uuid: None\nrecipe_name: A\nsteps: []\ningredients: []\n"
            recipe_path.write_text(blank_uuid_yaml, encoding="utf-8")
            db_path = Path(tmp) / "mealplanner.db"
            recipe_sync.sync_recipes(recipes_dir, db_path)

            conn = db.get_connection(db_path)
            try:
                recipe_id_before = conn.execute(
                    "SELECT id FROM recipe WHERE file_path = 'a.yaml'"
                ).fetchone()[0]
            finally:
                conn.close()

            # Overwrite with the same blank-uuid content, simulating a fresh re-upload of the
            # same file. sync_recipes mints a brand-new random uuid for it (different from the
            # first sync's), so this must not crash trying to insert a second row for a
            # file_path that already has one.
            time.sleep(0.01)
            recipe_path.write_text(blank_uuid_yaml, encoding="utf-8")
            result = recipe_sync.sync_recipes(recipes_dir, db_path)

            self.assertEqual(result.errors, [])
            conn = db.get_connection(db_path)
            try:
                rows = conn.execute("SELECT id FROM recipe WHERE file_path = 'a.yaml'").fetchall()
                self.assertEqual(len(rows), 1)
                self.assertEqual(rows[0]["id"], recipe_id_before)
                self.assertEqual(conn.execute("SELECT COUNT(*) FROM recipe").fetchone()[0], 1)
            finally:
                conn.close()

    def test_deleting_recipe_file_also_removes_its_meal_plan_rows(self):
        with tempfile.TemporaryDirectory() as tmp:
            recipes_dir = Path(tmp) / "recipes"
            recipes_dir.mkdir()
            self._copy_fixture("banana-bread.yaml", recipes_dir)
            db_path = Path(tmp) / "mealplanner.db"
            recipe_sync.sync_recipes(recipes_dir, db_path)

            conn = db.get_connection(db_path)
            try:
                recipe_id = conn.execute(
                    "SELECT id FROM recipe WHERE file_path = ?", ("banana-bread.yaml",)
                ).fetchone()[0]
                meal_calendar.assign_meal(conn, "2026-09-01", "Dinner", recipe_id)
            finally:
                conn.close()

            (recipes_dir / "banana-bread.yaml").unlink()
            recipe_sync.sync_recipes(recipes_dir, db_path)

            conn = db.get_connection(db_path)
            try:
                remaining = conn.execute('SELECT * FROM "meal_plan"').fetchall()
                self.assertEqual(remaining, [])
            finally:
                conn.close()


class TestWalkAmountSlots(unittest.TestCase):
    def test_walks_ingredients_and_nested_substitutions_in_order(self):
        data = {
            "ingredients": [
                {"Flour": {"amounts": [{"amount": "2", "unit": "cups"}]}},
                {
                    "Butter": {
                        "amounts": [{"amount": "1", "unit": "cup"}],
                        "substitutions": [
                            {"Margarine": {"amounts": [{"amount": "1", "unit": "cup"}]}},
                        ],
                    }
                },
            ]
        }
        slots = recipe_sync.walk_amount_slots(data)
        names = [name for name, _, _ in slots]
        self.assertEqual(names, ["Flour", "Butter", "Margarine"])

    def test_ingredient_with_multiple_amounts_yields_each_one(self):
        data = {"ingredients": [{"Apple": {"amounts": [
            {"amount": "4", "unit": "each"}, {"amount": "10", "unit": "each"},
        ]}}]}
        slots = recipe_sync.walk_amount_slots(data)
        self.assertEqual(len(slots), 2)

    def test_returned_dicts_are_the_actual_nested_objects(self):
        data = {"ingredients": [{"Flour": {"amounts": [{"amount": "2", "unit": "cups"}]}}]}
        _, amt, _ = recipe_sync.walk_amount_slots(data)[0]
        amt["amount"] = "9"
        self.assertEqual(data["ingredients"][0]["Flour"]["amounts"][0]["amount"], "9")

    def test_notes_are_returned_alongside_each_slot(self):
        data = {"ingredients": [
            {"Onion": {"amounts": [{"amount": "1", "unit": "sm"}], "notes": ["chopped"]}},
        ]}
        _, _, notes = recipe_sync.walk_amount_slots(data)[0]
        self.assertEqual(notes, ["chopped"])


class TestFormatIngredientLine(unittest.TestCase):
    def test_full_line_with_amount_unit_and_notes(self):
        line = recipe_sync._format_ingredient_line("Onion", {"amount": "1", "unit": "sm"}, ["chopped"])
        self.assertEqual(line, "1 sm Onion -- chopped")

    def test_no_amount_with_notes(self):
        line = recipe_sync._format_ingredient_line("Salt", {"amount": "", "unit": ""}, ["to taste"])
        self.assertEqual(line, "Salt -- to taste")

    def test_name_only_with_no_amount_unit_or_notes(self):
        line = recipe_sync._format_ingredient_line(
            "Freshly-ground black pepper", {"amount": "", "unit": ""}, None
        )
        self.assertEqual(line, "Freshly-ground black pepper")

    def test_non_string_note_does_not_crash(self):
        line = recipe_sync._format_ingredient_line("Cinnamon", {"amount": "1", "unit": "tsp"}, [350])
        self.assertEqual(line, "1 tsp Cinnamon -- 350")

    def test_zero_amount_is_not_dropped(self):
        line = recipe_sync._format_ingredient_line("Salt", {"amount": 0, "unit": "tsp"}, None)
        self.assertEqual(line, "0 tsp Salt")


class TestFindUnparseableAmountSlots(unittest.TestCase):
    def test_flags_only_the_unparseable_slot(self):
        data = {"ingredients": [
            {"Flour": {"amounts": [{"amount": "2", "unit": "cups"}]}},
            {"Wine": {"amounts": [{"amount": "750    750", "unit": "ml"}]}},
        ]}
        issues = recipe_sync.find_unparseable_amount_slots(data)
        self.assertEqual(len(issues), 1)
        self.assertEqual(issues[0]["slot_index"], 1)
        self.assertEqual(issues[0]["ingredient_name"], "Wine")
        self.assertEqual(issues[0]["amount"], "750    750")
        self.assertEqual(issues[0]["unit"], "ml")
        self.assertEqual(issues[0]["ingredient_line"], "750    750 ml Wine")

    def test_no_issues_when_everything_parses(self):
        data = {"ingredients": [{"Flour": {"amounts": [{"amount": "2", "unit": "cups"}]}}]}
        self.assertEqual(recipe_sync.find_unparseable_amount_slots(data), [])


class TestApplyAmountCorrection(unittest.TestCase):
    def test_overwrites_only_the_matching_slot(self):
        data = {"ingredients": [
            {"Flour": {"amounts": [{"amount": "2", "unit": "cups"}]}},
            {"Wine": {"amounts": [{"amount": "750    750", "unit": "ml"}]}},
        ]}

        recipe_sync.apply_amount_correction(data, 1, "480", "ml")

        self.assertEqual(
            data["ingredients"][0]["Flour"]["amounts"][0], {"amount": "2", "unit": "cups"}
        )
        self.assertEqual(
            data["ingredients"][1]["Wine"]["amounts"][0], {"amount": "1", "unit": "pt"}
        )

    def test_correction_still_unparseable_is_stored_as_typed(self):
        data = {"ingredients": [{"Wine": {"amounts": [{"amount": "bad", "unit": "ml"}]}}]}

        recipe_sync.apply_amount_correction(data, 0, "still bad", "ml")

        self.assertEqual(
            data["ingredients"][0]["Wine"]["amounts"][0],
            {"amount": "still bad", "unit": "ml"},
        )


class TestBuildEditableIngredients(unittest.TestCase):
    def test_single_amount_ingredient_fields(self):
        data = {"ingredients": [{"Flour": {"amounts": [{"amount": "2", "unit": "cups"}]}}]}
        result = recipe_sync.build_editable_ingredients(data)
        self.assertEqual(result, [{
            "index": 0, "name": "Flour", "amount": "2", "unit": "cups",
            "section": None, "notes": [], "multi_amount": False, "needs_input": False,
        }])

    def test_notes_are_exposed_as_a_list(self):
        data = {"ingredients": [
            {"Flour": {"amounts": [{"amount": "2", "unit": "cups"}], "notes": ["sifted", "to taste"]}},
            {"Salt": {"amounts": [{"amount": "1", "unit": "tsp"}], "notes": "bare string"}},
            {"Oil": {"amounts": [{"amount": "1", "unit": "tsp"}], "notes": None}},
        ]}
        result = recipe_sync.build_editable_ingredients(data)
        self.assertEqual([r["notes"] for r in result], [["sifted", "to taste"], ["bare string"], []])

    def test_parse_notes_field_splits_on_semicolons_and_drops_blanks(self):
        self.assertEqual(recipe_sync.parse_notes_field(" sifted ; to taste;; "), ["sifted", "to taste"])
        self.assertEqual(recipe_sync.parse_notes_field(""), [])
        self.assertEqual(recipe_sync.parse_notes_field(None), [])

    def test_unparseable_amount_is_flagged_as_needing_input(self):
        data = {"ingredients": [
            {"Wine": {"amounts": [{"amount": "750    750", "unit": "ml"}]}},
            {"Salt": {"amounts": [{"amount": "1", "unit": "tsp"}]}},
            {"Oil": {"amounts": [{"amount": "2", "unit": "tbsp"}],
                     "substitutions": [{"Butter": {"amounts": [{"amount": "some", "unit": ""}]}}]}},
        ]}
        result = recipe_sync.build_editable_ingredients(data)
        self.assertEqual([r["needs_input"] for r in result], [True, False, True])

    def test_section_is_carried_through(self):
        data = {"ingredients": [
            {"Butter": {"amounts": [{"amount": "2", "unit": "tbsp"}], "section": "Sauce"}},
        ]}
        result = recipe_sync.build_editable_ingredients(data)
        self.assertEqual(result[0]["section"], "Sauce")

    def test_multi_amount_ingredient_is_flagged(self):
        data = {"ingredients": [
            {"Apple": {"amounts": [{"amount": "4", "unit": "each"}, {"amount": "10", "unit": "each"}]}},
        ]}
        result = recipe_sync.build_editable_ingredients(data)
        self.assertTrue(result[0]["multi_amount"])
        self.assertEqual(result[0]["amount"], "4")
        self.assertEqual(result[0]["unit"], "each")

    def test_index_matches_position_in_ingredients_list(self):
        data = {"ingredients": [
            {"Flour": {"amounts": [{"amount": "2", "unit": "cups"}]}},
            {"Sugar": {"amounts": [{"amount": "1", "unit": "cup"}]}},
        ]}
        result = recipe_sync.build_editable_ingredients(data)
        self.assertEqual([r["index"] for r in result], [0, 1])


class TestBuildNewIngredient(unittest.TestCase):
    def test_converts_amount_and_unit_to_imperial(self):
        result = recipe_sync.build_new_ingredient("Milk", "480", "ml", None)
        self.assertEqual(result, {"Milk": {"amounts": [{"amount": "1", "unit": "pt"}]}})

    def test_empty_section_is_omitted_not_stored_as_empty_string(self):
        result = recipe_sync.build_new_ingredient("Flour", "2", "cups", "")
        self.assertNotIn("section", result["Flour"])

    def test_section_is_included_when_given(self):
        result = recipe_sync.build_new_ingredient("Butter", "1", "tbsp", "Sauce")
        self.assertEqual(result["Butter"]["section"], "Sauce")

    def test_preserved_fields_are_carried_forward(self):
        preserved = {"usda_num": "01001", "notes": ["chopped"]}
        result = recipe_sync.build_new_ingredient("Onion", "1", "sm", None, preserved)
        self.assertEqual(result["Onion"]["usda_num"], "01001")
        self.assertEqual(result["Onion"]["notes"], ["chopped"])
        self.assertNotIn("processing", result["Onion"])

    def test_preserved_amounts_and_section_keys_are_ignored(self):
        preserved = {"amounts": [{"amount": "999", "unit": "gal"}], "section": "Old Section"}
        result = recipe_sync.build_new_ingredient("Water", "1", "cup", None, preserved)
        self.assertEqual(result["Water"]["amounts"], [{"amount": "1", "unit": "cup"}])
        self.assertNotIn("section", result["Water"])


class TestParseYieldText(unittest.TestCase):
    def test_parses_amount_and_unit(self):
        self.assertEqual(
            recipe_sync.parse_yield_text("6 Servings"), {"amount": 6, "unit": "servings"}
        )

    def test_bare_number_defaults_unit_to_servings(self):
        self.assertEqual(recipe_sync.parse_yield_text("4"), {"amount": 4, "unit": "servings"})

    def test_zero_amount_treated_as_absent(self):
        self.assertIsNone(recipe_sync.parse_yield_text("0 Servings"))

    def test_no_leading_integer_returns_none(self):
        self.assertIsNone(recipe_sync.parse_yield_text("Serves a crowd"))

    def test_empty_or_none_returns_none(self):
        self.assertIsNone(recipe_sync.parse_yield_text(""))
        self.assertIsNone(recipe_sync.parse_yield_text(None))


if __name__ == "__main__":
    unittest.main()


class TestRating(unittest.TestCase):
    def test_normalize_rating_accepts_whole_numbers_one_to_five(self):
        for value, expected in [(3, 3), ("5", 5), (" 1 ", 1), (0, None), (6, None), (-1, None),
                                (None, None), ("", None), ("None", None), ("great", None),
                                (2.7, None), (True, None)]:
            self.assertEqual(recipe_sync.normalize_rating(value), expected, value)

    def test_parse_recipe_yaml_reads_rating_and_defaults_to_none(self):
        base = "recipe_name: R\nsteps: [{step: cook}]\ningredients: [{Salt: {amounts: [{amount: '1', unit: tsp}]}}]\n"
        self.assertEqual(recipe_sync.parse_recipe_yaml(base + "rating: 4\n")["rating"], 4)
        self.assertIsNone(recipe_sync.parse_recipe_yaml(base)["rating"])

    def test_sync_writes_the_rating_column(self):
        with tempfile.TemporaryDirectory() as tmp:
            recipes_dir = Path(tmp) / "recipes"; recipes_dir.mkdir()
            (recipes_dir / "r.yaml").write_text(
                "recipe_name: R\nrating: 2\nsteps: [{step: cook}]\n"
                "ingredients: [{Salt: {amounts: [{amount: '1', unit: tsp}]}}]\n", encoding="utf-8")
            db_path = Path(tmp) / "m.db"
            recipe_sync.sync_recipes(recipes_dir, db_path)
            conn = db.get_connection(db_path)
            try:
                self.assertEqual(conn.execute("SELECT rating FROM recipe").fetchone()["rating"], 2)
            finally:
                conn.close()
