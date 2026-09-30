import re
import shutil
import tempfile
import unittest
from pathlib import Path

import app as app_module
import db


class CorrectionsRouteTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp_dir = tempfile.mkdtemp()
        self.recipes_dir = Path(self.tmp_dir) / "recipes"
        self.recipes_dir.mkdir()
        self.db_path = Path(self.tmp_dir) / "mealplanner.db"

        self.app = app_module.create_app(self.recipes_dir, self.db_path)
        self.app.config["TESTING"] = True
        self.client = self.app.test_client()

    def tearDown(self):
        shutil.rmtree(self.tmp_dir, ignore_errors=True)

    def _write_bad_recipe(self):
        (self.recipes_dir / "bad.yaml").write_text(
            "recipe_name: Bad\nsteps: [{step: cook}]\n"
            "ingredients: [{Wine: {amounts: [{amount: '750    750', unit: ml}]}}]\n",
            encoding="utf-8",
        )


class TestSyncWithCorrections(CorrectionsRouteTestCase):
    def test_sync_with_no_bad_amounts_redirects_straight_to_recipes(self):
        (self.recipes_dir / "good.yaml").write_text(
            "recipe_name: Good\nsteps: [{step: cook}]\n"
            "ingredients: [{Flour: {amounts: [{amount: '2', unit: cups}]}}]\n",
            encoding="utf-8",
        )

        response = self.client.post("/sync", follow_redirects=False)

        self.assertEqual(response.status_code, 302)
        self.assertIn("/recipes", response.headers["Location"])
        self.assertNotIn("/recipes/corrections", response.headers["Location"])

    def test_sync_with_a_bad_amount_redirects_to_corrections_review(self):
        self._write_bad_recipe()

        response = self.client.post("/sync", follow_redirects=False)

        self.assertEqual(response.status_code, 302)
        self.assertIn("/recipes/corrections/review", response.headers["Location"])

    def test_corrections_review_lists_the_bad_ingredient(self):
        self._write_bad_recipe()
        self.client.post("/sync")

        body = self.client.get("/recipes/corrections/review").get_data(as_text=True)

        self.assertIn("Wine", body)
        self.assertIn('value="750    750"', body)
        self.assertIn("750    750 ml Wine", body)

    def test_unit_correction_field_offers_a_suggestion_list(self):
        self._write_bad_recipe()
        self.client.post("/sync")

        body = self.client.get("/recipes/corrections/review").get_data(as_text=True)

        self.assertIn('list="unit-suggestions"', body)
        self.assertIn('<datalist id="unit-suggestions">', body)
        self.assertIn('<option value="tsp">', body)

    def test_confirming_a_correction_rewrites_the_file_and_resyncs(self):
        self._write_bad_recipe()
        self.client.post("/sync")
        review = self.client.get("/recipes/corrections/review").get_data(as_text=True)
        match = re.search(r'name="amount_(\d+)"', review)
        correction_id = match.group(1)

        response = self.client.post(
            "/recipes/corrections/confirm",
            data={f"amount_{correction_id}": "480", f"unit_{correction_id}": "ml"},
            follow_redirects=True,
        )

        self.assertIn("Corrected", response.get_data(as_text=True))
        conn = db.get_connection(self.db_path)
        try:
            row = conn.execute(
                "SELECT amount, unit FROM recipe_ingredient WHERE name = 'Wine'"
            ).fetchone()
        finally:
            conn.close()
        self.assertEqual(row["amount"], "1")
        self.assertEqual(row["unit"], "pt")

    def test_skip_discards_staging_and_leaves_amount_unconverted(self):
        self._write_bad_recipe()
        self.client.post("/sync")

        response = self.client.post("/recipes/corrections/skip", follow_redirects=True)

        self.assertIn("Kept ingredient amounts as-is", response.get_data(as_text=True))
        conn = db.get_connection(self.db_path)
        try:
            row = conn.execute(
                "SELECT amount, unit FROM recipe_ingredient WHERE name = 'Wine'"
            ).fetchone()
        finally:
            conn.close()
        self.assertEqual(row["amount"], "750    750")
        self.assertEqual(row["unit"], "ml")

    def test_unchanged_file_is_not_rescanned_for_corrections_on_a_later_sync(self):
        self._write_bad_recipe()
        self.client.post("/sync")  # first sync: bad.yaml is newly indexed, flags a correction
        self.client.post("/recipes/corrections/skip")  # user skips, staging cleared

        response = self.client.post("/sync", follow_redirects=False)  # second sync: bad.yaml is unchanged now

        self.assertEqual(response.status_code, 302)
        self.assertIn("/recipes", response.headers["Location"])
        self.assertNotIn("/recipes/corrections", response.headers["Location"])

    def test_editing_a_previously_skipped_file_flags_it_again(self):
        self._write_bad_recipe()
        self.client.post("/sync")
        self.client.post("/recipes/corrections/skip")
        # Re-touch the file so its mtime changes and it gets re-indexed.
        path = self.recipes_dir / "bad.yaml"
        path.write_text(path.read_text(encoding="utf-8"), encoding="utf-8")

        response = self.client.post("/sync", follow_redirects=False)

        self.assertEqual(response.status_code, 302)
        self.assertIn("/recipes/corrections/review", response.headers["Location"])

    def test_review_and_confirm_handle_missing_staging_gracefully(self):
        response = self.client.get("/recipes/corrections/review", follow_redirects=True)
        self.assertIn("Nothing to review", response.get_data(as_text=True))

        response = self.client.post("/recipes/corrections/confirm", data={}, follow_redirects=True)
        self.assertIn("Nothing to review", response.get_data(as_text=True))


if __name__ == "__main__":
    unittest.main()
