import sqlite3
import tempfile
import unittest
from pathlib import Path

import db


class TestInitDb(unittest.TestCase):
    def test_creates_all_tables(self):
        conn = sqlite3.connect(":memory:")
        db.init_db(conn)
        tables = {
            row[0]
            for row in conn.execute(
                "SELECT name FROM sqlite_master WHERE type='table'"
            ).fetchall()
        }
        self.assertEqual(
            tables,
            {
                "recipe", "recipe_ingredient", "recipe_step", "meal_plan",
                "pantry_item", "shopping_list_item", "ingredient_aisle", "ha_tombstone",
                "gcal_event",
            },
        )

    def test_is_idempotent(self):
        conn = sqlite3.connect(":memory:")
        db.init_db(conn)
        db.init_db(conn)  # must not raise

    def test_meal_plan_enforces_unique_date_slot(self):
        conn = sqlite3.connect(":memory:")
        db.init_db(conn)
        conn.execute(
            'INSERT INTO "meal_plan" ("date", "slot", "recipe_id") VALUES (?, ?, ?)',
            ("2026-09-01", "Dinner", 1),
        )
        conn.commit()
        with self.assertRaises(sqlite3.IntegrityError):
            conn.execute(
                'INSERT INTO "meal_plan" ("date", "slot", "recipe_id") VALUES (?, ?, ?)',
                ("2026-09-01", "Dinner", 2),
            )

    def test_fresh_pantry_item_table_has_exact_match_column(self):
        conn = sqlite3.connect(":memory:")
        db.init_db(conn)
        columns = {row[1] for row in conn.execute('PRAGMA table_info("pantry_item")').fetchall()}
        self.assertIn("exact_match", columns)

    def test_migrates_existing_pantry_item_table_missing_exact_match_column(self):
        conn = sqlite3.connect(":memory:")
        conn.execute(
            'CREATE TABLE "pantry_item" ('
            '"id" INTEGER PRIMARY KEY, "name" TEXT UNIQUE NOT NULL COLLATE NOCASE)'
        )
        conn.commit()

        db.init_db(conn)  # must not raise, must add the missing column

        columns = {row[1] for row in conn.execute('PRAGMA table_info("pantry_item")').fetchall()}
        self.assertIn("exact_match", columns)

    def test_migration_is_idempotent(self):
        conn = sqlite3.connect(":memory:")
        db.init_db(conn)
        db.init_db(conn)  # column already exists on the second call, must not raise

    def test_fresh_pantry_item_table_has_aisle_column(self):
        conn = sqlite3.connect(":memory:")
        db.init_db(conn)
        columns = {row[1] for row in conn.execute('PRAGMA table_info("pantry_item")').fetchall()}
        self.assertIn("aisle", columns)

    def test_migrates_existing_pantry_item_table_missing_aisle_column(self):
        conn = sqlite3.connect(":memory:")
        conn.execute(
            'CREATE TABLE "pantry_item" ('
            '"id" INTEGER PRIMARY KEY, "name" TEXT UNIQUE NOT NULL COLLATE NOCASE, '
            '"exact_match" INTEGER NOT NULL DEFAULT 0)'
        )
        conn.commit()

        db.init_db(conn)  # must not raise, must add the missing column

        columns = {row[1] for row in conn.execute('PRAGMA table_info("pantry_item")').fetchall()}
        self.assertIn("aisle", columns)

    def test_fresh_pantry_item_table_has_active_column(self):
        conn = sqlite3.connect(":memory:")
        db.init_db(conn)
        columns = {row[1] for row in conn.execute('PRAGMA table_info("pantry_item")').fetchall()}
        self.assertIn("active", columns)

    def test_migrates_existing_pantry_item_table_missing_active_column(self):
        conn = sqlite3.connect(":memory:")
        conn.execute(
            'CREATE TABLE "pantry_item" ('
            '"id" INTEGER PRIMARY KEY, "name" TEXT UNIQUE NOT NULL COLLATE NOCASE, '
            '"exact_match" INTEGER NOT NULL DEFAULT 0, "aisle" TEXT)'
        )
        conn.commit()

        db.init_db(conn)  # must not raise, must add the missing column

        columns = {row[1] for row in conn.execute('PRAGMA table_info("pantry_item")').fetchall()}
        self.assertIn("active", columns)

    def test_fresh_shopping_list_item_table_has_aisle_column(self):
        conn = sqlite3.connect(":memory:")
        db.init_db(conn)
        columns = {row[1] for row in conn.execute('PRAGMA table_info("shopping_list_item")').fetchall()}
        self.assertIn("aisle", columns)

    def test_migrates_existing_shopping_list_item_table_missing_aisle_column(self):
        conn = sqlite3.connect(":memory:")
        conn.execute(
            'CREATE TABLE "shopping_list_item" ('
            '"id" INTEGER PRIMARY KEY, "name" TEXT NOT NULL, "amount" TEXT, "unit" TEXT, '
            '"in_pantry" INTEGER NOT NULL DEFAULT 0, "checked" INTEGER NOT NULL DEFAULT 0)'
        )
        conn.commit()

        db.init_db(conn)  # must not raise, must add the missing column

        columns = {row[1] for row in conn.execute('PRAGMA table_info("shopping_list_item")').fetchall()}
        self.assertIn("aisle", columns)

    def test_fresh_recipe_table_has_category_and_subcategory_columns(self):
        conn = sqlite3.connect(":memory:")
        db.init_db(conn)
        columns = {row[1] for row in conn.execute('PRAGMA table_info("recipe")').fetchall()}
        self.assertIn("category", columns)
        self.assertIn("subcategory", columns)

    def test_migrates_existing_recipe_table_missing_category_columns(self):
        conn = sqlite3.connect(":memory:")
        conn.execute(
            'CREATE TABLE "recipe" ('
            '"id" INTEGER PRIMARY KEY, "file_path" TEXT UNIQUE NOT NULL, '
            '"file_mtime" REAL NOT NULL, "name" TEXT NOT NULL, "raw_yaml" TEXT NOT NULL)'
        )
        conn.commit()

        db.init_db(conn)  # must not raise, must add the missing columns

        columns = {row[1] for row in conn.execute('PRAGMA table_info("recipe")').fetchall()}
        self.assertIn("category", columns)
        self.assertIn("subcategory", columns)

    def test_fresh_recipe_ingredient_table_has_section_column(self):
        conn = sqlite3.connect(":memory:")
        db.init_db(conn)
        columns = {row[1] for row in conn.execute('PRAGMA table_info("recipe_ingredient")').fetchall()}
        self.assertIn("section", columns)

    def test_migrates_existing_recipe_ingredient_table_missing_section_column(self):
        conn = sqlite3.connect(":memory:")
        conn.execute(
            'CREATE TABLE "recipe_ingredient" ('
            '"id" INTEGER PRIMARY KEY, "recipe_id" INTEGER NOT NULL, "order_num" INTEGER NOT NULL, '
            '"name" TEXT NOT NULL, "usda_num" TEXT, "amount" TEXT, "unit" TEXT, '
            '"amounts_json" TEXT NOT NULL, "processing_json" TEXT, '
            '"ingredient_notes_json" TEXT, "substitutions_json" TEXT)'
        )
        conn.commit()

        db.init_db(conn)  # must not raise, must add the missing column

        columns = {row[1] for row in conn.execute('PRAGMA table_info("recipe_ingredient")').fetchall()}
        self.assertIn("section", columns)


class TestGetConnection(unittest.TestCase):
    def test_returns_connection_with_row_factory_and_schema(self):
        with tempfile.TemporaryDirectory() as tmp:
            db_path = Path(tmp) / "mealplanner.db"
            conn = db.get_connection(db_path)
            try:
                self.assertEqual(conn.row_factory, sqlite3.Row)
                tables = {
                    row[0]
                    for row in conn.execute(
                        "SELECT name FROM sqlite_master WHERE type='table'"
                    ).fetchall()
                }
                self.assertIn("recipe", tables)
            finally:
                conn.close()


if __name__ == "__main__":
    unittest.main()
