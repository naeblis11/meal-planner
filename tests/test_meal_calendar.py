import sqlite3
import tempfile
import unittest
from datetime import date
from pathlib import Path

import db
import meal_calendar


def _insert_test_recipe(conn: sqlite3.Connection, name: str = "Test Recipe") -> int:
    cursor = conn.execute(
        'INSERT INTO "recipe" ("file_path", "file_mtime", "name", "raw_yaml") '
        "VALUES (?, ?, ?, ?)",
        (f"{name}.yaml", 0.0, name, f"recipe_name: {name}\n"),
    )
    conn.commit()
    return cursor.lastrowid


class TestGetWeekStart(unittest.TestCase):
    def test_mid_week_date_returns_monday(self):
        # 2026-09-03 is a Thursday
        self.assertEqual(meal_calendar.get_week_start(date(2026, 9, 3)), date(2026, 8, 31))

    def test_monday_returns_itself(self):
        self.assertEqual(meal_calendar.get_week_start(date(2026, 8, 31)), date(2026, 8, 31))


class TestGetWeekDates(unittest.TestCase):
    def test_returns_seven_consecutive_dates(self):
        dates = meal_calendar.get_week_dates(date(2026, 8, 31))
        self.assertEqual(len(dates), 7)
        self.assertEqual(dates[0], date(2026, 8, 31))
        self.assertEqual(dates[6], date(2026, 9, 6))
        for i in range(6):
            self.assertEqual((dates[i + 1] - dates[i]).days, 1)


class TestParseWeekParam(unittest.TestCase):
    def test_valid_date_string(self):
        # 2026-09-03 is a Thursday -> week start is Monday 2026-08-31
        self.assertEqual(meal_calendar.parse_week_param("2026-09-03"), date(2026, 8, 31))

    def test_none_falls_back_to_current_week(self):
        self.assertEqual(
            meal_calendar.parse_week_param(None), meal_calendar.get_week_start(date.today())
        )

    def test_empty_string_falls_back_to_current_week(self):
        self.assertEqual(
            meal_calendar.parse_week_param(""), meal_calendar.get_week_start(date.today())
        )

    def test_garbage_string_falls_back_to_current_week(self):
        self.assertEqual(
            meal_calendar.parse_week_param("not-a-date"),
            meal_calendar.get_week_start(date.today()),
        )


class MealPlanTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp_dir = tempfile.TemporaryDirectory()
        self.db_path = Path(self.tmp_dir.name) / "mealplanner.db"
        self.conn = db.get_connection(self.db_path)
        self.recipe_id = _insert_test_recipe(self.conn)

    def tearDown(self):
        self.conn.close()
        self.tmp_dir.cleanup()


class TestGetWeekPlan(MealPlanTestCase):
    def test_empty_week_returns_all_21_keys_as_none(self):
        week_start = date(2026, 8, 31)
        plan = meal_calendar.get_week_plan(self.conn, week_start)
        self.assertEqual(len(plan), 21)
        for d in meal_calendar.get_week_dates(week_start):
            for slot in meal_calendar.SLOTS:
                self.assertIsNone(plan[(d.isoformat(), slot)])

    def test_assigned_slot_shows_recipe_info(self):
        week_start = date(2026, 8, 31)
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", self.recipe_id)
        plan = meal_calendar.get_week_plan(self.conn, week_start)
        self.assertEqual(
            plan[("2026-09-01", "Dinner")],
            {
                "recipe_id": self.recipe_id,
                "recipe_name": "Test Recipe",
                "recipe_image": None,
                "servings": None,
            },
        )


class TestAssignMeal(MealPlanTestCase):
    def test_assign_then_reassign_updates_in_place(self):
        other_recipe_id = _insert_test_recipe(self.conn, "Other Recipe")
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", self.recipe_id)
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", other_recipe_id)

        rows = self.conn.execute(
            'SELECT "recipe_id" FROM "meal_plan" WHERE "date" = ? AND "slot" = ?',
            ("2026-09-01", "Dinner"),
        ).fetchall()
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["recipe_id"], other_recipe_id)

    def test_invalid_slot_raises(self):
        with self.assertRaises(ValueError):
            meal_calendar.assign_meal(self.conn, "2026-09-01", "Brunch", self.recipe_id)

    def test_nonexistent_recipe_raises(self):
        with self.assertRaises(ValueError):
            meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", 999999)

    def test_invalid_date_format_raises(self):
        with self.assertRaises(ValueError):
            meal_calendar.assign_meal(self.conn, "not-a-date", "Dinner", self.recipe_id)

    def test_non_canonical_date_format_raises(self):
        with self.assertRaises(ValueError):
            meal_calendar.assign_meal(self.conn, "20260901", "Dinner", self.recipe_id)


class TestUnassignMeal(MealPlanTestCase):
    def test_removes_assignment(self):
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", self.recipe_id)
        meal_calendar.unassign_meal(self.conn, "2026-09-01", "Dinner")
        row = self.conn.execute(
            'SELECT * FROM "meal_plan" WHERE "date" = ? AND "slot" = ?',
            ("2026-09-01", "Dinner"),
        ).fetchone()
        self.assertIsNone(row)

    def test_unassigning_empty_slot_is_not_an_error(self):
        meal_calendar.unassign_meal(self.conn, "2026-09-01", "Dinner")  # must not raise


if __name__ == "__main__":
    unittest.main()
