import os
import shutil
import tempfile
import unittest
from unittest import mock
from pathlib import Path

from werkzeug.security import generate_password_hash

import app as app_module
import recipe_sync

FIXTURES = Path(__file__).parent / "fixtures"
PASSWORD = "correct horse"


class AuthTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp_dir = tempfile.mkdtemp()
        self.recipes_dir = Path(self.tmp_dir) / "recipes"
        self.recipes_dir.mkdir()
        shutil.copy(FIXTURES / "banana-bread.yaml", self.recipes_dir)
        self.db_path = Path(self.tmp_dir) / "mealplanner.db"
        recipe_sync.sync_recipes(self.recipes_dir, self.db_path)
        self.app = app_module.create_app(
            self.recipes_dir, self.db_path,
            secret_key="test-secret",
            password_hash=generate_password_hash(PASSWORD),
        )
        self.app.config["TESTING"] = True
        self.client = self.app.test_client()

    def tearDown(self):
        shutil.rmtree(self.tmp_dir, ignore_errors=True)

    def login(self, password=PASSWORD, **extra):
        return self.client.post("/login", data={"password": password, **extra})


class TestLoginGate(AuthTestCase):
    def test_signed_out_get_redirects_to_login_with_next(self):
        response = self.client.get("/recipes?q=bread")
        self.assertEqual(response.status_code, 302)
        self.assertIn("/login?next=", response.headers["Location"])
        self.assertIn("q%3Dbread", response.headers["Location"])

    def test_signed_out_post_is_blocked(self):
        response = self.client.post("/shopping-list/clear")
        self.assertEqual(response.status_code, 302)
        self.assertIn("/login", response.headers["Location"])

    def test_login_page_is_public(self):
        response = self.client.get("/login")
        self.assertEqual(response.status_code, 200)
        self.assertIn("household password", response.get_data(as_text=True))

    def test_static_is_public(self):
        response = self.client.get("/static/style.css")
        self.assertEqual(response.status_code, 200)

    def test_extension_endpoint_returns_json_401_when_signed_out(self):
        response = self.client.post("/recipes/import/extension", json={"name": "x"})
        self.assertEqual(response.status_code, 401)
        self.assertFalse(response.get_json()["ok"])


class TestLogin(AuthTestCase):
    def test_wrong_password_is_rejected(self):
        response = self.login("nope")
        self.assertEqual(response.status_code, 401)
        self.assertIn("isn&#39;t right", response.get_data(as_text=True))
        self.assertEqual(self.client.get("/recipes").status_code, 302)

    def test_right_password_signs_in_and_redirects_home(self):
        response = self.login()
        self.assertEqual(response.status_code, 302)
        self.assertTrue(response.headers["Location"].endswith("/"))
        response = self.client.get("/recipes")
        self.assertEqual(response.status_code, 200)
        self.assertIn("Banana Bread", response.get_data(as_text=True))

    def test_login_honours_safe_next(self):
        response = self.login(next="/recipes?q=bread")
        self.assertTrue(response.headers["Location"].endswith("/recipes?q=bread"))

    def test_login_ignores_offsite_next(self):
        for bad in ("https://evil.example/", "//evil.example/"):
            response = self.login(next=bad)
            self.assertNotIn("evil", response.headers["Location"])
            self.client.post("/logout")

    def test_logout_ends_session(self):
        self.login()
        response = self.client.post("/logout")
        self.assertEqual(response.status_code, 302)
        self.assertIn("/login", response.headers["Location"])
        self.assertEqual(self.client.get("/recipes").status_code, 302)

    def test_signed_in_user_visiting_login_is_sent_home(self):
        self.login()
        response = self.client.get("/login")
        self.assertEqual(response.status_code, 302)

    def test_nav_shows_sign_out_only_when_signed_in(self):
        self.assertNotIn("Sign out", self.client.get("/login").get_data(as_text=True))
        self.login()
        self.assertIn("Sign out", self.client.get("/recipes").get_data(as_text=True))


class TestConfigFallbacks(unittest.TestCase):
    def test_env_vars_are_used_when_no_explicit_values(self):
        tmp = Path(tempfile.mkdtemp())
        try:
            (tmp / "recipes").mkdir()
            with mock.patch.dict(
                os.environ,
                {
                    app_module.SECRET_KEY_ENV: "from-env",
                    app_module.PASSWORD_HASH_ENV: generate_password_hash("pw"),
                },
            ):
                app = app_module.create_app(tmp / "recipes", tmp / "db.sqlite")
            self.assertEqual(app.secret_key, "from-env")
            self.assertIsNotNone(app.config["PASSWORD_HASH"])
            self.assertEqual(app.test_client().get("/recipes").status_code, 302)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

    def test_no_password_hash_disables_gate_for_test_clients(self):
        tmp = Path(tempfile.mkdtemp())
        try:
            (tmp / "recipes").mkdir()
            with mock.patch.dict(os.environ, {}, clear=True):
                app = app_module.create_app(tmp / "recipes", tmp / "db.sqlite")
            self.assertIsNone(app.config["PASSWORD_HASH"])
            self.assertNotEqual(app.secret_key, "meal-planning-local-dev")
            self.assertGreaterEqual(len(app.secret_key), 64)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()
