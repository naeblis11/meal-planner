import io
import shutil
import tempfile
import unittest
from pathlib import Path

from PIL import Image

import app as app_module
import paths
import recipe_sync

FIXTURES = Path(__file__).parent / "fixtures"


def _jpeg_bytes():
    buf = io.BytesIO()
    Image.new("RGB", (20, 20), (10, 200, 30)).save(buf, format="JPEG")
    return buf.getvalue()


class TestSeedDataDir(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.bundle = self.tmp / "install"
        (self.bundle / "recipes").mkdir(parents=True)
        shutil.copy(FIXTURES / "banana-bread.yaml", self.bundle / "recipes")
        (self.bundle / "static" / "recipe-images").mkdir(parents=True)
        (self.bundle / "static" / "recipe-images" / "abc.jpg").write_bytes(_jpeg_bytes())
        self.data = self.tmp / "Documents" / "Meal Planner"

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_first_run_copies_starter_recipes_and_photos(self):
        self.assertTrue(app_module.seed_data_dir(self.bundle, self.data))
        self.assertTrue((self.data / "recipes" / "banana-bread.yaml").exists())
        self.assertTrue((self.data / "recipe-images" / "abc.jpg").exists())

    def test_existing_library_is_never_touched(self):
        (self.data / "recipes").mkdir(parents=True)
        (self.data / "recipes" / "mine.yaml").write_text("name: Mine\n", encoding="utf-8")
        self.assertFalse(app_module.seed_data_dir(self.bundle, self.data))
        self.assertEqual(
            sorted(p.name for p in (self.data / "recipes").iterdir()), ["mine.yaml"]
        )
        self.assertFalse((self.data / "recipe-images").exists())

    def test_bundle_without_images_dir_still_seeds_recipes(self):
        shutil.rmtree(self.bundle / "static")
        self.assertTrue(app_module.seed_data_dir(self.bundle, self.data))
        self.assertTrue((self.data / "recipes" / "banana-bread.yaml").exists())


class TestRecipeImageRoute(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        recipes_dir = self.tmp / "recipes"
        recipes_dir.mkdir()
        shutil.copy(FIXTURES / "banana-bread.yaml", recipes_dir)
        db_path = self.tmp / "mealplanner.db"
        recipe_sync.sync_recipes(recipes_dir, db_path)
        # No images_dir passed: it should default to a sibling of the database.
        self.app = app_module.create_app(recipes_dir, db_path)
        self.app.config["TESTING"] = True
        self.client = self.app.test_client()

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_default_images_dir_sits_beside_database_not_static(self):
        self.assertEqual(self.app.config["RECIPE_IMAGES_DIR"], self.tmp / paths.IMAGES_SUBDIR)
        self.assertNotIn("static", str(self.app.config["RECIPE_IMAGES_DIR"]))

    def test_serves_photo_from_images_dir(self):
        images_dir = self.app.config["RECIPE_IMAGES_DIR"]
        images_dir.mkdir(parents=True)
        (images_dir / "abc.jpg").write_bytes(_jpeg_bytes())
        response = self.client.get("/recipe-images/abc.jpg")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.mimetype, "image/jpeg")

    def test_missing_photo_is_404(self):
        self.assertEqual(self.client.get("/recipe-images/nope.jpg").status_code, 404)

    def test_cannot_escape_images_dir(self):
        (self.tmp / "secret.txt").write_text("nope", encoding="utf-8")
        response = self.client.get("/recipe-images/../secret.txt")
        self.assertIn(response.status_code, (404, 400))

    def test_recipe_page_links_photo_via_route(self):
        with self.app.app_context():
            conn = __import__("db").get_connection(self.app.config["DB_PATH"])
            conn.execute("UPDATE recipe SET image_filename = 'abc.jpg'")
            conn.commit()
            recipe_id = conn.execute("SELECT id FROM recipe").fetchone()["id"]
            conn.close()
        body = self.client.get(f"/recipes/{recipe_id}").get_data(as_text=True)
        self.assertIn("/recipe-images/abc.jpg", body)
        self.assertNotIn("/static/recipe-images/", body)


if __name__ == "__main__":
    unittest.main()
