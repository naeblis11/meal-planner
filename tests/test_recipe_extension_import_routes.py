import shutil
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import app as app_module
import db
import recipe_sync


class ExtensionImportTestCase(unittest.TestCase):
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

    def _valid_payload(self, **overrides):
        payload = {
            "name": "Extracted Soup",
            "ingredients": ["2 cups flour, sifted", "Kosher salt, to taste"],
            "steps": ["Boil water.", "Add flour."],
            "yield_text": "4 servings",
            "author": "Jane Doe",
            "source_url": "https://www.example.com/soup",
            "image_url": None,
        }
        payload.update(overrides)
        return payload


class TestValidation(ExtensionImportTestCase):
    def test_missing_name_is_rejected(self):
        response = self.client.post(
            "/recipes/import/extension", json=self._valid_payload(name="")
        )
        self.assertEqual(response.status_code, 400)
        self.assertFalse(response.get_json()["ok"])

    def test_empty_ingredients_is_rejected(self):
        response = self.client.post(
            "/recipes/import/extension", json=self._valid_payload(ingredients=[])
        )
        self.assertEqual(response.status_code, 400)

    def test_empty_steps_is_rejected(self):
        response = self.client.post(
            "/recipes/import/extension", json=self._valid_payload(steps=[])
        )
        self.assertEqual(response.status_code, 400)

    def test_rejected_payload_stages_nothing(self):
        self.client.post("/recipes/import/extension", json=self._valid_payload(name=""))
        review = self.client.get("/recipes/import/review", follow_redirects=True)
        self.assertIn("Nothing to review", review.get_data(as_text=True))


class TestSuccessfulImport(ExtensionImportTestCase):
    def test_valid_payload_is_accepted(self):
        response = self.client.post("/recipes/import/extension", json=self._valid_payload())
        self.assertEqual(response.status_code, 200)
        self.assertTrue(response.get_json()["ok"])

    def test_staged_entry_appears_on_review_screen(self):
        self.client.post("/recipes/import/extension", json=self._valid_payload())
        review = self.client.get("/recipes/import/review")
        self.assertIn("Extracted Soup", review.get_data(as_text=True))

    def test_confirming_writes_yaml_with_parsed_fields(self):
        self.client.post("/recipes/import/extension", json=self._valid_payload())
        review = self.client.get("/recipes/import/review")
        import re as re_module

        temp_ids = re_module.findall(r'name="category_(\w+)"', review.get_data(as_text=True))
        confirm_data = {}
        for temp_id in temp_ids:
            confirm_data[f"category_{temp_id}"] = "Soups & Stews"
            confirm_data[f"subcategory_{temp_id}"] = "None"
        self.client.post("/recipes/import/confirm", data=confirm_data, follow_redirects=True)

        yaml_files = list(self.recipes_dir.glob("*.yaml"))
        self.assertEqual(len(yaml_files), 1)
        on_disk = yaml_files[0].read_text(encoding="utf-8")
        self.assertIn("recipe_name: Extracted Soup", on_disk)
        self.assertIn("flour", on_disk)
        self.assertIn("source_url: https://www.example.com/soup", on_disk)

        conn = db.get_connection(self.db_path)
        row = conn.execute("SELECT name, yields_json FROM recipe WHERE name = 'Extracted Soup'").fetchone()
        conn.close()
        self.assertIsNotNone(row)
        self.assertIn('"amount": 4', row["yields_json"])

    def test_sending_a_recipe_already_in_the_library_is_a_duplicate(self):
        self.client.post("/recipes/import/extension", json=self._valid_payload())
        self.client.post("/recipes/import/confirm", data={}, follow_redirects=True)
        self.assertEqual(len(list(self.recipes_dir.glob("*.yaml"))), 1)

        # Same name again from the extension: review flags it, confirm
        # without a new name ignores it, with a new name imports a copy.
        self.client.post("/recipes/import/extension", json=self._valid_payload(name="extracted soup"))
        review = self.client.get("/recipes/import/review").get_data(as_text=True)
        self.assertIn("Already in your library", review)
        self.assertIn('name="title_0"', review)

        body = self.client.post("/recipes/import/confirm", data={}, follow_redirects=True).get_data(as_text=True)
        self.assertIn("ignored 1 duplicate(s)", body)
        self.assertEqual(len(list(self.recipes_dir.glob("*.yaml"))), 1)

        self.client.post("/recipes/import/extension", json=self._valid_payload())
        body = self.client.post(
            "/recipes/import/confirm", data={"title_0": "Extracted Soup v2"}, follow_redirects=True
        ).get_data(as_text=True)
        self.assertIn("Imported 1 recipe(s)", body)
        self.assertEqual(len(list(self.recipes_dir.glob("*.yaml"))), 2)

    def test_ingredient_with_no_amount_is_flagged_for_correction_on_review(self):
        response = self.client.post("/recipes/import/extension", json=self._valid_payload())
        self.assertTrue(response.get_json()["ok"])
        review_body = self.client.get("/recipes/import/review").get_data(as_text=True)
        # "Kosher salt, to taste" has no leading amount -- the existing
        # amount-correction UI must surface it.
        self.assertIn("Kosher salt", review_body)


class TestImagePayload(ExtensionImportTestCase):
    @patch("recipe_extraction.fetch_image_bytes")
    def test_valid_image_url_is_downloaded_and_attached(self, mock_fetch):
        import io as io_module
        from PIL import Image

        buf = io_module.BytesIO()
        Image.new("RGB", (400, 300), (10, 20, 30)).save(buf, format="JPEG")
        mock_fetch.return_value = buf.getvalue()

        response = self.client.post(
            "/recipes/import/extension",
            json=self._valid_payload(image_url="https://example.com/photo.jpg"),
        )
        self.assertTrue(response.get_json()["ok"])
        self.assertNotIn("warning", response.get_json())

        image_files = list(self.images_dir.glob("*.jpg"))
        detail_files = [f for f in image_files if "_thumb" not in f.name]
        self.assertEqual(len(detail_files), 1)

        # Confirm the staged entry to prove the downloaded photo is
        # actually linked to the recipe, not just sitting on disk
        # unconnected -- same review/confirm flow as
        # test_confirming_writes_yaml_with_parsed_fields.
        review = self.client.get("/recipes/import/review")
        import re as re_module

        temp_ids = re_module.findall(r'name="category_(\w+)"', review.get_data(as_text=True))
        confirm_data = {}
        for temp_id in temp_ids:
            confirm_data[f"category_{temp_id}"] = "Soups & Stews"
            confirm_data[f"subcategory_{temp_id}"] = "None"
        self.client.post("/recipes/import/confirm", data=confirm_data, follow_redirects=True)

        conn = db.get_connection(self.db_path)
        row = conn.execute(
            "SELECT image_filename FROM recipe WHERE name = 'Extracted Soup'"
        ).fetchone()
        conn.close()
        self.assertIsNotNone(row)
        self.assertEqual(row["image_filename"], detail_files[0].name)

    @patch("recipe_extraction.fetch_image_bytes", side_effect=OSError("network down"))
    def test_broken_image_url_still_stages_with_a_warning(self, mock_fetch):
        response = self.client.post(
            "/recipes/import/extension",
            json=self._valid_payload(image_url="https://example.com/broken.jpg"),
        )
        data = response.get_json()
        self.assertTrue(data["ok"])
        self.assertIn("warning", data)

        review_body = self.client.get("/recipes/import/review").get_data(as_text=True)
        self.assertIn("Extracted Soup", review_body)
        self.assertEqual(len(list(self.images_dir.glob("*.jpg"))), 0)

    @patch("recipe_extraction.fetch_image_bytes")
    def test_staging_a_second_import_discards_the_first_uncancelled_photo(self, mock_fetch):
        # Staging recipe A downloads a photo; staging recipe B before A is
        # ever confirmed or cancelled must not leave A's photo orphaned on
        # disk with nothing referencing it (Finding 2 -- Task 6 only
        # handled the explicit-cancel case, not this supersede case).
        import io as io_module
        from PIL import Image

        def _jpeg_bytes(color):
            buf = io_module.BytesIO()
            Image.new("RGB", (400, 300), color).save(buf, format="JPEG")
            return buf.getvalue()

        mock_fetch.return_value = _jpeg_bytes((10, 20, 30))
        response_a = self.client.post(
            "/recipes/import/extension",
            json=self._valid_payload(name="Recipe A", image_url="https://example.com/a.jpg"),
        )
        self.assertTrue(response_a.get_json()["ok"])
        images_after_a = set(self.images_dir.glob("*.jpg"))
        self.assertEqual(len(images_after_a), 2)  # detail + thumb

        mock_fetch.return_value = _jpeg_bytes((200, 100, 50))
        response_b = self.client.post(
            "/recipes/import/extension",
            json=self._valid_payload(name="Recipe B", image_url="https://example.com/b.jpg"),
        )
        self.assertTrue(response_b.get_json()["ok"])

        images_after_b = set(self.images_dir.glob("*.jpg"))
        self.assertEqual(len(images_after_b), 2)  # only B's detail + thumb remain
        self.assertTrue(images_after_a.isdisjoint(images_after_b))
        for stale_file in images_after_a:
            self.assertFalse(stale_file.exists())


if __name__ == "__main__":
    unittest.main()
