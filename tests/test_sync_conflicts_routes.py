import shutil
import tempfile
import unittest
from pathlib import Path

import app as app_module
import db
import recipe_sync

FIXTURES = Path(__file__).parent / "fixtures"


class SyncConflictsTestCase(unittest.TestCase):
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

    def _write_duplicate_pair(self):
        (self.recipes_dir / "a.yaml").write_text(
            "recipe_uuid: dupe-1\nrecipe_name: Recipe A\nsteps: []\ningredients: []\n",
            encoding="utf-8",
        )
        (self.recipes_dir / "b.yaml").write_text(
            "recipe_uuid: dupe-1\nrecipe_name: Recipe B\nsteps: []\ningredients: []\n",
            encoding="utf-8",
        )


class TestSyncConflictsPage(SyncConflictsTestCase):
    def test_shows_no_conflicts_message_when_clean(self):
        response = self.client.get("/sync/conflicts")
        self.assertEqual(response.status_code, 200)
        self.assertIn("No conflicts", response.get_data(as_text=True))

    def test_lists_both_files_in_a_conflict(self):
        self._write_duplicate_pair()
        response = self.client.get("/sync/conflicts")
        body = response.get_data(as_text=True)
        self.assertEqual(response.status_code, 200)
        self.assertIn("a.yaml", body)
        self.assertIn("b.yaml", body)


class TestSyncConflictsDelete(SyncConflictsTestCase):
    def test_deletes_file_and_resyncs(self):
        self._write_duplicate_pair()
        recipe_sync.sync_recipes(self.recipes_dir, self.db_path)  # b.yaml errors, a.yaml indexed

        response = self.client.post("/sync/conflicts/delete", data={"file_name": "b.yaml"})

        self.assertEqual(response.status_code, 302)
        self.assertFalse((self.recipes_dir / "b.yaml").exists())
        self.assertTrue((self.recipes_dir / "a.yaml").exists())

    def test_rejects_path_traversal_filename(self):
        response = self.client.post(
            "/sync/conflicts/delete", data={"file_name": "../outside.yaml"}
        )
        self.assertEqual(response.status_code, 400)


class TestSyncConflictsReassign(SyncConflictsTestCase):
    def test_assigns_new_uuid_and_resyncs_both_as_separate_recipes(self):
        self._write_duplicate_pair()
        recipe_sync.sync_recipes(self.recipes_dir, self.db_path)  # only a.yaml indexed

        response = self.client.post("/sync/conflicts/reassign", data={"file_name": "b.yaml"})

        self.assertEqual(response.status_code, 302)
        content = (self.recipes_dir / "b.yaml").read_text(encoding="utf-8")
        self.assertNotIn("recipe_uuid: dupe-1", content)

        conn = db.get_connection(self.db_path)
        try:
            self.assertEqual(conn.execute("SELECT COUNT(*) FROM recipe").fetchone()[0], 2)
        finally:
            conn.close()

    def test_rejects_path_traversal_filename(self):
        response = self.client.post(
            "/sync/conflicts/reassign", data={"file_name": "../outside.yaml"}
        )
        self.assertEqual(response.status_code, 400)


class TestSyncFlashLinksToConflicts(SyncConflictsTestCase):
    def test_sync_flash_links_to_conflicts_page_when_any_exist(self):
        self._write_duplicate_pair()
        response = self.client.post("/sync", follow_redirects=True)
        body = response.get_data(as_text=True)
        self.assertIn("/sync/conflicts", body)


if __name__ == "__main__":
    unittest.main()
