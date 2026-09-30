import tempfile
import unittest
from datetime import date
from pathlib import Path

import db
import pantry


class PantryTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp_dir = tempfile.TemporaryDirectory()
        self.db_path = Path(self.tmp_dir.name) / "mealplanner.db"
        self.conn = db.get_connection(self.db_path)

    def tearDown(self):
        self.conn.close()
        self.tmp_dir.cleanup()


class TestAddItem(PantryTestCase):
    def test_new_name_is_added_and_returns_true(self):
        result = pantry.add_item(self.conn, "flour")
        self.assertTrue(result)
        names = [row["name"] for row in pantry.list_items(self.conn)]
        self.assertEqual(names, ["flour"])

    def test_strips_whitespace(self):
        pantry.add_item(self.conn, "  flour  ")
        names = [row["name"] for row in pantry.list_items(self.conn)]
        self.assertEqual(names, ["flour"])

    def test_duplicate_same_case_returns_false_and_does_not_duplicate(self):
        pantry.add_item(self.conn, "flour")
        result = pantry.add_item(self.conn, "flour")
        self.assertFalse(result)
        self.assertEqual(len(pantry.list_items(self.conn)), 1)

    def test_duplicate_different_case_returns_false_and_does_not_duplicate(self):
        pantry.add_item(self.conn, "flour")
        result = pantry.add_item(self.conn, "Flour")
        self.assertFalse(result)
        self.assertEqual(len(pantry.list_items(self.conn)), 1)

    def test_empty_name_raises(self):
        with self.assertRaises(ValueError):
            pantry.add_item(self.conn, "")

    def test_whitespace_only_name_raises(self):
        with self.assertRaises(ValueError):
            pantry.add_item(self.conn, "   ")


class TestListItems(PantryTestCase):
    def test_returns_names_in_case_insensitive_alphabetical_order(self):
        pantry.add_item(self.conn, "Olive Oil")
        pantry.add_item(self.conn, "flour")
        pantry.add_item(self.conn, "Bananas")

        names = [row["name"] for row in pantry.list_items(self.conn)]

        self.assertEqual(names, ["Bananas", "flour", "Olive Oil"])

    def test_empty_pantry_returns_empty_list(self):
        self.assertEqual(pantry.list_items(self.conn), [])

    def test_new_item_defaults_to_partial_match(self):
        pantry.add_item(self.conn, "flour")
        self.assertEqual(pantry.list_items(self.conn)[0]["exact_match"], 0)

    def test_new_item_without_aisle_has_none_aisle(self):
        pantry.add_item(self.conn, "flour")
        self.assertIsNone(pantry.list_items(self.conn)[0]["aisle"])

    def test_new_item_stores_given_aisle(self):
        pantry.add_item(self.conn, "flour", "Dry Goods & Pasta")
        self.assertEqual(pantry.list_items(self.conn)[0]["aisle"], "Dry Goods & Pasta")

    def test_blank_aisle_is_stored_as_none(self):
        pantry.add_item(self.conn, "flour", "   ")
        self.assertIsNone(pantry.list_items(self.conn)[0]["aisle"])

    def test_new_item_defaults_to_active(self):
        pantry.add_item(self.conn, "flour")
        self.assertEqual(pantry.list_items(self.conn)[0]["active"], 1)


class TestToggleExactMatch(PantryTestCase):
    def test_toggling_flips_exact_match_flag(self):
        pantry.add_item(self.conn, "egg")
        item_id = pantry.list_items(self.conn)[0]["id"]

        pantry.toggle_exact_match(self.conn, item_id)
        self.assertEqual(pantry.list_items(self.conn)[0]["exact_match"], 1)

        pantry.toggle_exact_match(self.conn, item_id)
        self.assertEqual(pantry.list_items(self.conn)[0]["exact_match"], 0)

    def test_toggling_nonexistent_id_does_not_raise(self):
        pantry.toggle_exact_match(self.conn, 999999)  # must not raise


class TestSetAisle(PantryTestCase):
    def test_sets_aisle_on_existing_item(self):
        pantry.add_item(self.conn, "egg")
        item_id = pantry.list_items(self.conn)[0]["id"]

        pantry.set_aisle(self.conn, item_id, "Dairy & Eggs")

        self.assertEqual(pantry.list_items(self.conn)[0]["aisle"], "Dairy & Eggs")

    def test_blank_aisle_clears_it_back_to_none(self):
        pantry.add_item(self.conn, "egg", "Dairy & Eggs")
        item_id = pantry.list_items(self.conn)[0]["id"]

        pantry.set_aisle(self.conn, item_id, "  ")

        self.assertIsNone(pantry.list_items(self.conn)[0]["aisle"])

    def test_setting_aisle_on_nonexistent_id_does_not_raise(self):
        pantry.set_aisle(self.conn, 999999, "Produce")  # must not raise


class TestDeleteItem(PantryTestCase):
    def test_deleting_removes_the_row_entirely(self):
        pantry.add_item(self.conn, "flour")
        item_id = pantry.list_items(self.conn)[0]["id"]

        pantry.delete_item(self.conn, item_id)

        self.assertEqual(pantry.list_items(self.conn), [])

    def test_deleting_leaves_other_items_alone(self):
        pantry.add_item(self.conn, "flour")
        pantry.add_item(self.conn, "sugar")
        flour_id = next(
            row["id"] for row in pantry.list_items(self.conn) if row["name"] == "flour"
        )

        pantry.delete_item(self.conn, flour_id)

        names = [row["name"] for row in pantry.list_items(self.conn)]
        self.assertEqual(names, ["sugar"])

    def test_deleted_name_can_be_added_again(self):
        # "name" is UNIQUE, so a re-add only succeeds if the row really went
        # away rather than being hidden the way deactivating hides it.
        pantry.add_item(self.conn, "flour")
        item_id = pantry.list_items(self.conn)[0]["id"]
        pantry.delete_item(self.conn, item_id)

        self.assertTrue(pantry.add_item(self.conn, "flour"))

    def test_deleting_an_inactive_item_works(self):
        pantry.add_item(self.conn, "flour")
        item_id = pantry.list_items(self.conn)[0]["id"]
        pantry.set_active(self.conn, item_id, False)

        pantry.delete_item(self.conn, item_id)

        self.assertEqual(pantry.list_items(self.conn), [])

    def test_deleting_nonexistent_id_does_not_raise(self):
        pantry.delete_item(self.conn, 999999)  # must not raise


class TestSetActive(PantryTestCase):
    def test_deactivating_existing_item(self):
        pantry.add_item(self.conn, "flour")
        item_id = pantry.list_items(self.conn)[0]["id"]

        pantry.set_active(self.conn, item_id, False)

        self.assertEqual(pantry.list_items(self.conn)[0]["active"], 0)

    def test_reactivating_a_deactivated_item(self):
        pantry.add_item(self.conn, "flour")
        item_id = pantry.list_items(self.conn)[0]["id"]
        pantry.set_active(self.conn, item_id, False)

        pantry.set_active(self.conn, item_id, True)

        self.assertEqual(pantry.list_items(self.conn)[0]["active"], 1)

    def test_setting_active_on_nonexistent_id_does_not_raise(self):
        pantry.set_active(self.conn, 999999, False)  # must not raise


if __name__ == "__main__":
    unittest.main()


class TestAddedOn(PantryTestCase):
    def _added_on(self, name):
        return self.conn.execute(
            'SELECT "added_on" FROM "pantry_item" WHERE "name" = ?', (name,)
        ).fetchone()["added_on"]

    def test_new_item_is_stamped_with_today(self):
        pantry.add_item(self.conn, "flour")
        self.assertEqual(self._added_on("flour"), date.today().isoformat())

    def test_explicit_added_on_is_stored(self):
        pantry.add_item(self.conn, "flour", added_on="2026-01-05")
        self.assertEqual(self._added_on("flour"), "2026-01-05")

    def test_list_items_exposes_added_on(self):
        pantry.add_item(self.conn, "flour", added_on="2026-01-05")
        self.assertEqual(pantry.list_items(self.conn)[0]["added_on"], "2026-01-05")

    def test_set_added_on_changes_the_date(self):
        pantry.add_item(self.conn, "flour")
        item_id = pantry.list_items(self.conn)[0]["id"]

        pantry.set_added_on(self.conn, item_id, "2025-12-24")

        self.assertEqual(self._added_on("flour"), "2025-12-24")

    def test_blank_clears_the_date_and_junk_is_rejected(self):
        pantry.add_item(self.conn, "flour")
        item_id = pantry.list_items(self.conn)[0]["id"]

        pantry.set_added_on(self.conn, item_id, "  ")
        self.assertIsNone(self._added_on("flour"))

        with self.assertRaises(ValueError):
            pantry.set_added_on(self.conn, item_id, "yesterday")
        with self.assertRaises(ValueError):
            pantry.set_added_on(self.conn, item_id, "2026-13-40")

    def test_putting_an_item_back_restamps_the_date_but_removing_does_not(self):
        pantry.add_item(self.conn, "flour", added_on="2026-01-05")
        item_id = pantry.list_items(self.conn)[0]["id"]

        pantry.set_active(self.conn, item_id, False)
        self.assertEqual(self._added_on("flour"), "2026-01-05")

        pantry.set_active(self.conn, item_id, True)
        self.assertEqual(self._added_on("flour"), date.today().isoformat())

    def test_reactivating_an_already_active_item_keeps_its_date(self):
        pantry.add_item(self.conn, "flour", added_on="2026-01-05")
        item_id = pantry.list_items(self.conn)[0]["id"]

        pantry.set_active(self.conn, item_id, True)

        self.assertEqual(self._added_on("flour"), "2026-01-05")

    def test_existing_database_without_the_column_is_migrated(self):
        import sqlite3
        legacy_path = Path(self.tmp_dir.name) / "legacy.db"
        raw = sqlite3.connect(legacy_path)
        raw.executescript(
            'CREATE TABLE "pantry_item" ("id" INTEGER PRIMARY KEY, '
            '"name" TEXT UNIQUE NOT NULL COLLATE NOCASE, "exact_match" INTEGER NOT NULL DEFAULT 0, '
            '"aisle" TEXT, "active" INTEGER NOT NULL DEFAULT 1);'
            'INSERT INTO "pantry_item" ("name") VALUES ("old salt");'
        )
        raw.commit(); raw.close()

        conn = db.get_connection(legacy_path)
        try:
            rows = pantry.list_items(conn)
            self.assertEqual([(r["name"], r["added_on"]) for r in rows], [("old salt", None)])
            pantry.add_item(conn, "new pepper", added_on="2026-02-02")
            self.assertEqual(
                {r["name"]: r["added_on"] for r in pantry.list_items(conn)},
                {"old salt": None, "new pepper": "2026-02-02"},
            )
        finally:
            conn.close()
