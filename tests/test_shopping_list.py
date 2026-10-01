import json
import tempfile
import unittest
from datetime import date
from pathlib import Path

import db
import meal_calendar
import shopping_list


def _insert_recipe(conn, name="Test Recipe", yields=None):
    cursor = conn.execute(
        'INSERT INTO "recipe" ("file_path", "file_mtime", "name", "raw_yaml", "yields_json") '
        "VALUES (?, ?, ?, ?, ?)",
        (
            f"{name}.yaml",
            0.0,
            name,
            f"recipe_name: {name}\n",
            json.dumps(yields) if yields else None,
        ),
    )
    conn.commit()
    return cursor.lastrowid


def _insert_ingredient(conn, recipe_id, name, amount=None, unit=None, order_num=0):
    conn.execute(
        """INSERT INTO "recipe_ingredient"
           ("recipe_id", "order_num", "name", "amount", "unit", "amounts_json")
           VALUES (?, ?, ?, ?, ?, ?)""",
        (recipe_id, order_num, name, amount, unit, "[]"),
    )
    conn.commit()


class ShoppingListTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp_dir = tempfile.TemporaryDirectory()
        self.db_path = Path(self.tmp_dir.name) / "mealplanner.db"
        self.conn = db.get_connection(self.db_path)

    def tearDown(self):
        self.conn.close()
        self.tmp_dir.cleanup()


class TestGenerate(ShoppingListTestCase):
    def test_single_recipe_produces_its_ingredient_lines(self):
        recipe_id = _insert_recipe(self.conn, "Banana Bread")
        _insert_ingredient(self.conn, recipe_id, "Flour", "3 1/2", "cups")
        _insert_ingredient(self.conn, recipe_id, "Baking Soda", "2", "tsp")
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", recipe_id)

        shopping_list.generate(self.conn, date(2026, 8, 31))

        names = {row["name"] for row in shopping_list.list_items(self.conn)}
        self.assertEqual(names, {"Flour", "Baking Soda"})

    def test_two_different_recipes_combine_ingredients(self):
        r1 = _insert_recipe(self.conn, "Recipe One")
        r2 = _insert_recipe(self.conn, "Recipe Two")
        _insert_ingredient(self.conn, r1, "Flour", "2", "cups")
        _insert_ingredient(self.conn, r2, "Sugar", "1", "cup")
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", r1)
        meal_calendar.assign_meal(self.conn, "2026-09-02", "Lunch", r2)

        shopping_list.generate(self.conn, date(2026, 8, 31))

        names = {row["name"] for row in shopping_list.list_items(self.conn)}
        self.assertEqual(names, {"Flour", "Sugar"})

    def test_same_recipe_assigned_twice_deduplicates_ingredient(self):
        recipe_id = _insert_recipe(self.conn, "Banana Bread")
        _insert_ingredient(self.conn, recipe_id, "Flour", "2", "cups")
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", recipe_id)
        meal_calendar.assign_meal(self.conn, "2026-09-03", "Lunch", recipe_id)

        shopping_list.generate(self.conn, date(2026, 8, 31))

        matching = [r for r in shopping_list.list_items(self.conn) if r["name"] == "Flour"]
        self.assertEqual(len(matching), 1)

    def _flour_line(self):
        return next(
            row for row in shopping_list.list_items(self.conn) if row["name"] == "Flour"
        )

    def test_planned_servings_scale_the_ingredient_amounts(self):
        recipe_id = _insert_recipe(
            self.conn, "Banana Bread", [{"amount": 3, "unit": "loaves"}]
        )
        _insert_ingredient(self.conn, recipe_id, "Flour", "3 1/2", "cups")
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", recipe_id, "6")

        shopping_list.generate(self.conn, date(2026, 8, 31))

        self.assertEqual(self._flour_line()["amount"], "7")

    def test_no_planned_servings_uses_the_recipes_own_amounts(self):
        recipe_id = _insert_recipe(
            self.conn, "Banana Bread", [{"amount": 3, "unit": "loaves"}]
        )
        _insert_ingredient(self.conn, recipe_id, "Flour", "3 1/2", "cups")
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", recipe_id)

        shopping_list.generate(self.conn, date(2026, 8, 31))

        self.assertEqual(self._flour_line()["amount"], "3 1/2")

    def test_scaling_does_not_rewrite_the_stored_recipe_amounts(self):
        recipe_id = _insert_recipe(
            self.conn, "Banana Bread", [{"amount": 3, "unit": "loaves"}]
        )
        _insert_ingredient(self.conn, recipe_id, "Flour", "3 1/2", "cups")
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", recipe_id, "6")

        shopping_list.generate(self.conn, date(2026, 8, 31))

        stored = self.conn.execute(
            'SELECT "amount" FROM "recipe_ingredient" WHERE "recipe_id" = ?', (recipe_id,)
        ).fetchone()
        self.assertEqual(stored["amount"], "3 1/2")

    def test_two_nights_at_different_sizes_add_up(self):
        recipe_id = _insert_recipe(
            self.conn, "Banana Bread", [{"amount": 3, "unit": "loaves"}]
        )
        _insert_ingredient(self.conn, recipe_id, "Flour", "3 1/2", "cups")
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", recipe_id, "6")
        meal_calendar.assign_meal(self.conn, "2026-09-02", "Lunch", recipe_id)

        shopping_list.generate(self.conn, date(2026, 8, 31))

        # 6 loaves' worth (7 cups) plus the recipe's own 3 loaves (3 1/2 cups).
        self.assertEqual(self._flour_line()["amount"], "10 1/2")

    def test_recipe_without_a_recorded_yield_is_left_unscaled(self):
        recipe_id = _insert_recipe(self.conn, "Mystery Stew")
        _insert_ingredient(self.conn, recipe_id, "Flour", "2", "cups")
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", recipe_id, "99")

        shopping_list.generate(self.conn, date(2026, 8, 31))

        self.assertEqual(self._flour_line()["amount"], "2")

    def test_amountless_ingredient_stays_amountless_when_scaled(self):
        recipe_id = _insert_recipe(
            self.conn, "Banana Bread", [{"amount": 3, "unit": "loaves"}]
        )
        _insert_ingredient(self.conn, recipe_id, "Salt", "", "")
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", recipe_id, "6")

        shopping_list.generate(self.conn, date(2026, 8, 31))

        salt = next(
            row for row in shopping_list.list_items(self.conn) if row["name"] == "Salt"
        )
        self.assertEqual(salt["amount"], "")

    def test_same_ingredient_matching_units_are_combined(self):
        r1 = _insert_recipe(self.conn, "Recipe One")
        r2 = _insert_recipe(self.conn, "Recipe Two")
        _insert_ingredient(self.conn, r1, "Flour", "2", "cups")
        _insert_ingredient(self.conn, r2, "Flour", "1", "cup")
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", r1)
        meal_calendar.assign_meal(self.conn, "2026-09-02", "Lunch", r2)

        shopping_list.generate(self.conn, date(2026, 8, 31))

        flour_items = [r for r in shopping_list.list_items(self.conn) if r["name"] == "Flour"]
        self.assertEqual(len(flour_items), 1)
        self.assertEqual(flour_items[0]["amount"], "3")
        self.assertEqual(flour_items[0]["unit"], "cups")

    def test_same_ingredient_different_but_convertible_units_are_combined(self):
        r1 = _insert_recipe(self.conn, "Recipe One")
        r2 = _insert_recipe(self.conn, "Recipe Two")
        _insert_ingredient(self.conn, r1, "Butter", "2", "tbsp")
        _insert_ingredient(self.conn, r2, "Butter", "1", "cup")
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", r1)
        meal_calendar.assign_meal(self.conn, "2026-09-02", "Lunch", r2)

        shopping_list.generate(self.conn, date(2026, 8, 31))

        butter_items = [r for r in shopping_list.list_items(self.conn) if r["name"] == "Butter"]
        self.assertEqual(len(butter_items), 1)
        self.assertEqual(butter_items[0]["amount"], "1 1/8")
        self.assertEqual(butter_items[0]["unit"], "cup")

    def test_same_ingredient_incompatible_units_stay_separate(self):
        r1 = _insert_recipe(self.conn, "Recipe One")
        r2 = _insert_recipe(self.conn, "Recipe Two")
        _insert_ingredient(self.conn, r1, "Garlic", "2", "each")
        _insert_ingredient(self.conn, r2, "Garlic", "1", "clove")
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", r1)
        meal_calendar.assign_meal(self.conn, "2026-09-02", "Lunch", r2)

        shopping_list.generate(self.conn, date(2026, 8, 31))

        garlic_items = {
            (r["amount"], r["unit"])
            for r in shopping_list.list_items(self.conn) if r["name"] == "Garlic"
        }
        self.assertEqual(garlic_items, {("2", "each"), ("1", "clove")})

    def test_pantry_match_sets_in_pantry_true(self):
        recipe_id = _insert_recipe(self.conn)
        _insert_ingredient(self.conn, recipe_id, "All Purpose Flour", "2", "cups")
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", recipe_id)
        self.conn.execute('INSERT INTO "pantry_item" ("name") VALUES (?)', ("flour",))
        self.conn.commit()

        shopping_list.generate(self.conn, date(2026, 8, 31))

        self.assertEqual(shopping_list.list_items(self.conn)[0]["in_pantry"], 1)

    def test_crossed_out_pantry_item_does_not_count_as_on_hand(self):
        recipe_id = _insert_recipe(self.conn)
        _insert_ingredient(self.conn, recipe_id, "All Purpose Flour", "2", "cups")
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", recipe_id)
        self.conn.execute('INSERT INTO "pantry_item" ("name", "active") VALUES (?, 0)', ("flour",))
        self.conn.commit()

        shopping_list.generate(self.conn, date(2026, 8, 31))

        self.assertEqual(shopping_list.list_items(self.conn)[0]["in_pantry"], 0)

    def test_no_pantry_match_sets_in_pantry_false(self):
        recipe_id = _insert_recipe(self.conn)
        _insert_ingredient(self.conn, recipe_id, "Baking Soda", "2", "tsp")
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", recipe_id)

        shopping_list.generate(self.conn, date(2026, 8, 31))

        self.assertEqual(shopping_list.list_items(self.conn)[0]["in_pantry"], 0)

    def test_substring_match_false_positive_is_accepted_behavior(self):
        recipe_id = _insert_recipe(self.conn)
        _insert_ingredient(self.conn, recipe_id, "Eggplant", "1", "each")
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", recipe_id)
        self.conn.execute('INSERT INTO "pantry_item" ("name") VALUES (?)', ("egg",))
        self.conn.commit()

        shopping_list.generate(self.conn, date(2026, 8, 31))

        # Documented, accepted false positive -- pantry "egg" matches "Eggplant" as a substring.
        self.assertEqual(shopping_list.list_items(self.conn)[0]["in_pantry"], 1)

    def test_exact_match_pantry_item_does_not_match_as_substring(self):
        recipe_id = _insert_recipe(self.conn)
        _insert_ingredient(self.conn, recipe_id, "Eggplant", "1", "each")
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", recipe_id)
        self.conn.execute(
            'INSERT INTO "pantry_item" ("name", "exact_match") VALUES (?, ?)', ("egg", 1)
        )
        self.conn.commit()

        shopping_list.generate(self.conn, date(2026, 8, 31))

        # Marking "egg" exact-match-only fixes the "Eggplant" false positive.
        self.assertEqual(shopping_list.list_items(self.conn)[0]["in_pantry"], 0)

    def test_exact_match_pantry_item_matches_identical_name_case_insensitively(self):
        recipe_id = _insert_recipe(self.conn)
        _insert_ingredient(self.conn, recipe_id, "Egg", "2", "each")
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", recipe_id)
        self.conn.execute(
            'INSERT INTO "pantry_item" ("name", "exact_match") VALUES (?, ?)', ("egg", 1)
        )
        self.conn.commit()

        shopping_list.generate(self.conn, date(2026, 8, 31))

        self.assertEqual(shopping_list.list_items(self.conn)[0]["in_pantry"], 1)

    def test_week_with_no_assigned_meals_produces_empty_list(self):
        shopping_list.generate(self.conn, date(2026, 8, 31))
        self.assertEqual(shopping_list.list_items(self.conn), [])

    def test_generating_keeps_items_already_on_the_list(self):
        r1 = _insert_recipe(self.conn, "Recipe One")
        _insert_ingredient(self.conn, r1, "Flour", "2", "cups")
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", r1)  # week of 2026-08-31
        shopping_list.generate(self.conn, date(2026, 8, 31))
        self.assertEqual(len(shopping_list.list_items(self.conn)), 1)

        r2 = _insert_recipe(self.conn, "Recipe Two")
        _insert_ingredient(self.conn, r2, "Sugar", "1", "cup")
        meal_calendar.assign_meal(self.conn, "2026-09-08", "Lunch", r2)  # week of 2026-09-07
        shopping_list.generate(self.conn, date(2026, 9, 7))

        names = {row["name"] for row in shopping_list.list_items(self.conn)}
        self.assertEqual(names, {"Flour", "Sugar"})

    def test_generating_combines_into_an_item_already_on_the_list(self):
        r1 = _insert_recipe(self.conn, "Recipe One")
        _insert_ingredient(self.conn, r1, "Flour", "2", "cups")
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", r1)
        shopping_list.generate(self.conn, date(2026, 8, 31))

        r2 = _insert_recipe(self.conn, "Recipe Two")
        _insert_ingredient(self.conn, r2, "flour", "1", "cup")
        meal_calendar.assign_meal(self.conn, "2026-09-08", "Lunch", r2)
        shopping_list.generate(self.conn, date(2026, 9, 7))

        items = shopping_list.list_items(self.conn)
        self.assertEqual(len(items), 1)
        self.assertEqual((items[0]["name"], items[0]["amount"], items[0]["unit"]), ("Flour", "3", "cups"))

    def test_generating_the_same_week_twice_adds_it_twice(self):
        # Additive by design: the list is only ever emptied by the user, so
        # generating the same week again doubles up rather than resetting.
        recipe_id = _insert_recipe(self.conn)
        _insert_ingredient(self.conn, recipe_id, "Flour", "2", "cups")
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", recipe_id)
        shopping_list.generate(self.conn, date(2026, 8, 31))
        shopping_list.generate(self.conn, date(2026, 8, 31))

        items = shopping_list.list_items(self.conn)
        self.assertEqual(len(items), 1)
        self.assertEqual((items[0]["amount"], items[0]["unit"]), ("4", "cups"))

    def test_generating_with_incompatible_units_keeps_a_separate_row(self):
        r1 = _insert_recipe(self.conn, "Recipe One")
        _insert_ingredient(self.conn, r1, "Flour", "2", "cups")
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", r1)
        shopping_list.generate(self.conn, date(2026, 8, 31))

        r2 = _insert_recipe(self.conn, "Recipe Two")
        _insert_ingredient(self.conn, r2, "Flour", "1", "lb")
        meal_calendar.assign_meal(self.conn, "2026-09-08", "Lunch", r2)
        shopping_list.generate(self.conn, date(2026, 9, 7))

        rows = sorted((r["amount"], r["unit"]) for r in shopping_list.list_items(self.conn))
        self.assertEqual(rows, [("1", "lb"), ("2", "cups")])

    def test_generating_unchecks_an_item_it_adds_to(self):
        recipe_id = _insert_recipe(self.conn)
        _insert_ingredient(self.conn, recipe_id, "Flour", "2", "cups")
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", recipe_id)
        shopping_list.generate(self.conn, date(2026, 8, 31))
        item_id = shopping_list.list_items(self.conn)[0]["id"]
        shopping_list.toggle_checked(self.conn, item_id)
        self.assertEqual(shopping_list.list_items(self.conn)[0]["checked"], 1)

        shopping_list.generate(self.conn, date(2026, 8, 31))

        item = shopping_list.list_items(self.conn)[0]
        self.assertEqual(item["id"], item_id)
        self.assertEqual(item["checked"], 0)

    def test_generating_leaves_untouched_items_checked(self):
        r1 = _insert_recipe(self.conn, "Recipe One")
        _insert_ingredient(self.conn, r1, "Flour", "2", "cups")
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", r1)
        shopping_list.generate(self.conn, date(2026, 8, 31))
        flour_id = shopping_list.list_items(self.conn)[0]["id"]
        shopping_list.toggle_checked(self.conn, flour_id)

        r2 = _insert_recipe(self.conn, "Recipe Two")
        _insert_ingredient(self.conn, r2, "Sugar", "1", "cup")
        meal_calendar.assign_meal(self.conn, "2026-09-08", "Lunch", r2)
        shopping_list.generate(self.conn, date(2026, 9, 7))

        checked = {r["name"]: r["checked"] for r in shopping_list.list_items(self.conn)}
        self.assertEqual(checked, {"Flour": 1, "Sugar": 0})

    def test_generating_refreshes_the_pantry_flag_of_an_item_it_adds_to(self):
        recipe_id = _insert_recipe(self.conn)
        _insert_ingredient(self.conn, recipe_id, "Flour", "2", "cups")
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", recipe_id)
        shopping_list.generate(self.conn, date(2026, 8, 31))
        self.assertEqual(shopping_list.list_items(self.conn)[0]["in_pantry"], 0)
        self.conn.execute('INSERT INTO "pantry_item" ("name") VALUES (?)', ("flour",))
        self.conn.commit()

        shopping_list.generate(self.conn, date(2026, 8, 31))

        self.assertEqual(shopping_list.list_items(self.conn)[0]["in_pantry"], 1)

    def test_generating_keeps_a_hand_added_item(self):
        shopping_list.add_item(self.conn, "Olive oil", "Oils")
        recipe_id = _insert_recipe(self.conn)
        _insert_ingredient(self.conn, recipe_id, "Flour", "2", "cups")
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", recipe_id)

        shopping_list.generate(self.conn, date(2026, 8, 31))

        names = {row["name"] for row in shopping_list.list_items(self.conn)}
        self.assertEqual(names, {"Olive oil", "Flour"})


    def test_generating_gives_an_amountless_hand_added_item_the_recipe_amount(self):
        shopping_list.add_item(self.conn, "Flour")
        recipe_id = _insert_recipe(self.conn)
        _insert_ingredient(self.conn, recipe_id, "flour", "2", "cups")
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", recipe_id)

        shopping_list.generate(self.conn, date(2026, 8, 31))

        items = shopping_list.list_items(self.conn)
        self.assertEqual(len(items), 1)
        self.assertEqual((items[0]["name"], items[0]["amount"], items[0]["unit"]), ("Flour", "2", "cups"))

    def test_an_amountless_item_never_loses_an_amount_the_week_adds(self):
        # The week needs flour in two units that can't be combined. The first
        # fills the hand-added amountless row; the second must not then merge
        # into that same row as if it were still amountless and overwrite it.
        shopping_list.add_item(self.conn, "Flour")
        recipe_id = _insert_recipe(self.conn)
        _insert_ingredient(self.conn, recipe_id, "flour", "2", "cups", order_num=0)
        _insert_ingredient(self.conn, recipe_id, "flour", "1", "lb", order_num=1)
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", recipe_id)

        shopping_list.generate(self.conn, date(2026, 8, 31))

        amounts = sorted((row["amount"], row["unit"]) for row in shopping_list.list_items(self.conn))
        self.assertEqual(amounts, [("1", "lb"), ("2", "cups")])


class TestListItems(ShoppingListTestCase):
    def test_need_to_buy_sorts_before_already_in_kitchen(self):
        recipe_id = _insert_recipe(self.conn)
        _insert_ingredient(self.conn, recipe_id, "Zucchini", "1", "each", order_num=0)
        _insert_ingredient(self.conn, recipe_id, "Apple", "1", "each", order_num=1)
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", recipe_id)
        self.conn.execute('INSERT INTO "pantry_item" ("name") VALUES (?)', ("apple",))
        self.conn.commit()

        shopping_list.generate(self.conn, date(2026, 8, 31))

        names_in_order = [row["name"] for row in shopping_list.list_items(self.conn)]
        self.assertEqual(names_in_order, ["Zucchini", "Apple"])


class TestSetAisle(ShoppingListTestCase):
    def test_sets_aisle_on_the_item(self):
        recipe_id = _insert_recipe(self.conn)
        _insert_ingredient(self.conn, recipe_id, "Flour", "2", "cups")
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", recipe_id)
        shopping_list.generate(self.conn, date(2026, 8, 31))
        item_id = shopping_list.list_items(self.conn)[0]["id"]

        shopping_list.set_aisle(self.conn, item_id, "Dry Goods & Pasta")

        self.assertEqual(shopping_list.list_items(self.conn)[0]["aisle"], "Dry Goods & Pasta")

    def test_aisle_is_remembered_for_the_same_ingredient_on_next_generate(self):
        recipe_id = _insert_recipe(self.conn)
        _insert_ingredient(self.conn, recipe_id, "Flour", "2", "cups")
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", recipe_id)
        shopping_list.generate(self.conn, date(2026, 8, 31))
        item_id = shopping_list.list_items(self.conn)[0]["id"]
        shopping_list.set_aisle(self.conn, item_id, "Dry Goods & Pasta")

        shopping_list.generate(self.conn, date(2026, 8, 31))  # regenerate the same week

        self.assertEqual(shopping_list.list_items(self.conn)[0]["aisle"], "Dry Goods & Pasta")

    def test_aisle_is_remembered_case_insensitively_for_the_ingredient_name(self):
        r1 = _insert_recipe(self.conn, "Recipe One")
        _insert_ingredient(self.conn, r1, "flour", "2", "cups")
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", r1)
        shopping_list.generate(self.conn, date(2026, 8, 31))
        item_id = shopping_list.list_items(self.conn)[0]["id"]
        shopping_list.set_aisle(self.conn, item_id, "Dry Goods & Pasta")

        r2 = _insert_recipe(self.conn, "Recipe Two")
        _insert_ingredient(self.conn, r2, "Flour", "1", "cup")
        meal_calendar.assign_meal(self.conn, "2026-09-08", "Lunch", r2)
        shopping_list.generate(self.conn, date(2026, 9, 7))

        self.assertEqual(shopping_list.list_items(self.conn)[0]["aisle"], "Dry Goods & Pasta")

    def test_blank_aisle_clears_the_manual_override(self):
        recipe_id = _insert_recipe(self.conn)
        _insert_ingredient(self.conn, recipe_id, "Flour", "2", "cups")
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", recipe_id)
        shopping_list.generate(self.conn, date(2026, 8, 31))
        item_id = shopping_list.list_items(self.conn)[0]["id"]
        shopping_list.set_aisle(self.conn, item_id, "Household")  # deliberately "wrong"

        shopping_list.set_aisle(self.conn, item_id, "  ")

        self.assertIsNone(shopping_list.list_items(self.conn)[0]["aisle"])

    def test_forgetting_an_override_falls_back_to_the_keyword_guess_on_regenerate(self):
        recipe_id = _insert_recipe(self.conn)
        _insert_ingredient(self.conn, recipe_id, "Flour", "2", "cups")
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", recipe_id)
        shopping_list.generate(self.conn, date(2026, 8, 31))
        item_id = shopping_list.list_items(self.conn)[0]["id"]
        shopping_list.set_aisle(self.conn, item_id, "Household")  # deliberately "wrong"
        shopping_list.set_aisle(self.conn, item_id, "  ")  # forget the override

        shopping_list.generate(self.conn, date(2026, 8, 31))

        # No override remembered any more -- falls back to the keyword guess
        # ("Flour" -> Dry Goods & Pasta), not the forgotten "Household".
        self.assertEqual(shopping_list.list_items(self.conn)[0]["aisle"], "Dry Goods & Pasta")

    def test_setting_aisle_on_nonexistent_id_does_not_raise(self):
        shopping_list.set_aisle(self.conn, 999999, "Produce")  # must not raise


class TestAisleKeywordGuess(ShoppingListTestCase):
    def test_ingredient_with_no_remembered_aisle_gets_a_keyword_guess(self):
        recipe_id = _insert_recipe(self.conn)
        _insert_ingredient(self.conn, recipe_id, "Boneless Chicken Thighs", "2", "lb")
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", recipe_id)

        shopping_list.generate(self.conn, date(2026, 8, 31))

        self.assertEqual(shopping_list.list_items(self.conn)[0]["aisle"], "Meat & Seafood")

    def test_unrecognized_ingredient_guess_is_uncategorized(self):
        recipe_id = _insert_recipe(self.conn)
        _insert_ingredient(self.conn, recipe_id, "Xylitol", "1", "tsp")
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", recipe_id)

        shopping_list.generate(self.conn, date(2026, 8, 31))

        self.assertEqual(shopping_list.list_items(self.conn)[0]["aisle"], "Uncategorized")

    def test_remembered_override_wins_over_the_keyword_guess(self):
        recipe_id = _insert_recipe(self.conn)
        _insert_ingredient(self.conn, recipe_id, "Chicken Broth", "2", "cups")
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", recipe_id)
        shopping_list.generate(self.conn, date(2026, 8, 31))
        item_id = shopping_list.list_items(self.conn)[0]["id"]
        # The keyword guesser would say "Meat & Seafood" (matches "chicken"
        # first); a manual correction to the actually-correct aisle must win.
        shopping_list.set_aisle(self.conn, item_id, "Canned & Jarred")

        shopping_list.generate(self.conn, date(2026, 8, 31))  # regenerate the same week

        self.assertEqual(shopping_list.list_items(self.conn)[0]["aisle"], "Canned & Jarred")


class TestToggleChecked(ShoppingListTestCase):
    def test_toggling_flips_checked_state(self):
        recipe_id = _insert_recipe(self.conn)
        _insert_ingredient(self.conn, recipe_id, "Flour", "2", "cups")
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", recipe_id)
        shopping_list.generate(self.conn, date(2026, 8, 31))
        item_id = shopping_list.list_items(self.conn)[0]["id"]

        shopping_list.toggle_checked(self.conn, item_id)
        self.assertEqual(shopping_list.list_items(self.conn)[0]["checked"], 1)

        shopping_list.toggle_checked(self.conn, item_id)
        self.assertEqual(shopping_list.list_items(self.conn)[0]["checked"], 0)

    def test_toggling_nonexistent_id_does_not_raise(self):
        shopping_list.toggle_checked(self.conn, 999999)  # must not raise


class TestRemoveItem(ShoppingListTestCase):
    def test_removes_the_item_from_the_current_list(self):
        recipe_id = _insert_recipe(self.conn)
        _insert_ingredient(self.conn, recipe_id, "Flour", "2", "cups")
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", recipe_id)
        shopping_list.generate(self.conn, date(2026, 8, 31))
        item_id = shopping_list.list_items(self.conn)[0]["id"]

        shopping_list.remove_item(self.conn, item_id)

        self.assertEqual(shopping_list.list_items(self.conn), [])

    def test_removing_one_item_leaves_the_others(self):
        r1 = _insert_recipe(self.conn, "Recipe One")
        r2 = _insert_recipe(self.conn, "Recipe Two")
        _insert_ingredient(self.conn, r1, "Flour", "2", "cups")
        _insert_ingredient(self.conn, r2, "Sugar", "1", "cup")
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", r1)
        meal_calendar.assign_meal(self.conn, "2026-09-02", "Lunch", r2)
        shopping_list.generate(self.conn, date(2026, 8, 31))
        flour_id = next(r["id"] for r in shopping_list.list_items(self.conn) if r["name"] == "Flour")

        shopping_list.remove_item(self.conn, flour_id)

        names = {row["name"] for row in shopping_list.list_items(self.conn)}
        self.assertEqual(names, {"Sugar"})

    def test_regenerating_after_a_removal_brings_back_a_still_current_ingredient(self):
        recipe_id = _insert_recipe(self.conn)
        _insert_ingredient(self.conn, recipe_id, "Flour", "2", "cups")
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", recipe_id)
        shopping_list.generate(self.conn, date(2026, 8, 31))
        item_id = shopping_list.list_items(self.conn)[0]["id"]
        shopping_list.remove_item(self.conn, item_id)
        self.assertEqual(shopping_list.list_items(self.conn), [])

        # Removal is a this-list-only action, not a permanent exclusion --
        # regenerating recomputes from the meal plan, which still wants Flour.
        shopping_list.generate(self.conn, date(2026, 8, 31))

        self.assertEqual(shopping_list.list_items(self.conn)[0]["name"], "Flour")

    def test_removing_nonexistent_id_does_not_raise(self):
        shopping_list.remove_item(self.conn, 999999)  # must not raise


class TestClear(ShoppingListTestCase):
    def test_clear_removes_all_items(self):
        r1 = _insert_recipe(self.conn, "Recipe One")
        r2 = _insert_recipe(self.conn, "Recipe Two")
        _insert_ingredient(self.conn, r1, "Flour", "2", "cups")
        _insert_ingredient(self.conn, r2, "Sugar", "1", "cup")
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", r1)
        meal_calendar.assign_meal(self.conn, "2026-09-02", "Lunch", r2)
        shopping_list.generate(self.conn, date(2026, 8, 31))
        self.assertEqual(len(shopping_list.list_items(self.conn)), 2)

        shopping_list.clear(self.conn)

        self.assertEqual(shopping_list.list_items(self.conn), [])

    def test_clear_on_empty_list_does_not_raise(self):
        shopping_list.clear(self.conn)  # must not raise
        self.assertEqual(shopping_list.list_items(self.conn), [])

    def test_regenerating_after_a_clear_rebuilds_from_the_meal_plan(self):
        recipe_id = _insert_recipe(self.conn)
        _insert_ingredient(self.conn, recipe_id, "Flour", "2", "cups")
        meal_calendar.assign_meal(self.conn, "2026-09-01", "Dinner", recipe_id)
        shopping_list.generate(self.conn, date(2026, 8, 31))
        shopping_list.clear(self.conn)
        self.assertEqual(shopping_list.list_items(self.conn), [])

        shopping_list.generate(self.conn, date(2026, 8, 31))

        self.assertEqual(shopping_list.list_items(self.conn)[0]["name"], "Flour")


class TestAddItem(ShoppingListTestCase):
    def test_new_item_is_added(self):
        status, item_id = shopping_list.add_item(self.conn, "Olive oil", "Oils")
        item = shopping_list.list_items(self.conn)[0]
        self.assertEqual(status, "added")
        self.assertEqual(item_id, item["id"])
        self.assertEqual((item["name"], item["amount"], item["unit"], item["aisle"]), ("Olive oil", None, None, "Oils"))

    def test_amount_and_unit_are_stored(self):
        shopping_list.add_item(self.conn, "Milk", amount="2", unit="gal")
        item = shopping_list.list_items(self.conn)[0]
        self.assertEqual((item["amount"], item["unit"]), ("2", "gal"))

    def test_same_name_with_no_amount_is_a_duplicate(self):
        _, first_id = shopping_list.add_item(self.conn, "Milk", amount="2", unit="gal")
        self.assertEqual(shopping_list.add_item(self.conn, "milk"), ("duplicate", first_id))
        self.assertEqual(len(shopping_list.list_items(self.conn)), 1)
        self.assertEqual(shopping_list.list_items(self.conn)[0]["amount"], "2")

    def test_same_name_with_an_amount_merges_and_unchecks(self):
        _, item_id = shopping_list.add_item(self.conn, "Milk", amount="2", unit="gal")
        shopping_list.toggle_checked(self.conn, item_id)

        self.assertEqual(shopping_list.add_item(self.conn, "milk", amount="1", unit="gallon"), ("merged", item_id))

        items = shopping_list.list_items(self.conn)
        self.assertEqual(len(items), 1)
        self.assertEqual((items[0]["amount"], items[0]["unit"], items[0]["checked"]), ("3", "gal", 0))

    def test_incompatible_units_add_a_second_row(self):
        _, first_id = shopping_list.add_item(self.conn, "Flour", amount="2", unit="cups")
        status, second_id = shopping_list.add_item(self.conn, "flour", amount="1", unit="lb")
        self.assertEqual(status, "added")
        self.assertNotEqual(second_id, first_id)
        self.assertEqual(len(shopping_list.list_items(self.conn)), 2)

    def test_merges_into_the_compatible_row_not_the_newest(self):
        # Two rows of the same name whose units can't combine: the amount
        # goes into whichever row it fits, and the returned id says which
        # -- a caller must not assume "the newest row of that name".
        _, cups_id = shopping_list.add_item(self.conn, "Flour", amount="2", unit="cups")
        _, lb_id = shopping_list.add_item(self.conn, "flour", amount="1", unit="lb")

        self.assertEqual(shopping_list.add_item(self.conn, "flour", amount="1", unit="cup"), ("merged", cups_id))

        rows = {row["id"]: row for row in shopping_list.list_items(self.conn)}
        self.assertEqual((rows[cups_id]["amount"], rows[cups_id]["unit"]), ("3", "cups"))
        self.assertEqual((rows[lb_id]["amount"], rows[lb_id]["unit"]), ("1", "lb"))

    def test_blank_name_raises(self):
        with self.assertRaises(ValueError):
            shopping_list.add_item(self.conn, "   ")


if __name__ == "__main__":
    unittest.main()
