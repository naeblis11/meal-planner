import shutil
import tempfile
import unittest
from pathlib import Path

import app as app_module
import db
import pantry


class PantryRouteTestCase(unittest.TestCase):
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


class TestPantryAdd(PantryRouteTestCase):
    def test_adds_item_and_redirects(self):
        response = self.client.post("/pantry/add", data={"name": "flour"})

        self.assertEqual(response.status_code, 302)
        self.assertIn("/pantry", response.headers["Location"])

        conn = db.get_connection(self.db_path)
        try:
            names = [row["name"] for row in pantry.list_items(conn)]
        finally:
            conn.close()
        self.assertEqual(names, ["flour"])

    def test_duplicate_name_redirects_without_creating_second_row(self):
        self.client.post("/pantry/add", data={"name": "flour"})
        response = self.client.post("/pantry/add", data={"name": "Flour"})

        self.assertEqual(response.status_code, 302)
        conn = db.get_connection(self.db_path)
        try:
            self.assertEqual(len(pantry.list_items(conn)), 1)
        finally:
            conn.close()

    def test_empty_name_redirects_without_creating_a_row(self):
        response = self.client.post("/pantry/add", data={"name": "   "})

        self.assertEqual(response.status_code, 302)
        conn = db.get_connection(self.db_path)
        try:
            self.assertEqual(pantry.list_items(conn), [])
        finally:
            conn.close()

    def test_duplicate_name_flash_mentions_already_in_pantry(self):
        self.client.post("/pantry/add", data={"name": "flour"})
        response = self.client.post("/pantry/add", data={"name": "Flour"}, follow_redirects=True)
        body = response.get_data(as_text=True)
        self.assertIn("already in your pantry", body)


class TestPantryAisleGrouping(PantryRouteTestCase):
    def test_items_render_under_their_aisle_band(self):
        self.client.post("/pantry/add", data={"name": "milk", "aisle": "Dairy & Eggs"})
        self.client.post("/pantry/add", data={"name": "flour", "aisle": "Frozen"})

        body = self.client.get("/pantry").get_data(as_text=True)

        self.assertIn("Dairy &amp; Eggs", body)
        self.assertIn("Frozen", body)
        self.assertLess(body.index("Dairy &amp; Eggs"), body.index("Frozen"))

    def test_item_without_aisle_lands_in_uncategorized_band(self):
        self.client.post("/pantry/add", data={"name": "mystery sauce"})

        body = self.client.get("/pantry").get_data(as_text=True)

        self.assertIn("Uncategorized", body)
        self.assertIn("mystery sauce", body)

    def test_uncategorized_band_renders_last(self):
        self.client.post("/pantry/add", data={"name": "mystery sauce"})
        self.client.post("/pantry/add", data={"name": "milk", "aisle": "Produce"})

        body = self.client.get("/pantry").get_data(as_text=True)

        self.assertLess(body.index("Produce"), body.index("Uncategorized"))

    def test_custom_aisle_not_in_default_list_still_renders(self):
        self.client.post("/pantry/add", data={"name": "kombucha", "aisle": "Fermented Drinks"})

        body = self.client.get("/pantry").get_data(as_text=True)

        self.assertIn("Fermented Drinks", body)


class TestPantrySetAisle(PantryRouteTestCase):
    def test_sets_aisle_and_redirects(self):
        conn = db.get_connection(self.db_path)
        try:
            pantry.add_item(conn, "egg")
            item_id = pantry.list_items(conn)[0]["id"]
        finally:
            conn.close()

        response = self.client.post(
            "/pantry/aisle", data={"item_id": str(item_id), "aisle": "Dairy & Eggs"}
        )

        self.assertEqual(response.status_code, 302)
        conn = db.get_connection(self.db_path)
        try:
            self.assertEqual(pantry.list_items(conn)[0]["aisle"], "Dairy & Eggs")
        finally:
            conn.close()


class TestPantrySetActive(PantryRouteTestCase):
    def test_unchecking_deactivates_item_and_redirects(self):
        conn = db.get_connection(self.db_path)
        try:
            pantry.add_item(conn, "flour")
            item_id = pantry.list_items(conn)[0]["id"]
        finally:
            conn.close()

        # An unchecked checkbox sends no "active" field at all.
        response = self.client.post("/pantry/set-active", data={"item_id": str(item_id)})

        self.assertEqual(response.status_code, 302)
        conn = db.get_connection(self.db_path)
        try:
            self.assertEqual(pantry.list_items(conn)[0]["active"], 0)
        finally:
            conn.close()

    def test_rechecking_reactivates_item(self):
        conn = db.get_connection(self.db_path)
        try:
            pantry.add_item(conn, "flour")
            item_id = pantry.list_items(conn)[0]["id"]
            pantry.set_active(conn, item_id, False)
        finally:
            conn.close()

        response = self.client.post(
            "/pantry/set-active", data={"item_id": str(item_id), "active": "on"}
        )

        self.assertEqual(response.status_code, 302)
        conn = db.get_connection(self.db_path)
        try:
            self.assertEqual(pantry.list_items(conn)[0]["active"], 1)
        finally:
            conn.close()

    def test_deactivating_flashes_removed_message(self):
        conn = db.get_connection(self.db_path)
        try:
            pantry.add_item(conn, "flour")
            item_id = pantry.list_items(conn)[0]["id"]
        finally:
            conn.close()

        response = self.client.post(
            "/pantry/set-active", data={"item_id": str(item_id)}, follow_redirects=True
        )

        self.assertIn("Removed", response.get_data(as_text=True))

    def test_reactivating_flashes_added_back_message(self):
        conn = db.get_connection(self.db_path)
        try:
            pantry.add_item(conn, "flour")
            item_id = pantry.list_items(conn)[0]["id"]
            pantry.set_active(conn, item_id, False)
        finally:
            conn.close()

        response = self.client.post(
            "/pantry/set-active",
            data={"item_id": str(item_id), "active": "on"},
            follow_redirects=True,
        )

        self.assertIn("back", response.get_data(as_text=True))


class TestPantryDelete(PantryRouteTestCase):
    def _add_and_get_id(self, name):
        conn = db.get_connection(self.db_path)
        try:
            pantry.add_item(conn, name)
            return next(
                row["id"] for row in pantry.list_items(conn) if row["name"] == name
            )
        finally:
            conn.close()

    def test_deletes_item_and_redirects(self):
        item_id = self._add_and_get_id("flour")

        response = self.client.post("/pantry/delete", data={"item_id": str(item_id)})

        self.assertEqual(response.status_code, 302)
        self.assertIn("/pantry", response.headers["Location"])
        conn = db.get_connection(self.db_path)
        try:
            self.assertEqual(pantry.list_items(conn), [])
        finally:
            conn.close()

    def test_deleting_flashes_deleted_message_naming_the_item(self):
        item_id = self._add_and_get_id("flour")

        response = self.client.post(
            "/pantry/delete", data={"item_id": str(item_id)}, follow_redirects=True
        )

        body = response.get_data(as_text=True)
        self.assertIn("Deleted &#39;flour&#39; from your pantry.", body)

    def test_deleting_an_already_removed_item_works(self):
        item_id = self._add_and_get_id("flour")
        self.client.post("/pantry/set-active", data={"item_id": str(item_id)})

        response = self.client.post("/pantry/delete", data={"item_id": str(item_id)})

        self.assertEqual(response.status_code, 302)
        conn = db.get_connection(self.db_path)
        try:
            self.assertEqual(pantry.list_items(conn), [])
        finally:
            conn.close()

    def test_deleting_an_unknown_id_redirects_instead_of_500(self):
        response = self.client.post("/pantry/delete", data={"item_id": "999999"})

        self.assertEqual(response.status_code, 302)

    def test_page_offers_a_delete_control_per_item(self):
        self.client.post("/pantry/add", data={"name": "flour"})

        body = self.client.get("/pantry").get_data(as_text=True)

        self.assertIn('action="/pantry/delete"', body)
        self.assertIn("Yes, delete it", body)

    def test_removed_items_also_offer_a_delete_control(self):
        item_id = self._add_and_get_id("flour")
        self.client.post("/pantry/set-active", data={"item_id": str(item_id)})

        body = self.client.get("/pantry").get_data(as_text=True)

        removed_section = body[body.index("Removed"):]
        self.assertIn('action="/pantry/delete"', removed_section)


class TestPantryRemovedSection(PantryRouteTestCase):
    def test_active_item_checkbox_is_checked(self):
        self.client.post("/pantry/add", data={"name": "flour"})

        body = self.client.get("/pantry").get_data(as_text=True)

        self.assertIn('action="/pantry/set-active"', body)
        self.assertIn("checked", body)

    def test_deactivated_item_moves_to_removed_section_with_unchecked_box(self):
        conn = db.get_connection(self.db_path)
        try:
            pantry.add_item(conn, "flour")
            item_id = pantry.list_items(conn)[0]["id"]
            pantry.set_active(conn, item_id, False)
        finally:
            conn.close()

        body = self.client.get("/pantry").get_data(as_text=True)

        self.assertIn("Removed", body)
        removed_section = body[body.index("Removed"):]
        self.assertIn("flour", removed_section)

    def test_removed_section_absent_when_nothing_removed(self):
        self.client.post("/pantry/add", data={"name": "flour"})

        body = self.client.get("/pantry").get_data(as_text=True)

        self.assertNotIn("Removed", body)


class TestPantryToggleExactMatch(PantryRouteTestCase):
    def test_toggles_and_redirects(self):
        conn = db.get_connection(self.db_path)
        try:
            pantry.add_item(conn, "egg")
            item_id = pantry.list_items(conn)[0]["id"]
        finally:
            conn.close()

        response = self.client.post("/pantry/toggle-exact-match", data={"item_id": str(item_id)})

        self.assertEqual(response.status_code, 302)
        conn = db.get_connection(self.db_path)
        try:
            self.assertEqual(pantry.list_items(conn)[0]["exact_match"], 1)
        finally:
            conn.close()

    def test_rendered_page_shows_current_match_mode_and_toggle_label(self):
        conn = db.get_connection(self.db_path)
        try:
            pantry.add_item(conn, "egg")
            item_id = pantry.list_items(conn)[0]["id"]
        finally:
            conn.close()

        body = self.client.get("/pantry").get_data(as_text=True)
        self.assertIn("partial match", body)
        self.assertIn("Require exact match", body)

        self.client.post("/pantry/toggle-exact-match", data={"item_id": str(item_id)})

        body = self.client.get("/pantry").get_data(as_text=True)
        self.assertIn("exact match", body)
        self.assertIn("Allow partial match", body)


class TestPantryView(PantryRouteTestCase):
    def test_renders_pantry_page(self):
        response = self.client.get("/pantry")
        self.assertEqual(response.status_code, 200)

    def test_renders_empty_state(self):
        body = self.client.get("/pantry").get_data(as_text=True)
        self.assertIn("Your pantry is empty", body)

    def test_full_add_dedupe_flow_shows_item_once(self):
        self.client.post("/pantry/add", data={"name": "Flour"})
        self.client.post("/pantry/add", data={"name": "flour"})  # differing case
        body = self.client.get("/pantry").get_data(as_text=True)
        self.assertNotIn("Your pantry is empty", body)
        self.assertEqual(body.count('action="/pantry/set-active"'), 1)
        self.assertIn("Flour", body)


if __name__ == "__main__":
    unittest.main()


class TestPantryAddedOn(PantryRouteTestCase):
    def _item(self, name):
        conn = db.get_connection(self.db_path)
        try:
            return conn.execute(
                'SELECT "id", "added_on" FROM "pantry_item" WHERE "name" = ?', (name,)
            ).fetchone()
        finally:
            conn.close()

    def test_adding_from_the_pantry_page_stamps_today(self):
        from datetime import date
        self.client.post("/pantry/add", data={"name": "flour"})
        self.assertEqual(self._item("flour")["added_on"], date.today().isoformat())

    def test_pantry_page_shows_the_date_and_a_form_to_change_it(self):
        conn = db.get_connection(self.db_path)
        pantry.add_item(conn, "flour", added_on="2026-09-13")
        conn.close()

        body = self.client.get("/pantry").get_data(as_text=True)

        self.assertIn("Added Sep 13, 2026", body)
        self.assertIn('action="/pantry/added-on"', body)
        self.assertIn('type="date" name="added_on" value="2026-09-13"', body)

    def test_item_without_a_date_says_so(self):
        conn = db.get_connection(self.db_path)
        pantry.add_item(conn, "flour")
        item_id = pantry.list_items(conn)[0]["id"]
        pantry.set_added_on(conn, item_id, "")
        conn.close()

        body = self.client.get("/pantry").get_data(as_text=True)

        self.assertIn("Date added not set", body)
        self.assertIn('type="date" name="added_on" value=""', body)

    def test_changing_the_date_saves_and_redirects(self):
        conn = db.get_connection(self.db_path)
        pantry.add_item(conn, "flour")
        conn.close()
        item_id = self._item("flour")["id"]

        response = self.client.post(
            "/pantry/added-on", data={"item_id": item_id, "added_on": "2026-03-01"}
        )

        self.assertEqual(response.status_code, 302)
        self.assertIn("/pantry", response.headers["Location"])
        self.assertEqual(self._item("flour")["added_on"], "2026-03-01")

    def test_a_bad_date_is_rejected_with_a_message_and_nothing_changes(self):
        conn = db.get_connection(self.db_path)
        pantry.add_item(conn, "flour", added_on="2026-01-01")
        conn.close()
        item_id = self._item("flour")["id"]

        body = self.client.post(
            "/pantry/added-on", data={"item_id": item_id, "added_on": "not a date"},
            follow_redirects=True,
        ).get_data(as_text=True)

        self.assertIn("YYYY-MM-DD", body)
        self.assertEqual(self._item("flour")["added_on"], "2026-01-01")

    def test_removed_items_show_their_date_read_only(self):
        conn = db.get_connection(self.db_path)
        pantry.add_item(conn, "flour", added_on="2026-09-13")
        item_id = pantry.list_items(conn)[0]["id"]
        pantry.set_active(conn, item_id, False)
        conn.close()

        body = self.client.get("/pantry").get_data(as_text=True)

        self.assertIn('<span class="pantry-date-note">Added Sep 13, 2026</span>', body)
        self.assertNotIn('action="/pantry/added-on"', body)


class TestAisleDropdown(PantryRouteTestCase):
    """Aisle fields get the store-aisle dropdown from static/aisle-picker.js, which
    reads the page's aisle list; both pages must load it and carry every aisle."""

    def _assert_dropdown(self, body):
        self.assertIn("aisle-picker.js", body)
        self.assertIn('list="aisle-suggestions"', body)
        self.assertIn('<datalist id="aisle-suggestions">', body)
        for aisle in app_module.DEFAULT_AISLES:
            self.assertIn('<option value="%s">' % aisle.replace("&", "&amp;"), body)

    def test_pantry_aisle_fields_offer_every_aisle(self):
        self._assert_dropdown(self.client.get("/pantry").get_data(as_text=True))

    def test_shopping_list_aisle_fields_offer_every_aisle(self):
        self._assert_dropdown(self.client.get("/shopping-list").get_data(as_text=True))
