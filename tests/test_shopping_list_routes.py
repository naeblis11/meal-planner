import shutil
import tempfile
import unittest
from datetime import date
from pathlib import Path

import app as app_module
import db
import meal_calendar
import shopping_list


class ShoppingListRouteTestCase(unittest.TestCase):
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

    def _insert_recipe(self, conn, name="Test Recipe"):
        cursor = conn.execute(
            'INSERT INTO "recipe" ("file_path", "file_mtime", "name", "raw_yaml") '
            "VALUES (?, ?, ?, ?)",
            (f"{name}.yaml", 0.0, name, f"recipe_name: {name}\n"),
        )
        conn.commit()
        return cursor.lastrowid

    def _insert_ingredient(self, conn, recipe_id, name, amount=None, unit=None):
        conn.execute(
            """INSERT INTO "recipe_ingredient"
               ("recipe_id", "order_num", "name", "amount", "unit", "amounts_json")
               VALUES (?, ?, ?, ?, ?, ?)""",
            (recipe_id, 0, name, amount, unit, "[]"),
        )
        conn.commit()


class TestShoppingListGenerate(ShoppingListRouteTestCase):
    def test_generates_items_and_redirects(self):
        conn = db.get_connection(self.db_path)
        try:
            recipe_id = self._insert_recipe(conn)
            self._insert_ingredient(conn, recipe_id, "Flour", "2", "cups")
            meal_calendar.assign_meal(conn, "2026-09-01", "Dinner", recipe_id)
        finally:
            conn.close()

        response = self.client.post("/shopping-list/generate", data={"week": "2026-08-31"})

        self.assertEqual(response.status_code, 302)
        conn = db.get_connection(self.db_path)
        try:
            items = shopping_list.list_items(conn)
        finally:
            conn.close()
        self.assertEqual(len(items), 1)
        self.assertEqual(items[0]["name"], "Flour")


class TestShoppingListToggle(ShoppingListRouteTestCase):
    def test_toggles_and_redirects(self):
        conn = db.get_connection(self.db_path)
        try:
            recipe_id = self._insert_recipe(conn)
            self._insert_ingredient(conn, recipe_id, "Flour", "2", "cups")
            meal_calendar.assign_meal(conn, "2026-09-01", "Dinner", recipe_id)
            shopping_list.generate(conn, date(2026, 8, 31))
            item_id = shopping_list.list_items(conn)[0]["id"]
        finally:
            conn.close()

        response = self.client.post("/shopping-list/toggle", data={"item_id": str(item_id)})

        self.assertEqual(response.status_code, 302)
        conn = db.get_connection(self.db_path)
        try:
            self.assertEqual(shopping_list.list_items(conn)[0]["checked"], 1)
        finally:
            conn.close()


class TestShoppingListSetAisle(ShoppingListRouteTestCase):
    def test_sets_aisle_and_redirects(self):
        conn = db.get_connection(self.db_path)
        try:
            recipe_id = self._insert_recipe(conn)
            self._insert_ingredient(conn, recipe_id, "Flour", "2", "cups")
            meal_calendar.assign_meal(conn, "2026-09-01", "Dinner", recipe_id)
            shopping_list.generate(conn, date(2026, 8, 31))
            item_id = shopping_list.list_items(conn)[0]["id"]
        finally:
            conn.close()

        response = self.client.post(
            "/shopping-list/aisle", data={"item_id": str(item_id), "aisle": "Dry Goods & Pasta"}
        )

        self.assertEqual(response.status_code, 302)
        conn = db.get_connection(self.db_path)
        try:
            self.assertEqual(shopping_list.list_items(conn)[0]["aisle"], "Dry Goods & Pasta")
        finally:
            conn.close()

    def test_aisle_survives_a_regenerate_for_the_same_ingredient(self):
        conn = db.get_connection(self.db_path)
        try:
            recipe_id = self._insert_recipe(conn)
            self._insert_ingredient(conn, recipe_id, "Flour", "2", "cups")
            meal_calendar.assign_meal(conn, "2026-09-01", "Dinner", recipe_id)
            shopping_list.generate(conn, date(2026, 8, 31))
            item_id = shopping_list.list_items(conn)[0]["id"]
        finally:
            conn.close()
        self.client.post(
            "/shopping-list/aisle", data={"item_id": str(item_id), "aisle": "Dry Goods & Pasta"}
        )

        self.client.post("/shopping-list/generate", data={"week": "2026-08-31"})

        conn = db.get_connection(self.db_path)
        try:
            self.assertEqual(shopping_list.list_items(conn)[0]["aisle"], "Dry Goods & Pasta")
        finally:
            conn.close()


class TestShoppingListRemove(ShoppingListRouteTestCase):
    def test_removes_and_redirects(self):
        conn = db.get_connection(self.db_path)
        try:
            recipe_id = self._insert_recipe(conn)
            self._insert_ingredient(conn, recipe_id, "Flour", "2", "cups")
            meal_calendar.assign_meal(conn, "2026-09-01", "Dinner", recipe_id)
            shopping_list.generate(conn, date(2026, 8, 31))
            item_id = shopping_list.list_items(conn)[0]["id"]
        finally:
            conn.close()

        response = self.client.post("/shopping-list/remove", data={"item_id": str(item_id)})

        self.assertEqual(response.status_code, 302)
        conn = db.get_connection(self.db_path)
        try:
            self.assertEqual(shopping_list.list_items(conn), [])
        finally:
            conn.close()

    def test_flashes_confirmation_naming_the_removed_item(self):
        conn = db.get_connection(self.db_path)
        try:
            recipe_id = self._insert_recipe(conn)
            self._insert_ingredient(conn, recipe_id, "Flour", "2", "cups")
            meal_calendar.assign_meal(conn, "2026-09-01", "Dinner", recipe_id)
            shopping_list.generate(conn, date(2026, 8, 31))
            item_id = shopping_list.list_items(conn)[0]["id"]
        finally:
            conn.close()

        response = self.client.post(
            "/shopping-list/remove", data={"item_id": str(item_id)}, follow_redirects=True
        )

        self.assertIn("Removed &#39;Flour&#39; from your shopping list.", response.get_data(as_text=True))


class TestShoppingListClear(ShoppingListRouteTestCase):
    def test_clears_and_redirects(self):
        conn = db.get_connection(self.db_path)
        try:
            recipe_id = self._insert_recipe(conn)
            self._insert_ingredient(conn, recipe_id, "Flour", "2", "cups")
            meal_calendar.assign_meal(conn, "2026-09-01", "Dinner", recipe_id)
            shopping_list.generate(conn, date(2026, 8, 31))
        finally:
            conn.close()

        response = self.client.post("/shopping-list/clear")

        self.assertEqual(response.status_code, 302)
        conn = db.get_connection(self.db_path)
        try:
            self.assertEqual(shopping_list.list_items(conn), [])
        finally:
            conn.close()

    def test_flashes_confirmation(self):
        conn = db.get_connection(self.db_path)
        try:
            recipe_id = self._insert_recipe(conn)
            self._insert_ingredient(conn, recipe_id, "Flour", "2", "cups")
            meal_calendar.assign_meal(conn, "2026-09-01", "Dinner", recipe_id)
            shopping_list.generate(conn, date(2026, 8, 31))
        finally:
            conn.close()

        response = self.client.post("/shopping-list/clear", follow_redirects=True)

        self.assertIn("Cleared your shopping list.", response.get_data(as_text=True))


class TestShoppingListView(ShoppingListRouteTestCase):
    def test_renders_page(self):
        response = self.client.get("/shopping-list")
        self.assertEqual(response.status_code, 200)

    def test_renders_empty_state(self):
        body = self.client.get("/shopping-list").get_data(as_text=True)
        self.assertIn("No shopping list yet", body)

    def test_clear_button_absent_when_list_is_empty(self):
        body = self.client.get("/shopping-list").get_data(as_text=True)
        self.assertNotIn('action="/shopping-list/clear"', body)

    def test_clear_button_present_when_list_has_items(self):
        conn = db.get_connection(self.db_path)
        try:
            recipe_id = self._insert_recipe(conn)
            self._insert_ingredient(conn, recipe_id, "Flour", "2", "cups")
            meal_calendar.assign_meal(conn, "2026-09-01", "Dinner", recipe_id)
            shopping_list.generate(conn, date(2026, 8, 31))
        finally:
            conn.close()

        body = self.client.get("/shopping-list").get_data(as_text=True)
        self.assertIn('action="/shopping-list/clear"', body)

    def test_renders_need_to_buy_and_already_have_sections(self):
        conn = db.get_connection(self.db_path)
        try:
            recipe_id = self._insert_recipe(conn)
            self._insert_ingredient(conn, recipe_id, "All Purpose Flour", "2", "cups")
            self._insert_ingredient(conn, recipe_id, "Baking Soda", "1", "tsp")
            meal_calendar.assign_meal(conn, "2026-09-01", "Dinner", recipe_id)
            conn.execute('INSERT INTO "pantry_item" ("name") VALUES (?)', ("flour",))
            conn.commit()
            shopping_list.generate(conn, date(2026, 8, 31))
        finally:
            conn.close()

        body = self.client.get("/shopping-list").get_data(as_text=True)

        self.assertIn("Need to Buy", body)
        self.assertIn("Already in My Kitchen", body)
        self.assertIn("Baking Soda", body)
        self.assertIn("All Purpose Flour", body)

    def test_need_to_buy_items_group_under_their_aisle_band(self):
        conn = db.get_connection(self.db_path)
        try:
            recipe_id = self._insert_recipe(conn)
            self._insert_ingredient(conn, recipe_id, "Flour", "2", "cups")
            self._insert_ingredient(conn, recipe_id, "Milk", "1", "gallon")
            meal_calendar.assign_meal(conn, "2026-09-01", "Dinner", recipe_id)
            shopping_list.generate(conn, date(2026, 8, 31))
            flour_id = next(
                r["id"] for r in shopping_list.list_items(conn) if r["name"] == "Flour"
            )
            milk_id = next(
                r["id"] for r in shopping_list.list_items(conn) if r["name"] == "Milk"
            )
        finally:
            conn.close()
        self.client.post(
            "/shopping-list/aisle", data={"item_id": str(flour_id), "aisle": "Dry Goods & Pasta"}
        )
        self.client.post(
            "/shopping-list/aisle", data={"item_id": str(milk_id), "aisle": "Dairy & Eggs"}
        )

        body = self.client.get("/shopping-list").get_data(as_text=True)

        self.assertIn("Dairy &amp; Eggs", body)
        self.assertIn("Dry Goods &amp; Pasta", body)
        self.assertLess(body.index("Dairy &amp; Eggs"), body.index("Dry Goods &amp; Pasta"))

    def test_unbought_item_checkbox_is_unchecked(self):
        conn = db.get_connection(self.db_path)
        try:
            recipe_id = self._insert_recipe(conn)
            self._insert_ingredient(conn, recipe_id, "Flour", "2", "cups")
            meal_calendar.assign_meal(conn, "2026-09-01", "Dinner", recipe_id)
            shopping_list.generate(conn, date(2026, 8, 31))
        finally:
            conn.close()

        body = self.client.get("/shopping-list").get_data(as_text=True)
        self.assertIn('action="/shopping-list/toggle"', body)
        self.assertNotIn('name="checked" checked', body)

    def test_bought_item_checkbox_is_checked(self):
        conn = db.get_connection(self.db_path)
        try:
            recipe_id = self._insert_recipe(conn)
            self._insert_ingredient(conn, recipe_id, "Flour", "2", "cups")
            meal_calendar.assign_meal(conn, "2026-09-01", "Dinner", recipe_id)
            shopping_list.generate(conn, date(2026, 8, 31))
            item_id = shopping_list.list_items(conn)[0]["id"]
            shopping_list.toggle_checked(conn, item_id)
        finally:
            conn.close()

        body = self.client.get("/shopping-list").get_data(as_text=True)
        self.assertIn('name="checked" checked', body)
        self.assertNotIn("Mark bought", body)
        self.assertNotIn(">Undo<", body)


if __name__ == "__main__":
    unittest.main()


class TestShoppingListAdd(ShoppingListRouteTestCase):
    def _items(self):
        conn = db.get_connection(self.db_path)
        try:
            return [dict(r) for r in shopping_list.list_items(conn)]
        finally:
            conn.close()

    def test_adds_a_named_item_with_no_amount_and_returns_to_next(self):
        response = self.client.post(
            "/shopping-list/add", data={"name": "Olive oil", "aisle": "Oils", "next": "/pantry"}
        )

        self.assertEqual(response.status_code, 302)
        self.assertTrue(response.headers["Location"].endswith("/pantry"))
        items = self._items()
        self.assertEqual(len(items), 1)
        self.assertEqual(items[0]["name"], "Olive oil")
        self.assertIsNone(items[0]["amount"])
        self.assertEqual(items[0]["aisle"], "Oils")
        self.assertEqual(items[0]["in_pantry"], 0)

    def test_added_item_renders_without_a_stray_none_amount(self):
        self.client.post("/shopping-list/add", data={"name": "Olive oil"})

        body = self.client.get("/shopping-list").get_data(as_text=True)

        self.assertIn("Olive oil", body)
        self.assertNotIn("None None", body)
        self.assertNotIn("None Olive oil", body)

    def test_duplicate_name_is_not_added_twice_and_says_so(self):
        self.client.post("/shopping-list/add", data={"name": "Olive oil"})

        body = self.client.post(
            "/shopping-list/add", data={"name": "olive OIL"}, follow_redirects=True
        ).get_data(as_text=True)

        self.assertIn("already on your shopping list", body)
        self.assertEqual(len(self._items()), 1)

    def test_blank_name_is_rejected(self):
        body = self.client.post(
            "/shopping-list/add", data={"name": "  "}, follow_redirects=True
        ).get_data(as_text=True)
        self.assertIn("enter an item name", body.lower())
        self.assertEqual(self._items(), [])

    def test_aisle_falls_back_to_the_remembered_aisle_then_a_guess(self):
        conn = db.get_connection(self.db_path)
        conn.execute('INSERT INTO "ingredient_aisle" ("name", "aisle") VALUES (?, ?)', ("Capers", "Condiments"))
        conn.commit(); conn.close()

        self.client.post("/shopping-list/add", data={"name": "capers"})
        self.client.post("/shopping-list/add", data={"name": "milk"})

        aisles = {i["name"]: i["aisle"] for i in self._items()}
        self.assertEqual(aisles["capers"], "Condiments")
        self.assertTrue(aisles["milk"])  # a keyword guess, never blank

    def test_unsafe_next_falls_back_to_the_shopping_list(self):
        response = self.client.post(
            "/shopping-list/add", data={"name": "Olive oil", "next": "https://evil.example/"}
        )
        self.assertTrue(response.headers["Location"].endswith("/shopping-list"))

    def test_shopping_page_has_its_own_add_box(self):
        body = self.client.get("/shopping-list").get_data(as_text=True)
        self.assertIn('class="library-search pantry-add shopping-add"', body)
        for field in ("name", "amount", "unit", "aisle"):
            self.assertIn(f'id="shop-add-{field}"', body)
        self.assertIn('<datalist id="unit-suggestions">', body)

    def test_adds_with_an_amount_and_unit_from_the_shopping_page(self):
        response = self.client.post(
            "/shopping-list/add",
            data={"name": "ground beef", "amount": "2", "unit": "lb", "aisle": ""},
            follow_redirects=True,
        )
        self.assertIn("Added &#39;ground beef&#39; to your shopping list.", response.get_data(as_text=True))
        (item,) = self._items()
        self.assertEqual((item["amount"], item["unit"], item["aisle"]), ("2", "lb", "Meat & Seafood"))

    def test_an_amount_merges_into_the_same_item_already_on_the_list(self):
        self.client.post("/shopping-list/add", data={"name": "milk", "amount": "1", "unit": "cup"})
        response = self.client.post(
            "/shopping-list/add", data={"name": "Milk", "amount": "1", "unit": "cup"},
            follow_redirects=True,
        )
        self.assertIn("Added more &#39;Milk&#39;", response.get_data(as_text=True))
        (item,) = self._items()
        self.assertEqual((item["amount"], item["unit"]), ("2", "cups"))

    def test_an_amount_with_no_unit_renders_without_a_stray_none(self):
        self.client.post("/shopping-list/add", data={"name": "bananas", "amount": "6"})
        body = self.client.get("/shopping-list").get_data(as_text=True)
        self.assertIn('<span class="pantry-row-name">6 bananas</span>', body)
        self.assertNotIn("None", body.split("pantry-row-name")[1][:40])

    def test_a_unit_without_an_amount_is_dropped(self):
        self.client.post("/shopping-list/add", data={"name": "eggs", "amount": "", "unit": "dozen"})
        (item,) = self._items()
        self.assertEqual((item["amount"], item["unit"]), (None, None))

    def test_an_unreadable_amount_is_rejected_without_adding(self):
        response = self.client.post(
            "/shopping-list/add", data={"name": "eggs", "amount": "a few"}, follow_redirects=True
        )
        self.assertIn("Couldn&#39;t read the amount &#39;a few&#39;", response.get_data(as_text=True))
        self.assertEqual(self._items(), [])

    def test_pantry_page_offers_the_button_for_active_and_removed_items(self):
        import pantry
        conn = db.get_connection(self.db_path)
        pantry.add_item(conn, "Olive oil", "Oils")
        pantry.add_item(conn, "Flour")
        flour_id = [r["id"] for r in pantry.list_items(conn) if r["name"] == "Flour"][0]
        pantry.set_active(conn, flour_id, False)
        conn.close()

        body = self.client.get("/pantry").get_data(as_text=True)

        self.assertEqual(body.count('action="/shopping-list/add"'), 2)
        self.assertIn('name="name" value="Olive oil"', body)
        self.assertIn('name="aisle" value="Oils"', body)
        self.assertIn('name="name" value="Flour"', body)
        self.assertIn('name="next" value="/pantry"', body)
        self.assertIn("Add to shopping list", body)
