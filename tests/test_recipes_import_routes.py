import io
import json
import re
import shutil
import tempfile
import unittest

import yaml
from pathlib import Path
from unittest.mock import patch

from PIL import Image

import app as app_module
import db
import recipe_sync

FIXTURES = Path(__file__).parent / "fixtures"
MEALMASTER_FIXTURES = FIXTURES / "mealmaster"


class ImportRouteTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp_dir = tempfile.mkdtemp()
        self.recipes_dir = Path(self.tmp_dir) / "recipes"
        self.recipes_dir.mkdir()
        self.db_path = Path(self.tmp_dir) / "mealplanner.db"
        recipe_sync.sync_recipes(self.recipes_dir, self.db_path)
        self.images_dir = Path(self.tmp_dir) / "recipe-images"

        self.app = app_module.create_app(self.recipes_dir, self.db_path, self.images_dir)
        self.app.config["TESTING"] = True
        self.client = self.app.test_client()

    def tearDown(self):
        shutil.rmtree(self.tmp_dir, ignore_errors=True)

    def _recipe_names(self):
        conn = db.get_connection(self.db_path)
        try:
            return {row["name"] for row in conn.execute("SELECT name FROM recipe").fetchall()}
        finally:
            conn.close()

    def _upload_and_confirm(self, filename, raw_bytes, category_overrides=None):
        """Upload a file, follow the redirect to the review screen, extract
        its temp_ids, then POST confirm (optionally overriding category/
        subcategory per temp_id — a dict of temp_id -> (category, subcategory)),
        returning the final (redirected) response."""
        self.client.post(
            "/recipes/import",
            data={"recipe_file": (io.BytesIO(raw_bytes), filename)},
            content_type="multipart/form-data",
        )
        review = self.client.get("/recipes/import/review")
        body = review.get_data(as_text=True)
        temp_ids = re.findall(r'name="category_(\w+)"', body)
        overrides = category_overrides or {}
        confirm_data = {}
        for temp_id in temp_ids:
            cat, sub = overrides.get(temp_id, ("", ""))
            confirm_data[f"category_{temp_id}"] = cat
            confirm_data[f"subcategory_{temp_id}"] = sub
        return self.client.post(
            "/recipes/import/confirm", data=confirm_data, follow_redirects=True
        )


class TestEmptySubmission(ImportRouteTestCase):
    def test_no_file_chosen_flashes_message_and_redirects(self):
        response = self.client.post("/recipes/import", data={}, follow_redirects=True)
        self.assertEqual(response.status_code, 200)
        self.assertIn("Please choose a file", response.get_data(as_text=True))


class TestYamlUpload(ImportRouteTestCase):
    def test_uploaded_yaml_is_written_and_synced(self):
        raw = (FIXTURES / "banana-bread.yaml").read_bytes()
        response = self._upload_and_confirm("banana-bread.yaml", raw)
        self.assertEqual(response.status_code, 200)
        self.assertIn("Imported 1 recipe(s) from banana-bread.yaml", response.get_data(as_text=True))
        self.assertTrue((self.recipes_dir / "banana-bread.yaml").exists())
        self.assertIn("Banana Bread", self._recipe_names())

    def test_reuploading_same_recipe_name_is_ignored_as_duplicate(self):
        raw = (FIXTURES / "banana-bread.yaml").read_bytes()
        self._upload_and_confirm("banana-bread.yaml", raw)
        original_text = (self.recipes_dir / "banana-bread.yaml").read_text(encoding="utf-8")

        # Same recipe name, different content: without a new name it is a
        # duplicate and must be ignored -- never silently overwrite the
        # library's copy.
        changed = raw.replace(b"steps:", b"notes: changed upstream\nsteps:")
        response = self._upload_and_confirm("banana-bread.yaml", changed)

        body = response.get_data(as_text=True)
        self.assertIn("Imported 0 recipe(s)", body)
        self.assertIn("ignored 1 duplicate(s) already in your library: Banana Bread", body)
        self.assertEqual(len(list(self.recipes_dir.glob("*.yaml"))), 1)
        self.assertEqual(
            (self.recipes_dir / "banana-bread.yaml").read_text(encoding="utf-8"), original_text
        )

    def test_malformed_yaml_reports_error_instead_of_false_success(self):
        raw = b"steps: []\ningredients: []\n"  # missing recipe_name

        response = self.client.post(
            "/recipes/import",
            data={"recipe_file": (io.BytesIO(raw), "broken.yaml")},
            content_type="multipart/form-data",
            follow_redirects=True,
        )

        body = response.get_data(as_text=True)
        self.assertIn("broken.yaml could not be imported", body)
        self.assertNotIn("Imported broken.yaml", body)
        self.assertEqual(self._recipe_names(), set())
        self.assertFalse((self.recipes_dir / "broken.yaml").exists())

    def test_malformed_yaml_does_not_clobber_existing_good_file_at_same_name(self):
        good_raw = (FIXTURES / "banana-bread.yaml").read_bytes()
        self._upload_and_confirm("banana-bread.yaml", good_raw)
        self.assertIn("Banana Bread", self._recipe_names())
        good_on_disk = (self.recipes_dir / "banana-bread.yaml").read_bytes()

        broken_raw = b"steps: []\ningredients: []\n"  # missing recipe_name
        response = self.client.post(
            "/recipes/import",
            data={"recipe_file": (io.BytesIO(broken_raw), "banana-bread.yaml")},
            content_type="multipart/form-data",
            follow_redirects=True,
        )

        body = response.get_data(as_text=True)
        self.assertIn("banana-bread.yaml could not be imported", body)
        self.assertEqual(
            (self.recipes_dir / "banana-bread.yaml").read_bytes(), good_on_disk
        )
        self.assertIn("Banana Bread", self._recipe_names())

    def test_uploaded_yml_extension_is_indexed(self):
        raw = (FIXTURES / "banana-bread.yaml").read_bytes()
        response = self._upload_and_confirm("banana-bread.yml", raw)

        self.assertEqual(response.status_code, 200)
        body = response.get_data(as_text=True)
        self.assertIn("Imported 1 recipe(s) from banana-bread.yml", body)
        self.assertTrue((self.recipes_dir / "banana-bread.yaml").exists())
        self.assertFalse((self.recipes_dir / "banana-bread.yml").exists())
        self.assertIn("Banana Bread", self._recipe_names())

    def test_uploaded_uppercase_yaml_extension_is_indexed(self):
        raw = (FIXTURES / "banana-bread.yaml").read_bytes()
        response = self._upload_and_confirm("banana-bread.YAML", raw)

        self.assertEqual(response.status_code, 200)
        self.assertTrue((self.recipes_dir / "banana-bread.yaml").exists())
        self.assertIn("Banana Bread", self._recipe_names())

    def test_path_traversal_filename_is_confined_to_recipes_dir(self):
        raw = (FIXTURES / "banana-bread.yaml").read_bytes()
        response = self._upload_and_confirm("../../evil.yaml", raw)

        self.assertEqual(response.status_code, 200)
        written = list(self.recipes_dir.glob("*.yaml"))
        self.assertEqual([p.name for p in written], ["evil.yaml"])
        outside = self.recipes_dir.parent / "evil.yaml"
        self.assertFalse(outside.exists())
        self.assertIn("Banana Bread", self._recipe_names())

    def test_review_screen_prefills_category_from_uploaded_yaml(self):
        raw = (
            b"recipe_name: Test Recipe\nsteps: [{step: cook}]\ningredients: [{X: {}}]\n"
            b"category: Main Dishes\nsubcategory: Beef\n"
        )
        self.client.post(
            "/recipes/import",
            data={"recipe_file": (io.BytesIO(raw), "test.yaml")},
            content_type="multipart/form-data",
        )
        response = self.client.get("/recipes/import/review")
        body = response.get_data(as_text=True)
        self.assertIn("Test Recipe", body)
        self.assertIn('value="Main Dishes"', body)
        self.assertIn('value="Beef"', body)

    def test_confirm_applies_edited_category_not_the_suggestion(self):
        raw = (
            b"recipe_name: Test Recipe\nsteps: [{step: cook}]\ningredients: [{X: {}}]\n"
            b"category: Main Dishes\nsubcategory: Beef\n"
        )
        self.client.post(
            "/recipes/import",
            data={"recipe_file": (io.BytesIO(raw), "test.yaml")},
            content_type="multipart/form-data",
        )
        review = self.client.get("/recipes/import/review")
        temp_id = re.findall(r'name="category_(\w+)"', review.get_data(as_text=True))[0]

        self.client.post(
            "/recipes/import/confirm",
            data={f"category_{temp_id}": "Desserts", f"subcategory_{temp_id}": ""},
            follow_redirects=True,
        )

        conn = db.get_connection(self.db_path)
        try:
            row = conn.execute(
                "SELECT category, subcategory FROM recipe WHERE name = 'Test Recipe'"
            ).fetchone()
        finally:
            conn.close()
        self.assertEqual(row["category"], "Desserts")
        self.assertIsNone(row["subcategory"])

    def test_review_screen_prefills_servings_from_uploaded_yaml(self):
        raw = (FIXTURES / "banana-bread.yaml").read_bytes()  # yields: 3 loaves
        self.client.post(
            "/recipes/import",
            data={"recipe_file": (io.BytesIO(raw), "banana-bread.yaml")},
            content_type="multipart/form-data",
        )

        response = self.client.get("/recipes/import/review")

        body = response.get_data(as_text=True)
        self.assertIn('name="servings_amount_0" value="3"', body)
        self.assertIn('name="servings_unit_0" value="loaves"', body)

    def test_review_screen_blank_servings_when_yaml_has_no_yields(self):
        raw = b"recipe_name: Test Recipe\nsteps: [{step: cook}]\ningredients: [{X: {}}]\n"
        self.client.post(
            "/recipes/import",
            data={"recipe_file": (io.BytesIO(raw), "test.yaml")},
            content_type="multipart/form-data",
        )

        response = self.client.get("/recipes/import/review")

        body = response.get_data(as_text=True)
        self.assertIn('name="servings_amount_0" value=""', body)
        self.assertIn('name="servings_unit_0" value="servings"', body)

    def test_confirm_saves_edited_servings_to_db_and_on_disk_file(self):
        raw = b"recipe_name: Test Recipe\nsteps: [{step: cook}]\ningredients: [{X: {}}]\n"
        self.client.post(
            "/recipes/import",
            data={"recipe_file": (io.BytesIO(raw), "test.yaml")},
            content_type="multipart/form-data",
        )
        review = self.client.get("/recipes/import/review")
        temp_id = re.findall(r'name="category_(\w+)"', review.get_data(as_text=True))[0]

        self.client.post(
            "/recipes/import/confirm",
            data={
                f"category_{temp_id}": "", f"subcategory_{temp_id}": "",
                f"servings_amount_{temp_id}": "6", f"servings_unit_{temp_id}": "muffins",
            },
            follow_redirects=True,
        )

        conn = db.get_connection(self.db_path)
        try:
            row = conn.execute(
                "SELECT yields_json FROM recipe WHERE name = 'Test Recipe'"
            ).fetchone()
        finally:
            conn.close()
        self.assertEqual(json.loads(row["yields_json"]), [{"amount": 6, "unit": "muffins"}])
        on_disk = (self.recipes_dir / "test.yaml").read_text(encoding="utf-8")
        self.assertIn("amount: 6", on_disk)
        self.assertIn("unit: muffins", on_disk)

    def test_confirm_leaves_yields_unset_when_servings_left_blank(self):
        raw = b"recipe_name: Test Recipe\nsteps: [{step: cook}]\ningredients: [{X: {}}]\n"
        self.client.post(
            "/recipes/import",
            data={"recipe_file": (io.BytesIO(raw), "test.yaml")},
            content_type="multipart/form-data",
        )
        review = self.client.get("/recipes/import/review")
        temp_id = re.findall(r'name="category_(\w+)"', review.get_data(as_text=True))[0]

        self.client.post(
            "/recipes/import/confirm",
            data={f"category_{temp_id}": "", f"subcategory_{temp_id}": ""},
            follow_redirects=True,
        )

        conn = db.get_connection(self.db_path)
        try:
            row = conn.execute(
                "SELECT yields_json FROM recipe WHERE name = 'Test Recipe'"
            ).fetchone()
        finally:
            conn.close()
        self.assertIsNone(row["yields_json"])

    def test_review_screen_shows_the_whole_recipe_and_flags_unparseable_amounts(self):
        raw = (
            b"recipe_name: Test Recipe\nsteps: [{step: cook it}, {step: eat it}]\n"
            b"ingredients: [{Wine: {amounts: [{amount: '750    750', unit: ml}]}},"
            b" {Salt: {amounts: [{amount: '1', unit: tsp}]}}]\n"
        )
        self.client.post(
            "/recipes/import",
            data={"recipe_file": (io.BytesIO(raw), "test.yaml")},
            content_type="multipart/form-data",
        )

        body = self.client.get("/recipes/import/review").get_data(as_text=True)

        # Title is editable for a new recipe too.
        self.assertIn('name="title_0" class="import-title" value="Test Recipe"', body)
        # Every ingredient is an editable row, namespaced to this recipe.
        self.assertIn('name="r0_row_name_e0" value="Wine"', body)
        self.assertIn('name="r0_row_amount_e0" value="750    750"', body)
        self.assertIn('name="r0_row_unit_e0" value="ml"', body)
        self.assertIn('name="r0_row_name_e1" value="Salt"', body)
        self.assertIn('name="r0_row_order"', body)
        # Only the unparseable one is shaded as needing input.
        self.assertEqual(body.count("ing-row--ingredient ing-row--needs-input"), 1)
        self.assertIn('name="r0_row_amount_e0" value="750    750" placeholder="Amount" aria-invalid="true"', body)
        self.assertIn('<span id="needs-input-total">1</span>', body)
        # Each row can be switched between ingredient and sub-recipe.
        self.assertIn('name="r0_row_kind_e0"', body)
        self.assertIn('<option value="section">Sub-recipe</option>', body)
        # Steps are shown so the whole recipe is visible.
        self.assertIn("<li>cook it</li>", body)
        self.assertIn("<li>eat it</li>", body)

    def test_unit_correction_field_offers_a_suggestion_list(self):
        raw = (
            b"recipe_name: Test Recipe\nsteps: [{step: cook}]\n"
            b"ingredients: [{Wine: {amounts: [{amount: '750    750', unit: ml}]}}]\n"
        )
        self.client.post(
            "/recipes/import",
            data={"recipe_file": (io.BytesIO(raw), "test.yaml")},
            content_type="multipart/form-data",
        )

        response = self.client.get("/recipes/import/review")

        body = response.get_data(as_text=True)
        self.assertIn('list="unit-suggestions"', body)
        self.assertIn('<datalist id="unit-suggestions">', body)
        self.assertIn('<option value="tsp">', body)

    def test_confirming_an_amount_fix_converts_and_stores_it(self):
        raw = (
            b"recipe_name: Test Recipe\nsteps: [{step: cook}]\n"
            b"ingredients: [{Wine: {amounts: [{amount: '750    750', unit: ml}]}}]\n"
        )
        self.client.post(
            "/recipes/import",
            data={"recipe_file": (io.BytesIO(raw), "test.yaml")},
            content_type="multipart/form-data",
        )

        self.client.post(
            "/recipes/import/confirm",
            data={
                "category_0": "", "subcategory_0": "",
                "r0_row_order": "e0", "r0_row_kind_e0": "ingredient",
                "r0_row_name_e0": "Wine", "r0_row_amount_e0": "480", "r0_row_unit_e0": "ml",
            },
            follow_redirects=True,
        )

        conn = db.get_connection(self.db_path)
        try:
            ingredient = conn.execute(
                "SELECT amount, unit FROM recipe_ingredient WHERE name = 'Wine'"
            ).fetchone()
        finally:
            conn.close()
        self.assertEqual(ingredient["amount"], "1")
        self.assertEqual(ingredient["unit"], "pt")

    def test_leaving_the_amount_fix_field_unchanged_keeps_it_broken(self):
        raw = (
            b"recipe_name: Test Recipe\nsteps: [{step: cook}]\n"
            b"ingredients: [{Wine: {amounts: [{amount: '750    750', unit: ml}]}}]\n"
        )
        self.client.post(
            "/recipes/import",
            data={"recipe_file": (io.BytesIO(raw), "test.yaml")},
            content_type="multipart/form-data",
        )

        self.client.post(
            "/recipes/import/confirm",
            data={
                "category_0": "", "subcategory_0": "",
                "r0_row_order": "e0", "r0_row_kind_e0": "ingredient",
                "r0_row_name_e0": "Wine", "r0_row_amount_e0": "750    750", "r0_row_unit_e0": "ml",
            },
            follow_redirects=True,
        )

        conn = db.get_connection(self.db_path)
        try:
            ingredient = conn.execute(
                "SELECT amount, unit FROM recipe_ingredient WHERE name = 'Wine'"
            ).fetchone()
        finally:
            conn.close()
        self.assertEqual(ingredient["amount"], "750    750")
        self.assertEqual(ingredient["unit"], "ml")


class TestUnsupportedExtension(ImportRouteTestCase):
    def test_unsupported_extension_is_rejected(self):
        response = self.client.post(
            "/recipes/import",
            data={"recipe_file": (io.BytesIO(b"hello"), "notes.txt")},
            content_type="multipart/form-data",
            follow_redirects=True,
        )
        self.assertIn("Unsupported file type", response.get_data(as_text=True))
        self.assertEqual(self._recipe_names(), set())


class TestMmfUpload(ImportRouteTestCase):
    def test_mmf_upload_imports_all_recipes_from_two_recipe_file(self):
        raw = (MEALMASTER_FIXTURES / "baked-ziti-only.mmf").read_bytes()
        raw += b"\n" + (MEALMASTER_FIXTURES / "rice-croquettes-only.mmf").read_bytes()

        response = self._upload_and_confirm("two-recipes.mmf", raw)

        self.assertEqual(response.status_code, 200)
        body = response.get_data(as_text=True)
        self.assertIn("Imported 2 recipe(s)", body)
        self.assertEqual(
            self._recipe_names(),
            {"Baked Ziti", "Rice Croquettes with Ham and Mozzarella"},
        )

    def test_reuploading_same_mmf_ignores_already_imported_titles(self):
        raw = (MEALMASTER_FIXTURES / "baked-ziti-only.mmf").read_bytes()
        self._upload_and_confirm("one-recipe.mmf", raw)

        # Every recipe in this second upload is already in the library. It
        # still goes through review -- the user may want to rename one --
        # but confirming without a new name ignores it.
        self.client.post(
            "/recipes/import",
            data={"recipe_file": (io.BytesIO(raw), "one-recipe.mmf")},
            content_type="multipart/form-data",
        )
        review = self.client.get("/recipes/import/review").get_data(as_text=True)
        self.assertIn("0</span> to import", review)
        self.assertIn('<span id="ignored-count">1</span>', review)
        self.assertIn("No new recipes in this file", review)
        self.assertIn('name="title_0"', review)

        response = self.client.post("/recipes/import/confirm", data={}, follow_redirects=True)
        body = response.get_data(as_text=True)
        self.assertIn("Imported 0 recipe(s)", body)
        self.assertIn("ignored 1 duplicate(s) already in your library: Baked Ziti", body)
        self.assertEqual(len(list(self.recipes_dir.glob("*.yaml"))), 1)

    def test_review_screen_prefills_servings_from_mmf_tail_yield(self):
        raw = (MEALMASTER_FIXTURES / "baked-ziti-only.mmf").read_bytes()  # tail Yield: 6 Servings
        self.client.post(
            "/recipes/import",
            data={"recipe_file": (io.BytesIO(raw), "one-recipe.mmf")},
            content_type="multipart/form-data",
        )

        response = self.client.get("/recipes/import/review")

        body = response.get_data(as_text=True)
        self.assertIn('name="servings_amount_0" value="6"', body)
        self.assertIn('name="servings_unit_0" value="servings"', body)

    def test_unparseable_recipe_is_reported_and_does_not_block_the_rest(self):
        raw = (MEALMASTER_FIXTURES / "missing-title.mmf").read_bytes()
        raw += b"\n" + (MEALMASTER_FIXTURES / "baked-ziti-only.mmf").read_bytes()

        response = self._upload_and_confirm("mixed.mmf", raw)

        body = response.get_data(as_text=True)
        self.assertIn("Imported 1 recipe(s)", body)
        self.assertIn("1 failed to parse", body)
        self.assertIn("Baked Ziti", self._recipe_names())

    def test_invalid_cp1252_byte_does_not_crash_the_request(self):
        raw = (MEALMASTER_FIXTURES / "baked-ziti-only.mmf").read_bytes()
        # 0x81 is undefined in CP1252 and raises UnicodeDecodeError under a
        # strict decode. Splice it into the notes line so it's part of the
        # payload without disturbing the recipe's parseable structure.
        raw = raw.replace(b"Easy to make", b"Easy\x81 to make")

        response = self.client.post(
            "/recipes/import",
            data={"recipe_file": (io.BytesIO(raw), "bad-byte.mmf")},
            content_type="multipart/form-data",
            follow_redirects=True,
        )

        # Must complete gracefully and reach the review screen, not blow up
        # with a 500.
        self.assertEqual(response.status_code, 200)
        self.assertIn("Review Import", response.get_data(as_text=True))

    def test_duplicate_title_within_same_mmf_upload_is_deduped(self):
        single = (MEALMASTER_FIXTURES / "baked-ziti-only.mmf").read_bytes()
        raw = single + b"\n" + single

        response = self._upload_and_confirm("duplicated.mmf", raw)

        body = response.get_data(as_text=True)
        self.assertIn("Imported 1 recipe(s)", body)
        self.assertIn("ignored 1 duplicate(s)", body)
        conn = db.get_connection(self.db_path)
        try:
            count = conn.execute(
                "SELECT COUNT(*) FROM recipe WHERE name = ?", ("Baked Ziti",)
            ).fetchone()[0]
        finally:
            conn.close()
        self.assertEqual(count, 1)
        self.assertEqual(len(list(self.recipes_dir.glob("*.yaml"))), 1)

    def test_review_prefills_subcategory_from_raw_mmf_category(self):
        raw = (MEALMASTER_FIXTURES / "baked-ziti-only.mmf").read_bytes()  # Categories: Pasta
        self.client.post(
            "/recipes/import",
            data={"recipe_file": (io.BytesIO(raw), "one-recipe.mmf")},
            content_type="multipart/form-data",
        )

        response = self.client.get("/recipes/import/review")

        body = response.get_data(as_text=True)
        self.assertIn("Baked Ziti", body)
        self.assertIn('value="Pasta"', body)

    def test_intra_batch_filename_collision_keeps_both_recipes(self):
        # "Beef Stew!" and "Beef Stew?" both slugify to "beef-stew" (the
        # slugify regex replaces every run of non-alphanumeric characters
        # with a single hyphen and strips leading/trailing hyphens), so
        # naively assigning both the same dest_filename during staging would
        # cause the second write to silently overwrite the first at confirm
        # time. Both recipes must survive as distinct files/rows.
        raw = (
            b"MMMMM----- Recipe via Meal-Master\n\n"
            b"      Title: Beef Stew!\n"
            b" Categories: None\n\n"
            b"      1 Beef -- cubed\n\n"
            b"Brown the beef.\n\n"
            b"MMMMM\n\n"
            b"MMMMM----- Recipe via Meal-Master\n\n"
            b"      Title: Beef Stew?\n"
            b" Categories: None\n\n"
            b"      1 Beef -- cubed\n\n"
            b"Simmer the beef.\n\n"
            b"MMMMM\n"
        )

        response = self._upload_and_confirm("collision.mmf", raw)

        self.assertEqual(response.status_code, 200)
        body = response.get_data(as_text=True)
        self.assertIn("Imported 2 recipe(s)", body)
        self.assertEqual(
            self._recipe_names(), {"Beef Stew!", "Beef Stew?"}
        )
        written = sorted(p.name for p in self.recipes_dir.glob("*.yaml"))
        self.assertEqual(written, ["beef-stew-2.yaml", "beef-stew.yaml"])

    def test_confirm_applies_distinct_category_overrides_per_recipe(self):
        # The staging loop assigns temp_id = str(i) using the index of each
        # recipe as parsed from the .mmf file (see recipes_import in app.py),
        # so with neither recipe skipped, temp_id "0" is always the first
        # recipe in the file (Baked Ziti) and "1" is always the second (Rice
        # Croquettes) — deterministic regardless of upload order elsewhere.
        raw = (MEALMASTER_FIXTURES / "baked-ziti-only.mmf").read_bytes()
        raw += b"\n" + (MEALMASTER_FIXTURES / "rice-croquettes-only.mmf").read_bytes()

        self._upload_and_confirm(
            "two-recipes.mmf",
            raw,
            category_overrides={
                "0": ("Main Dishes", "Beef"),
                "1": ("Side Dishes", "Vegetarian"),
            },
        )

        conn = db.get_connection(self.db_path)
        try:
            ziti = conn.execute(
                "SELECT category, subcategory FROM recipe WHERE name = 'Baked Ziti'"
            ).fetchone()
            rice = conn.execute(
                "SELECT category, subcategory FROM recipe WHERE name = "
                "'Rice Croquettes with Ham and Mozzarella'"
            ).fetchone()
        finally:
            conn.close()

        self.assertEqual(ziti["category"], "Main Dishes")
        self.assertEqual(ziti["subcategory"], "Beef")
        self.assertEqual(rice["category"], "Side Dishes")
        self.assertEqual(rice["subcategory"], "Vegetarian")

    def test_review_screen_shows_only_new_recipes_as_editable_rows(self):
        # Pre-import Rice Croquettes so the next upload's copy is a duplicate.
        self._upload_and_confirm(
            "rice-croquettes-only.mmf",
            (MEALMASTER_FIXTURES / "rice-croquettes-only.mmf").read_bytes(),
        )

        raw = (MEALMASTER_FIXTURES / "missing-title.mmf").read_bytes()  # error
        raw += b"\n" + (MEALMASTER_FIXTURES / "rice-croquettes-only.mmf").read_bytes()  # duplicate
        raw += b"\n" + (MEALMASTER_FIXTURES / "baked-ziti-only.mmf").read_bytes()  # new

        self.client.post(
            "/recipes/import",
            data={"recipe_file": (io.BytesIO(raw), "mixed-batch.mmf")},
            content_type="multipart/form-data",
        )

        response = self.client.get("/recipes/import/review")
        body = response.get_data(as_text=True)

        # The genuinely new recipe gets an editable row with no rename field.
        self.assertIn("Baked Ziti", body)
        # (Unparseable recipes don't take a temp_id: duplicate is 0, new is 1.)
        self.assertIn('name="category_1"', body)
        self.assertIn('name="title_1" class="import-title" value="Baked Ziti"', body)
        # The duplicate lands in the "Already in your library" section as an
        # ignored card, imported only if the user changes its title.
        self.assertIn("Rice Croquettes with Ham and Mozzarella", body)
        self.assertIn("Already in your library", body)
        self.assertIn('<section class="import-card import-card--duplicate" data-temp-id="0" data-status="ignored">', body)
        self.assertIn('<section class="import-card" data-temp-id="1" data-status="new">', body)
        self.assertIn("1</span> to import", body)
        self.assertIn('<span id="ignored-count">1</span>', body)
        # The unparseable recipe is surfaced as a parse-error notice.
        self.assertIn("failed to parse", body)

    def test_submitting_the_unchanged_title_still_ignores_the_duplicate(self):
        raw = (MEALMASTER_FIXTURES / "baked-ziti-only.mmf").read_bytes()
        self._upload_and_confirm("one-recipe.mmf", raw)

        for submitted in ("Baked Ziti", "  baked ZITI  ", ""):
            self.client.post(
                "/recipes/import",
                data={"recipe_file": (io.BytesIO(raw), "one-recipe.mmf")},
                content_type="multipart/form-data",
            )
            body = self.client.post(
                "/recipes/import/confirm", data={"title_0": submitted}, follow_redirects=True
            ).get_data(as_text=True)
            self.assertIn("ignored 1 duplicate(s)", body, submitted)
            self.assertEqual(len(list(self.recipes_dir.glob("*.yaml"))), 1, submitted)

    def test_duplicate_title_field_is_prefilled_with_the_existing_name(self):
        raw = (MEALMASTER_FIXTURES / "baked-ziti-only.mmf").read_bytes()
        self._upload_and_confirm("one-recipe.mmf", raw)
        self.client.post(
            "/recipes/import",
            data={"recipe_file": (io.BytesIO(raw), "one-recipe.mmf")},
            content_type="multipart/form-data",
        )
        body = self.client.get("/recipes/import/review").get_data(as_text=True)
        self.assertIn(
            'name="title_0" class="import-title" value="Baked Ziti" data-original="Baked Ziti"', body
        )
        self.assertNotIn("Import as", body)

    def test_new_recipe_title_can_be_changed_on_import(self):
        raw = (MEALMASTER_FIXTURES / "baked-ziti-only.mmf").read_bytes()
        self.client.post(
            "/recipes/import",
            data={"recipe_file": (io.BytesIO(raw), "one.mmf")},
            content_type="multipart/form-data",
        )
        body = self.client.post(
            "/recipes/import/confirm",
            data={"title_0": "Nonna's Baked Ziti", "category_0": "Main Dishes"},
            follow_redirects=True,
        ).get_data(as_text=True)

        self.assertIn("Imported 1 recipe(s)", body)
        self.assertEqual(self._recipe_names(), {"Nonna's Baked Ziti"})
        files = [p.name for p in self.recipes_dir.glob("*.yaml")]
        self.assertEqual(files, ["nonna-s-baked-ziti.yaml"])

    def test_new_recipe_renamed_onto_an_existing_name_is_rejected(self):
        self._upload_and_confirm(
            "rice.mmf", (MEALMASTER_FIXTURES / "rice-croquettes-only.mmf").read_bytes()
        )
        self.client.post(
            "/recipes/import",
            data={"recipe_file": (io.BytesIO((MEALMASTER_FIXTURES / "baked-ziti-only.mmf").read_bytes()), "z.mmf")},
            content_type="multipart/form-data",
        )
        body = self.client.post(
            "/recipes/import/confirm",
            data={"title_0": "Rice Croquettes with Ham and Mozzarella"},
            follow_redirects=True,
        ).get_data(as_text=True)
        self.assertIn("that name is already in your library", body)
        self.assertEqual(len(list(self.recipes_dir.glob("*.yaml"))), 1)

    def test_ingredients_edited_on_import_are_what_gets_written(self):
        raw = (
            b"recipe_name: Test Recipe\nsteps: [{step: cook}]\n"
            b"ingredients: [{Wine: {amounts: [{amount: '1', unit: cup}]}},"
            b" {Salt: {amounts: [{amount: '1', unit: tsp}]}}]\n"
        )
        self.client.post(
            "/recipes/import",
            data={"recipe_file": (io.BytesIO(raw), "test.yaml")},
            content_type="multipart/form-data",
        )
        # Rename Wine -> Red Wine, drop Salt, add a sub-recipe heading with a
        # new ingredient under it, and reorder so the heading comes first.
        self.client.post(
            "/recipes/import/confirm",
            data={
                "category_0": "", "subcategory_0": "",
                "r0_row_order": "n0,n1,e0",
                "r0_row_kind_n0": "section", "r0_row_name_n0": "Sauce",
                "r0_row_kind_n1": "ingredient", "r0_row_name_n1": "Butter",
                "r0_row_amount_n1": "2", "r0_row_unit_n1": "tbsp",
                "r0_row_kind_e0": "ingredient", "r0_row_name_e0": "Red Wine",
                "r0_row_amount_e0": "1/2", "r0_row_unit_e0": "cup",
            },
            follow_redirects=True,
        )

        on_disk = yaml.safe_load(
            (self.recipes_dir / "test.yaml").read_text(encoding="utf-8")
        )
        self.assertEqual(on_disk["ingredients"], [
            {"Butter": {"amounts": [{"amount": "2", "unit": "tbsp"}], "section": "Sauce"}},
            {"Red Wine": {"amounts": [{"amount": "1/2", "unit": "cup"}], "section": "Sauce"}},
        ])
        conn = db.get_connection(self.db_path)
        try:
            names = [r["name"] for r in conn.execute(
                "SELECT name FROM recipe_ingredient ORDER BY id"
            ).fetchall()]
        finally:
            conn.close()
        self.assertEqual(names, ["Butter", "Red Wine"])

    def test_confirm_without_editor_fields_keeps_the_original_ingredients(self):
        raw = (
            b"recipe_name: Test Recipe\nsteps: [{step: cook}]\n"
            b"ingredients: [{Wine: {amounts: [{amount: '1', unit: cup}]}}]\n"
        )
        self.client.post(
            "/recipes/import",
            data={"recipe_file": (io.BytesIO(raw), "test.yaml")},
            content_type="multipart/form-data",
        )
        self.client.post("/recipes/import/confirm", data={"category_0": "Drinks"}, follow_redirects=True)
        on_disk = (self.recipes_dir / "test.yaml").read_text(encoding="utf-8")
        self.assertIn("Wine", on_disk)
        self.assertIn("category: Drinks", on_disk)

    def test_removing_every_ingredient_on_import_is_rejected(self):
        raw = (
            b"recipe_name: Test Recipe\nsteps: [{step: cook}]\n"
            b"ingredients: [{Wine: {amounts: [{amount: '1', unit: cup}]}}]\n"
        )
        self.client.post(
            "/recipes/import",
            data={"recipe_file": (io.BytesIO(raw), "test.yaml")},
            content_type="multipart/form-data",
        )
        body = self.client.post(
            "/recipes/import/confirm", data={"r0_row_order": ""}, follow_redirects=True
        ).get_data(as_text=True)
        self.assertIn("needs at least one ingredient", body)
        self.assertEqual(list(self.recipes_dir.glob("*.yaml")), [])
        # Staging survives so the user can fix it.
        self.assertIn('name="title_0"', body)

    def test_renaming_a_duplicate_imports_it_under_the_new_name(self):
        raw = (MEALMASTER_FIXTURES / "baked-ziti-only.mmf").read_bytes()
        self._upload_and_confirm("one-recipe.mmf", raw)

        self.client.post(
            "/recipes/import",
            data={"recipe_file": (io.BytesIO(raw), "one-recipe.mmf")},
            content_type="multipart/form-data",
        )
        response = self.client.post(
            "/recipes/import/confirm",
            data={"title_0": "Baked Ziti (Grandma's)", "category_0": "Main Dishes"},
            follow_redirects=True,
        )

        body = response.get_data(as_text=True)
        self.assertIn("Imported 1 recipe(s)", body)
        self.assertNotIn("ignored", body)
        self.assertEqual(self._recipe_names(), {"Baked Ziti", "Baked Ziti (Grandma's)"})
        files = sorted(p.name for p in self.recipes_dir.glob("*.yaml"))
        self.assertEqual(files, ["baked-ziti-grandma-s.yaml", "baked-ziti.yaml"])
        renamed = (self.recipes_dir / "baked-ziti-grandma-s.yaml").read_text(encoding="utf-8")
        self.assertIn("recipe_name: Baked Ziti (Grandma's)", renamed)
        self.assertIn("category: Main Dishes", renamed)
        conn = db.get_connection(self.db_path)
        try:
            uuids = {
                row["recipe_uuid"] for row in conn.execute("SELECT recipe_uuid FROM recipe")
            }
        finally:
            conn.close()
        self.assertEqual(len(uuids), 2, "renamed copy must get its own uuid")

    def test_renaming_a_duplicate_to_another_existing_name_is_rejected(self):
        raw = (MEALMASTER_FIXTURES / "baked-ziti-only.mmf").read_bytes()
        raw += b"\n" + (MEALMASTER_FIXTURES / "rice-croquettes-only.mmf").read_bytes()
        self._upload_and_confirm("two.mmf", raw)

        single = (MEALMASTER_FIXTURES / "baked-ziti-only.mmf").read_bytes()
        self.client.post(
            "/recipes/import",
            data={"recipe_file": (io.BytesIO(single), "again.mmf")},
            content_type="multipart/form-data",
        )
        response = self.client.post(
            "/recipes/import/confirm",
            data={"title_0": "rice croquettes WITH ham and mozzarella"},
            follow_redirects=True,
        )

        body = response.get_data(as_text=True)
        self.assertIn("that name is already in your library", body)
        # Nothing written, staging kept so the user can fix the name.
        self.assertEqual(len(list(self.recipes_dir.glob("*.yaml"))), 2)
        self.assertIn('name="title_0"', body)

    def test_renaming_a_duplicate_cannot_collide_with_a_new_recipe_in_the_same_batch(self):
        self._upload_and_confirm(
            "ziti.mmf", (MEALMASTER_FIXTURES / "baked-ziti-only.mmf").read_bytes()
        )
        raw = (MEALMASTER_FIXTURES / "baked-ziti-only.mmf").read_bytes()  # duplicate, temp_id 0
        raw += b"\n" + (MEALMASTER_FIXTURES / "rice-croquettes-only.mmf").read_bytes()  # new, 1
        self.client.post(
            "/recipes/import",
            data={"recipe_file": (io.BytesIO(raw), "batch.mmf")},
            content_type="multipart/form-data",
        )
        response = self.client.post(
            "/recipes/import/confirm",
            data={"title_0": "Rice Croquettes with Ham and Mozzarella"},
            follow_redirects=True,
        )
        self.assertIn("that name is already in your library", response.get_data(as_text=True))
        self.assertEqual(len(list(self.recipes_dir.glob("*.yaml"))), 1)

    def test_review_confirm_button_asks_before_continuing(self):
        raw = (MEALMASTER_FIXTURES / "baked-ziti-only.mmf").read_bytes()
        self.client.post(
            "/recipes/import",
            data={"recipe_file": (io.BytesIO(raw), "one.mmf")},
            content_type="multipart/form-data",
        )
        body = self.client.get("/recipes/import/review").get_data(as_text=True)
        self.assertIn("window.confirm(", body)
        self.assertIn("Continue?", body)


class TestImportCancel(ImportRouteTestCase):
    def test_cancel_discards_staging_and_writes_nothing(self):
        raw = (FIXTURES / "banana-bread.yaml").read_bytes()
        self.client.post(
            "/recipes/import",
            data={"recipe_file": (io.BytesIO(raw), "banana-bread.yaml")},
            content_type="multipart/form-data",
        )

        response = self.client.post("/recipes/import/cancel", follow_redirects=True)

        self.assertIn("Import cancelled", response.get_data(as_text=True))
        self.assertEqual(list(self.recipes_dir.glob("*.yaml")), [])
        self.assertEqual(self._recipe_names(), set())

    def test_cancel_with_no_pending_import_is_a_harmless_noop(self):
        response = self.client.post("/recipes/import/cancel", follow_redirects=True)
        self.assertIn("Import cancelled", response.get_data(as_text=True))


class TestDisplayValue(unittest.TestCase):
    def test_none_becomes_blank(self):
        self.assertEqual(app_module._display_value(None), "")

    def test_none_token_string_becomes_blank(self):
        self.assertEqual(app_module._display_value("None"), "")

    def test_ordinary_string_passes_through(self):
        self.assertEqual(app_module._display_value("Main Dishes"), "Main Dishes")

    def test_non_string_value_does_not_raise(self):
        # A hand-authored .yaml file could have a non-scalar value like
        # `category: [a, b]`. `value in recipe_sync.NONE_TOKENS` would raise
        # TypeError: unhashable type on a list if not guarded by isinstance.
        self.assertEqual(app_module._display_value([1, 2]), [1, 2])


class TestImportReviewGuards(ImportRouteTestCase):
    def test_review_with_no_pending_import_redirects_with_message(self):
        response = self.client.get("/recipes/import/review", follow_redirects=True)
        self.assertIn("Nothing to review", response.get_data(as_text=True))

    def test_confirm_with_no_pending_import_redirects_with_message(self):
        response = self.client.post(
            "/recipes/import/confirm", data={}, follow_redirects=True
        )
        self.assertIn("Nothing to review", response.get_data(as_text=True))

    def test_corrupted_staging_file_does_not_500_the_review_screen(self):
        staging_path = self.app.config["STAGING_PATH"]
        staging_path.write_text("{not valid json, truncated mid-write", encoding="utf-8")

        response = self.client.get("/recipes/import/review", follow_redirects=True)

        self.assertEqual(response.status_code, 200)
        self.assertIn("Nothing to review", response.get_data(as_text=True))

    def test_corrupted_staging_file_does_not_500_the_confirm_route(self):
        staging_path = self.app.config["STAGING_PATH"]
        staging_path.write_text("{not valid json, truncated mid-write", encoding="utf-8")

        response = self.client.post(
            "/recipes/import/confirm", data={}, follow_redirects=True
        )

        self.assertEqual(response.status_code, 200)
        self.assertIn("Nothing to review", response.get_data(as_text=True))


class TestCancelWithExtractedPhoto(ImportRouteTestCase):
    @patch("recipe_extraction.fetch_image_bytes")
    def test_cancelling_removes_the_downloaded_photo(self, mock_fetch):
        buf = io.BytesIO()
        Image.new("RGB", (400, 300), (10, 20, 30)).save(buf, format="JPEG")
        mock_fetch.return_value = buf.getvalue()

        self.client.post(
            "/recipes/import/extension",
            json={
                "name": "Cancelled Soup",
                "ingredients": ["2 cups flour"],
                "steps": ["Cook."],
                "image_url": "https://example.com/photo.jpg",
            },
        )
        self.assertEqual(len(list(self.images_dir.glob("*.jpg"))), 2)  # detail + thumb

        self.client.post("/recipes/import/cancel", follow_redirects=True)

        self.assertEqual(len(list(self.images_dir.glob("*.jpg"))), 0)


class TestCancelDoesNotDeleteUnrelatedRecipeImage(ImportRouteTestCase):
    def test_cancelling_a_plain_yaml_upload_leaves_its_image_field_target_alone(self):
        # A plain .yaml upload's yaml_text is the raw uploaded file content
        # verbatim -- if it happens to reference an `image:` filename that
        # already exists on disk (e.g. re-uploading a backup of an existing,
        # already-photographed recipe), cancel must NOT delete that photo.
        # Only images this staging call itself downloaded (tracked via
        # "downloaded_images") are eligible for cleanup.
        self.images_dir.mkdir(parents=True, exist_ok=True)
        existing_photo = self.images_dir / "existing-uuid.jpg"
        existing_photo.write_bytes(b"fake jpg bytes")
        existing_thumb = self.images_dir / "existing-uuid_thumb.jpg"
        existing_thumb.write_bytes(b"fake thumb bytes")

        yaml_text = (
            "recipe_name: My Recipe\n"
            "image: existing-uuid.jpg\n"
            "ingredients:\n"
            "  - Flour:\n"
            "      amounts:\n"
            "        - amount: 1\n"
            "          unit: cup\n"
            "steps:\n"
            "  - step: Mix.\n"
        )
        self.client.post(
            "/recipes/import",
            data={"recipe_file": (io.BytesIO(yaml_text.encode("utf-8")), "my-recipe.yaml")},
            content_type="multipart/form-data",
        )

        self.client.post("/recipes/import/cancel", follow_redirects=True)

        self.assertTrue(existing_photo.exists())
        self.assertTrue(existing_thumb.exists())


class TestSupersedingImportDiscardsPreviousPhoto(ImportRouteTestCase):
    @patch("recipe_extraction.fetch_image_bytes")
    def test_plain_yaml_upload_discards_a_previous_extension_staged_photo(self, mock_fetch):
        # The other direction of Finding 2: a plain .yaml upload's
        # _save_staging call must also discard whatever photo an
        # earlier, still-pending extension import downloaded -- not
        # just the extension route discarding its own predecessor.
        buf = io.BytesIO()
        Image.new("RGB", (400, 300), (10, 20, 30)).save(buf, format="JPEG")
        mock_fetch.return_value = buf.getvalue()

        self.client.post(
            "/recipes/import/extension",
            json={
                "name": "Staged Soup",
                "ingredients": ["2 cups flour"],
                "steps": ["Cook."],
                "image_url": "https://example.com/photo.jpg",
            },
        )
        self.assertEqual(len(list(self.images_dir.glob("*.jpg"))), 2)  # detail + thumb

        raw = (FIXTURES / "banana-bread.yaml").read_bytes()
        self.client.post(
            "/recipes/import",
            data={"recipe_file": (io.BytesIO(raw), "banana-bread.yaml")},
            content_type="multipart/form-data",
        )

        self.assertEqual(list(self.images_dir.glob("*.jpg")), [])


if __name__ == "__main__":
    unittest.main()
