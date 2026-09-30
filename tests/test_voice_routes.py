import shutil
import tempfile
import unittest
from datetime import date
from pathlib import Path

from werkzeug.security import generate_password_hash

import app as app_module
import db
import meal_calendar
import pantry
import shopping_list

TOKEN = "test-token"


class VoiceRouteTestCase(unittest.TestCase):
    api_token = TOKEN
    password_hash = None

    def setUp(self):
        self.tmp_dir = tempfile.mkdtemp()
        self.recipes_dir = Path(self.tmp_dir) / "recipes"
        self.recipes_dir.mkdir()
        self.db_path = Path(self.tmp_dir) / "mealplanner.db"
        self.app = app_module.create_app(
            self.recipes_dir, self.db_path,
            secret_key="test-secret",
            password_hash=self.password_hash,
            api_token=self.api_token,
        )
        self.app.config["TESTING"] = True
        self.client = self.app.test_client()

    def tearDown(self):
        shutil.rmtree(self.tmp_dir, ignore_errors=True)

    def post(self, path, body, token=TOKEN):
        headers = {"Authorization": f"Bearer {token}"} if token is not None else {}
        return self.client.post(path, json=body, headers=headers)

    def conn(self):
        return db.get_connection(self.db_path)


class TestVoiceGate(VoiceRouteTestCase):
    def test_missing_header_is_401_with_speech(self):
        response = self.post("/api/voice/shopping-list", {"item": "milk"}, token=None)
        self.assertEqual(response.status_code, 401)
        self.assertEqual(response.get_json()["ok"], False)
        self.assertIn("refused", response.get_json()["speech"])

    def test_wrong_token_is_401(self):
        response = self.post("/api/voice/shopping-list", {"item": "milk"}, token="nope")
        self.assertEqual(response.status_code, 401)

    def test_wrong_scheme_is_401(self):
        response = self.client.post(
            "/api/voice/shopping-list", json={"item": "milk"}, headers={"Authorization": f"Token {TOKEN}"}
        )
        self.assertEqual(response.status_code, 401)

    def test_non_ascii_header_is_401_not_500(self):
        response = self.client.post(
            "/api/voice/shopping-list", json={"item": "milk"},
            headers={"Authorization": "Bearer café"},
        )
        self.assertEqual(response.status_code, 401)

    def test_right_token_is_accepted(self):
        response = self.post("/api/voice/shopping-list", {"item": "milk"})
        self.assertEqual(response.status_code, 200)


class TestVoiceGateWithoutToken(VoiceRouteTestCase):
    api_token = None

    def test_unconfigured_api_is_503_with_speech(self):
        response = self.post("/api/voice/shopping-list", {"item": "milk"})
        self.assertEqual(response.status_code, 503)
        self.assertIn("isn't set up", response.get_json()["speech"])


class TestVoiceGateWithEmptyToken(VoiceRouteTestCase):
    api_token = ""

    def test_empty_string_token_is_503(self):
        response = self.post("/api/voice/shopping-list", {"item": "milk"})
        self.assertEqual(response.status_code, 503)


class TestVoiceTokenGrantsNothingElse(VoiceRouteTestCase):
    password_hash = generate_password_hash("correct horse")

    def test_bearer_token_does_not_unlock_a_page(self):
        response = self.client.get("/recipes", headers={"Authorization": f"Bearer {TOKEN}"})
        self.assertEqual(response.status_code, 302)
        self.assertIn("/login", response.headers["Location"])

    def test_voice_endpoint_needs_no_session_when_password_is_set(self):
        response = self.post("/api/voice/shopping-list", {"item": "milk"})
        self.assertEqual(response.status_code, 200)


class TestVoiceShoppingList(VoiceRouteTestCase):
    def items(self):
        conn = self.conn()
        try:
            return [dict(r) for r in shopping_list.list_items(conn)]
        finally:
            conn.close()

    def test_adds_with_amount_unit_and_aisle(self):
        response = self.post(
            "/api/voice/shopping-list",
            {"item": "milk", "quantity": "2", "unit": "gallons", "aisle": "Dairy & Eggs"},
        )
        self.assertEqual(response.get_json(), {
            "ok": True,
            "speech": "Added 2 gallons of milk to your shopping list, under Dairy & Eggs.",
        })
        item = self.items()[0]
        self.assertEqual((item["name"], item["amount"], item["unit"], item["aisle"]), ("milk", "2", "gal", "Dairy & Eggs"))

    def test_skip_words_leave_amount_blank_and_aisle_guessed(self):
        response = self.post(
            "/api/voice/shopping-list",
            {"item": "milk", "quantity": "skip", "unit": "", "aisle": "skip"},
        )
        self.assertEqual(
            response.get_json()["speech"],
            "Added milk to your shopping list, under Dairy & Eggs.",  # keyword guess
        )
        item = self.items()[0]
        self.assertIsNone(item["amount"])
        self.assertEqual(item["aisle"], "Dairy & Eggs")

    def test_remembered_aisle_beats_the_guess(self):
        conn = self.conn()
        conn.execute('INSERT INTO "ingredient_aisle" ("name", "aisle") VALUES (?, ?)', ("milk", "Beverages"))
        conn.commit(); conn.close()
        response = self.post("/api/voice/shopping-list", {"item": "milk"})
        self.assertIn("under Beverages", response.get_json()["speech"])

    def test_number_word_quantity(self):
        self.post("/api/voice/shopping-list", {"item": "eggs", "quantity": "a dozen"})
        self.assertEqual(self.items()[0]["amount"], "12")

    def test_numeric_quantity_from_ha_template_coercion(self):
        # Home Assistant's Jinja templates can parse "2" into the int 2
        # before it reaches the app as JSON.
        self.post("/api/voice/shopping-list", {"item": "eggs", "quantity": 12})
        self.assertEqual(self.items()[0]["amount"], "12")

    def test_duplicate_without_amount(self):
        self.post("/api/voice/shopping-list", {"item": "milk"})
        response = self.post("/api/voice/shopping-list", {"item": "Milk"})
        self.assertEqual(response.get_json(), {"ok": True, "speech": "Milk is already on your shopping list."})
        self.assertEqual(len(self.items()), 1)

    def test_merges_into_existing_row(self):
        self.post("/api/voice/shopping-list", {"item": "milk", "quantity": "2", "unit": "gallons"})
        response = self.post("/api/voice/shopping-list", {"item": "milk", "quantity": "1", "unit": "gallon"})
        self.assertEqual(response.get_json()["speech"], "Milk was already on your shopping list; it's now 3 gallons.")
        self.assertEqual((self.items()[0]["amount"], self.items()[0]["unit"]), ("3", "gal"))

    def test_merge_sentence_describes_the_row_merged_into_not_the_newest(self):
        # "flour 2 cups" and "flour 1 lb" both on the list (incompatible
        # units). "1 cup of flour" merges into the cups row, so the
        # sentence must say 3 cups -- not read back the newer lb row.
        self.post("/api/voice/shopping-list", {"item": "flour", "quantity": "2", "unit": "cups"})
        self.post("/api/voice/shopping-list", {"item": "flour", "quantity": "1", "unit": "lb"})

        response = self.post("/api/voice/shopping-list", {"item": "flour", "quantity": "1", "unit": "cup"})

        self.assertEqual(
            response.get_json()["speech"], "Flour was already on your shopping list; it's now 3 cups."
        )
        amounts = sorted((item["amount"], item["unit"]) for item in self.items())
        self.assertEqual(amounts, [("1", "lb"), ("3", "cups")])

    def test_sentence_tail_leaked_into_the_item_slot_is_dropped(self):
        # "add milk to cart" once arrived as item "milk to cart".
        response = self.post("/api/voice/shopping-list", {"item": "milk to cart", "quantity": "1", "unit": "gallon"})
        self.assertEqual(response.get_json()["speech"], "Added 1 gallon of milk to your shopping list, under Dairy & Eggs.")
        self.assertEqual(self.items()[0]["name"], "milk")

    def test_blank_item_is_ok_false_with_speech(self):
        response = self.post("/api/voice/shopping-list", {"item": "  "})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.get_json(), {"ok": False, "speech": "I didn't catch what to add."})
        self.assertEqual(self.items(), [])

    def test_missing_body_is_treated_as_empty(self):
        response = self.client.post("/api/voice/shopping-list", headers={"Authorization": f"Bearer {TOKEN}"})
        self.assertEqual(response.get_json()["ok"], False)

    def test_non_object_json_body_is_ok_false_not_500(self):
        response = self.client.post(
            "/api/voice/shopping-list", json=["milk"],
            headers={"Authorization": f"Bearer {TOKEN}"},
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.get_json()["ok"], False)

    def test_non_string_item_value_is_ok_false_not_500(self):
        response = self.post("/api/voice/shopping-list", {"item": {"a": 1}})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.get_json()["ok"], False)


class TestVoicePantry(VoiceRouteTestCase):
    def rows(self):
        conn = self.conn()
        try:
            return [dict(r) for r in pantry.list_items(conn)]
        finally:
            conn.close()

    def test_adds_a_new_item_with_aisle(self):
        response = self.post("/api/voice/pantry", {"item": "olive oil", "aisle": "condiments & sauces"})
        self.assertEqual(response.get_json(), {"ok": True, "speech": "Added olive oil to your pantry."})
        row = self.rows()[0]
        self.assertEqual((row["name"], row["aisle"], row["active"]), ("olive oil", "Condiments & Sauces", 1))

    def test_skipped_aisle_is_blank(self):
        self.post("/api/voice/pantry", {"item": "olive oil", "aisle": "skip"})
        self.assertIsNone(self.rows()[0]["aisle"])

    def test_already_on_hand_is_a_duplicate(self):
        self.post("/api/voice/pantry", {"item": "olive oil"})
        response = self.post("/api/voice/pantry", {"item": "Olive Oil"})
        self.assertEqual(response.get_json()["speech"], "Olive Oil is already in your pantry.")
        self.assertEqual(len(self.rows()), 1)

    def test_marked_out_item_is_put_back(self):
        self.post("/api/voice/pantry", {"item": "olive oil"})
        conn = self.conn()
        pantry.set_active(conn, self.rows()[0]["id"], False)
        conn.close()

        response = self.post("/api/voice/pantry", {"item": "olive oil"})

        self.assertEqual(response.get_json()["speech"], "Put olive oil back in your pantry.")
        self.assertEqual(self.rows()[0]["active"], 1)

    def test_blank_item(self):
        response = self.post("/api/voice/pantry", {"item": "skip"})
        self.assertEqual(response.get_json(), {"ok": False, "speech": "I didn't catch what to add."})


class TestVoiceMeal(VoiceRouteTestCase):
    def setUp(self):
        super().setUp()
        conn = self.conn()
        self.tacos_id = self._insert_recipe(conn, "Ground Beef Tacos")
        self.chili_id = self._insert_recipe(conn, "Chili")
        self._insert_recipe(conn, "Fish Tacos")
        conn.close()

    def _insert_recipe(self, conn, name):
        cursor = conn.execute(
            'INSERT INTO "recipe" ("file_path", "file_mtime", "name", "raw_yaml") VALUES (?, ?, ?, ?)',
            (f"{name}.yaml", 0.0, name, f"recipe_name: {name}\n"),
        )
        conn.commit()
        return cursor.lastrowid

    def planned(self, date_iso, slot):
        conn = self.conn()
        try:
            row = conn.execute(
                'SELECT "recipe_id" FROM "meal_plan" WHERE "date" = ? AND "slot" = ?', (date_iso, slot)
            ).fetchone()
            return row["recipe_id"] if row else None
        finally:
            conn.close()

    def test_plans_a_matched_recipe(self):
        response = self.post("/api/voice/meal", {"recipe": "ground beef tacos", "meal": "dinner", "date": "2026-09-17"})
        self.assertEqual(response.get_json(), {
            "ok": True, "speech": "Added Ground Beef Tacos for dinner on Thursday, September 17.",
        })
        self.assertEqual(self.planned("2026-09-17", "Dinner"), self.tacos_id)

    def test_meal_defaults_to_dinner_and_date_to_today(self):
        response = self.post("/api/voice/meal", {"recipe": "chili"})
        today = date.today()
        self.assertEqual(response.get_json()["ok"], True)
        self.assertEqual(self.planned(today.isoformat(), "Dinner"), self.chili_id)

    def test_replacing_names_the_previous_recipe(self):
        conn = self.conn()
        meal_calendar.assign_meal(conn, "2026-09-17", "Dinner", self.chili_id)
        conn.close()
        response = self.post("/api/voice/meal", {"recipe": "ground beef tacos", "date": "2026-09-17"})
        self.assertEqual(
            response.get_json()["speech"],
            "Added Ground Beef Tacos for dinner on Thursday, September 17, replacing Chili.",
        )
        self.assertEqual(self.planned("2026-09-17", "Dinner"), self.tacos_id)

    def test_same_recipe_already_planned(self):
        self.post("/api/voice/meal", {"recipe": "chili", "date": "2026-09-17"})
        response = self.post("/api/voice/meal", {"recipe": "chili", "date": "2026-09-17"})
        self.assertEqual(
            response.get_json()["speech"], "Chili is already planned for dinner on Thursday, September 17."
        )

    def test_no_match(self):
        response = self.post("/api/voice/meal", {"recipe": "lasagna", "date": "2026-09-17"})
        self.assertEqual(response.get_json(), {"ok": False, "speech": "I couldn't find a recipe like 'lasagna'."})
        self.assertIsNone(self.planned("2026-09-17", "Dinner"))

    def test_ambiguous(self):
        conn = self.conn()
        self._insert_recipe(conn, "Beef Tacos")  # same length as "Fish Tacos"
        conn.close()
        response = self.post("/api/voice/meal", {"recipe": "tacos", "date": "2026-09-17"})
        body = response.get_json()
        self.assertEqual(body["ok"], False)
        self.assertIn("Which one?", body["speech"])
        self.assertIn("Fish Tacos", body["speech"])
        self.assertIn("Beef Tacos", body["speech"])

    def test_week_date_is_rejected(self):
        response = self.post("/api/voice/meal", {"recipe": "chili", "date": "2026-W38"})
        self.assertEqual(response.get_json(), {"ok": False, "speech": "I need a specific day, like Thursday."})

    def test_blank_recipe(self):
        response = self.post("/api/voice/meal", {"recipe": ""})
        self.assertEqual(response.get_json(), {"ok": False, "speech": "I didn't catch what to add."})


if __name__ == "__main__":
    unittest.main()
