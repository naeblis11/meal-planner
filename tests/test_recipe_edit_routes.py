import json
import shutil
import tempfile
import unittest
from pathlib import Path

import app as app_module
import db
import recipe_sync


class EditRouteTestCase(unittest.TestCase):
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

    def _write_recipe(self, name, yaml_body):
        (self.recipes_dir / f"{name}.yaml").write_text(yaml_body, encoding="utf-8")
        recipe_sync.sync_recipes(self.recipes_dir, self.db_path)
        conn = db.get_connection(self.db_path)
        try:
            return conn.execute(
                "SELECT id FROM recipe WHERE file_path = ?", (f"{name}.yaml",)
            ).fetchone()["id"]
        finally:
            conn.close()

    @staticmethod
    def _editor(*rows):
        """Build an editor submission.

        Each row is ("section", key, name) or
        ("ingredient", key, name, amount, unit); order is the row order, and
        an ingredient belongs to the most recent section row above it.
        """
        data = {"row_order": ",".join(row[1] for row in rows)}
        for row in rows:
            kind, key, name = row[0], row[1], row[2]
            data[f"row_kind_{key}"] = kind
            data[f"row_name_{key}"] = name
            if kind == "ingredient":
                data[f"row_amount_{key}"] = row[3] if len(row) > 3 else ""
                data[f"row_unit_{key}"] = row[4] if len(row) > 4 else ""
        return data

    def _ingredients(self, recipe_id):
        conn = db.get_connection(self.db_path)
        try:
            return conn.execute(
                'SELECT "name", "amount", "unit", "section" FROM "recipe_ingredient" '
                'WHERE "recipe_id" = ? ORDER BY "order_num"',
                (recipe_id,),
            ).fetchall()
        finally:
            conn.close()


class TestRecipeEditView(EditRouteTestCase):
    def test_shows_each_ingredient_as_a_draggable_row(self):
        recipe_id = self._write_recipe("test", (
            "recipe_name: Test\nsteps: [{step: cook}]\n"
            "ingredients:\n"
            "- Flour:\n"
            "    amounts: [{amount: '2', unit: cups}]\n"
            "- Butter:\n"
            "    amounts: [{amount: '1', unit: tbsp}]\n"
            "    section: Sauce\n"
        ))

        response = self.client.get(f"/recipes/{recipe_id}/edit")

        body = response.get_data(as_text=True)
        self.assertEqual(response.status_code, 200)
        self.assertIn("Flour", body)
        self.assertIn('name="row_name_e0"', body)
        self.assertIn('name="row_name_e1"', body)
        self.assertIn('class="ing-handle"', body)

    def test_a_section_is_rendered_as_its_own_row_above_its_ingredients(self):
        recipe_id = self._write_recipe("test", (
            "recipe_name: Test\nsteps: [{step: cook}]\n"
            "ingredients:\n"
            "- Flour:\n"
            "    amounts: [{amount: '2', unit: cups}]\n"
            "- Butter:\n"
            "    amounts: [{amount: '1', unit: tbsp}]\n"
            "    section: Sauce\n"
        ))

        body = self.client.get(f"/recipes/{recipe_id}/edit").get_data(as_text=True)

        self.assertIn('value="Sauce"', body)
        # The section row is ordered between the two ingredients, which is what
        # makes "everything below this heading is in it" true.
        order = body[body.index('name="row_order"'):]
        order = order[order.index('value="') + 7:]
        order = order[:order.index('"')]
        self.assertEqual(order.split(",")[0], "e0")
        self.assertEqual(order.split(",")[-1], "e1")
        self.assertEqual(len(order.split(",")), 3)

    def test_404_for_missing_recipe(self):
        response = self.client.get("/recipes/999999/edit")
        self.assertEqual(response.status_code, 404)

    def test_multi_amount_ingredient_shown_read_only(self):
        recipe_id = self._write_recipe("test", (
            "recipe_name: Test\nsteps: [{step: cook}]\n"
            "ingredients:\n"
            "- Apple:\n"
            "    amounts: [{amount: '4', unit: each}, {amount: '10', unit: each}]\n"
        ))

        response = self.client.get(f"/recipes/{recipe_id}/edit")

        body = response.get_data(as_text=True)
        self.assertNotIn('name="row_amount_e0"', body)
        self.assertIn("Apple", body)
        self.assertIn("Multiple amounts", body)

    def test_shows_servings_fields_prefilled_with_current_yields(self):
        recipe_id = self._write_recipe("test", (
            "recipe_name: Test\nsteps: [{step: cook}]\n"
            "ingredients: [{Flour: {amounts: [{amount: '2', unit: cups}]}}]\n"
            "yields: [{amount: 4, unit: servings}]\n"
        ))

        response = self.client.get(f"/recipes/{recipe_id}/edit")

        body = response.get_data(as_text=True)
        self.assertIn('name="servings_amount" value="4"', body)
        self.assertIn('name="servings_unit" value="servings"', body)

    def test_servings_fields_default_blank_when_no_yields_set(self):
        recipe_id = self._write_recipe("test", (
            "recipe_name: Test\nsteps: [{step: cook}]\n"
            "ingredients: [{Flour: {amounts: [{amount: '2', unit: cups}]}}]\n"
        ))

        response = self.client.get(f"/recipes/{recipe_id}/edit")

        body = response.get_data(as_text=True)
        self.assertIn('name="servings_amount" value=""', body)
        self.assertIn('name="servings_unit" value="servings"', body)

    def test_shows_title_details_and_every_step_as_an_editable_row(self):
        recipe_id = self._write_recipe("test", (
            "recipe_name: Test Soup\n"
            "category: Soups\nsubcategory: Chicken\n"
            "author: Jane Doe\nsource_url: https://example.com/soup\n"
            "oven_temp: [{amount: 350, unit: F}]\noven_time: 45 minutes\n"
            "notes: [Freezes well, Double it]\n"
            "steps:\n- step: Boil water.\n- step: Add noodles.\n"
            "ingredients: [{Flour: {amounts: [{amount: '2', unit: cups}]}}]\n"
        ))

        body = self.client.get(f"/recipes/{recipe_id}/edit").get_data(as_text=True)

        self.assertIn('name="recipe_name" value="Test Soup"', body)
        self.assertIn('name="category" value="Soups"', body)
        self.assertIn('name="subcategory" value="Chicken"', body)
        self.assertIn('name="author" value="Jane Doe"', body)
        self.assertIn('name="source_url" value="https://example.com/soup"', body)
        self.assertIn('name="oven_temp_amount" value="350"', body)
        self.assertIn('name="oven_time" value="45 minutes"', body)
        self.assertIn("Freezes well\nDouble it</textarea>", body)
        self.assertIn('name="step_text_e0"', body)
        self.assertIn("Boil water.</textarea>", body)
        self.assertIn('name="step_text_e1"', body)
        self.assertIn("Add noodles.</textarea>", body)
        self.assertIn('name="step_order" class="js-step-order" value="e0,e1"', body)

    def test_saving_servings_from_edit_page_redirects_back_to_edit_page(self):
        recipe_id = self._write_recipe("test", (
            "recipe_name: Test\nsteps: [{step: cook}]\n"
            "ingredients: [{Flour: {amounts: [{amount: '2', unit: cups}]}}]\n"
            "yields: [{amount: 4, unit: servings}]\n"
        ))

        response = self.client.post(
            f"/recipes/{recipe_id}/servings",
            data={"amount": "6", "unit": "servings", "next": "edit"},
        )

        self.assertEqual(response.status_code, 302)
        self.assertIn(f"/recipes/{recipe_id}/edit", response.headers["Location"])


class TestRecipeEditConfirm(EditRouteTestCase):
    def test_editing_amount_and_unit_converts_to_imperial(self):
        recipe_id = self._write_recipe("test", (
            "recipe_name: Test\nsteps: [{step: cook}]\n"
            "ingredients:\n"
            "- Milk:\n"
            "    amounts: [{amount: '2', unit: cups}]\n"
        ))

        self.client.post(
            f"/recipes/{recipe_id}/edit",
            data=self._editor(("ingredient", "e0", "Milk", "480", "ml")),
            follow_redirects=True,
        )

        ing = self._ingredients(recipe_id)[0]
        self.assertEqual(ing["amount"], "1")
        self.assertEqual(ing["unit"], "pt")

    def test_leaving_a_row_out_of_the_order_drops_it(self):
        recipe_id = self._write_recipe("test", (
            "recipe_name: Test\nsteps: [{step: cook}]\n"
            "ingredients:\n"
            "- Flour:\n"
            "    amounts: [{amount: '2', unit: cups}]\n"
            "- Sugar:\n"
            "    amounts: [{amount: '1', unit: cup}]\n"
        ))

        self.client.post(
            f"/recipes/{recipe_id}/edit",
            data=self._editor(("ingredient", "e0", "Flour", "2", "cups")),
            follow_redirects=True,
        )

        self.assertEqual([r["name"] for r in self._ingredients(recipe_id)], ["Flour"])

    def test_adding_a_new_ingredient_under_a_new_section(self):
        recipe_id = self._write_recipe("test", (
            "recipe_name: Test\nsteps: [{step: cook}]\n"
            "ingredients:\n"
            "- Flour:\n"
            "    amounts: [{amount: '2', unit: cups}]\n"
        ))

        self.client.post(
            f"/recipes/{recipe_id}/edit",
            data=self._editor(
                ("ingredient", "e0", "Flour", "2", "cups"),
                ("section", "n0", "Sauce"),
                ("ingredient", "n1", "Butter", "1", "tbsp"),
            ),
            follow_redirects=True,
        )

        butter = next(r for r in self._ingredients(recipe_id) if r["name"] == "Butter")
        self.assertEqual(butter["amount"], "1")
        self.assertEqual(butter["unit"], "tbsp")
        self.assertEqual(butter["section"], "Sauce")

    def test_an_ingredient_above_every_section_has_no_section(self):
        recipe_id = self._write_recipe("test", (
            "recipe_name: Test\nsteps: [{step: cook}]\n"
            "ingredients:\n"
            "- Flour:\n"
            "    amounts: [{amount: '2', unit: cups}]\n"
        ))

        self.client.post(
            f"/recipes/{recipe_id}/edit",
            data=self._editor(
                ("ingredient", "e0", "Flour", "2", "cups"),
                ("section", "n0", "Sauce"),
                ("ingredient", "n1", "Butter", "1", "tbsp"),
            ),
            follow_redirects=True,
        )

        flour = next(r for r in self._ingredients(recipe_id) if r["name"] == "Flour")
        self.assertIsNone(flour["section"])

    def test_dragging_an_ingredient_under_a_heading_moves_it_into_that_section(self):
        recipe_id = self._write_recipe("test", (
            "recipe_name: Test\nsteps: [{step: cook}]\n"
            "ingredients:\n"
            "- Butter:\n"
            "    amounts: [{amount: '1', unit: tbsp}]\n"
            "    section: Bechamel\n"
        ))

        self.client.post(
            f"/recipes/{recipe_id}/edit",
            data=self._editor(
                ("section", "n0", "Bolognese"),
                ("ingredient", "e0", "Butter", "1", "tbsp"),
            ),
            follow_redirects=True,
        )

        butter = self._ingredients(recipe_id)[0]
        self.assertEqual(butter["section"], "Bolognese")

    def test_dragging_an_ingredient_above_the_heading_takes_it_out_of_the_section(self):
        recipe_id = self._write_recipe("test", (
            "recipe_name: Test\nsteps: [{step: cook}]\n"
            "ingredients:\n"
            "- Butter:\n"
            "    amounts: [{amount: '1', unit: tbsp}]\n"
            "    section: Sauce\n"
        ))

        self.client.post(
            f"/recipes/{recipe_id}/edit",
            data=self._editor(
                ("ingredient", "e0", "Butter", "1", "tbsp"),
                ("section", "n0", "Sauce"),
            ),
            follow_redirects=True,
        )

        butter = self._ingredients(recipe_id)[0]
        self.assertIsNone(butter["section"])

    def test_an_empty_section_name_ends_the_section(self):
        recipe_id = self._write_recipe("test", (
            "recipe_name: Test\nsteps: [{step: cook}]\n"
            "ingredients:\n"
            "- Butter:\n"
            "    amounts: [{amount: '1', unit: tbsp}]\n"
            "    section: Sauce\n"
            "- Flour:\n"
            "    amounts: [{amount: '2', unit: cups}]\n"
            "    section: Sauce\n"
        ))

        self.client.post(
            f"/recipes/{recipe_id}/edit",
            data=self._editor(
                ("section", "n0", "Sauce"),
                ("ingredient", "e0", "Butter", "1", "tbsp"),
                ("section", "n1", ""),
                ("ingredient", "e1", "Flour", "2", "cups"),
            ),
            follow_redirects=True,
        )

        by_name = {r["name"]: r for r in self._ingredients(recipe_id)}
        self.assertEqual(by_name["Butter"]["section"], "Sauce")
        self.assertIsNone(by_name["Flour"]["section"])

    def test_the_submitted_order_is_the_saved_order(self):
        recipe_id = self._write_recipe("test", (
            "recipe_name: Test\nsteps: [{step: cook}]\n"
            "ingredients:\n"
            "- Flour:\n"
            "    amounts: [{amount: '2', unit: cups}]\n"
            "- Sugar:\n"
            "    amounts: [{amount: '1', unit: cup}]\n"
            "- Salt:\n"
            "    amounts: [{amount: '1', unit: tsp}]\n"
        ))

        self.client.post(
            f"/recipes/{recipe_id}/edit",
            data=self._editor(
                ("ingredient", "e2", "Salt", "1", "tsp"),
                ("ingredient", "e0", "Flour", "2", "cups"),
                ("ingredient", "e1", "Sugar", "1", "cup"),
            ),
            follow_redirects=True,
        )

        self.assertEqual(
            [r["name"] for r in self._ingredients(recipe_id)],
            ["Salt", "Flour", "Sugar"],
        )

    def test_reordering_survives_a_reload_of_the_edit_page(self):
        recipe_id = self._write_recipe("test", (
            "recipe_name: Test\nsteps: [{step: cook}]\n"
            "ingredients:\n"
            "- Flour:\n"
            "    amounts: [{amount: '2', unit: cups}]\n"
            "- Sugar:\n"
            "    amounts: [{amount: '1', unit: cup}]\n"
        ))

        self.client.post(
            f"/recipes/{recipe_id}/edit",
            data=self._editor(
                ("ingredient", "e1", "Sugar", "1", "cup"),
                ("ingredient", "e0", "Flour", "2", "cups"),
            ),
            follow_redirects=True,
        )

        body = self.client.get(f"/recipes/{recipe_id}/edit").get_data(as_text=True)
        self.assertLess(body.index("Sugar"), body.index("Flour"))

    def test_saving_down_to_zero_ingredients_is_rejected(self):
        recipe_id = self._write_recipe("test", (
            "recipe_name: Test\nsteps: [{step: cook}]\n"
            "ingredients:\n"
            "- Flour:\n"
            "    amounts: [{amount: '2', unit: cups}]\n"
        ))

        response = self.client.post(
            f"/recipes/{recipe_id}/edit",
            data={"row_order": ""},
            follow_redirects=True,
        )

        self.assertIn("at least one ingredient", response.get_data(as_text=True).lower())
        self.assertEqual([r["name"] for r in self._ingredients(recipe_id)], ["Flour"])

    def test_a_section_with_no_ingredients_under_it_is_rejected_as_empty(self):
        recipe_id = self._write_recipe("test", (
            "recipe_name: Test\nsteps: [{step: cook}]\n"
            "ingredients:\n"
            "- Flour:\n"
            "    amounts: [{amount: '2', unit: cups}]\n"
        ))

        response = self.client.post(
            f"/recipes/{recipe_id}/edit",
            data=self._editor(("section", "n0", "Sauce")),
            follow_redirects=True,
        )

        self.assertIn("at least one ingredient", response.get_data(as_text=True).lower())
        self.assertEqual([r["name"] for r in self._ingredients(recipe_id)], ["Flour"])

    def test_multi_amount_ingredient_survives_unchanged(self):
        recipe_id = self._write_recipe("test", (
            "recipe_name: Test\nsteps: [{step: cook}]\n"
            "ingredients:\n"
            "- Apple:\n"
            "    amounts: [{amount: 4, unit: each}, {amount: 10, unit: each}]\n"
            "- Flour:\n"
            "    amounts: [{amount: '2', unit: cups}]\n"
        ))

        self.client.post(
            f"/recipes/{recipe_id}/edit",
            data=self._editor(
                ("ingredient", "e0", "Apple"),
                ("ingredient", "e1", "Flour", "3", "cups"),
            ),
            follow_redirects=True,
        )

        conn = db.get_connection(self.db_path)
        try:
            apple = conn.execute(
                "SELECT amounts_json FROM recipe_ingredient WHERE name = 'Apple'"
            ).fetchone()
        finally:
            conn.close()
        self.assertEqual(
            json.loads(apple["amounts_json"]),
            [{"amount": 4, "unit": "each"}, {"amount": 10, "unit": "each"}],
        )

    def test_multi_amount_ingredient_can_still_be_moved_into_a_section(self):
        recipe_id = self._write_recipe("test", (
            "recipe_name: Test\nsteps: [{step: cook}]\n"
            "ingredients:\n"
            "- Apple:\n"
            "    amounts: [{amount: 4, unit: each}, {amount: 10, unit: each}]\n"
        ))

        self.client.post(
            f"/recipes/{recipe_id}/edit",
            data=self._editor(
                ("section", "n0", "Filling"),
                ("ingredient", "e0", "Apple"),
            ),
            follow_redirects=True,
        )

        apple = self._ingredients(recipe_id)[0]
        self.assertEqual(apple["section"], "Filling")

    def test_404_for_missing_recipe(self):
        response = self.client.post("/recipes/999999/edit", data={})
        self.assertEqual(response.status_code, 404)

    def test_saving_with_no_changes_preserves_usda_num_and_notes(self):
        recipe_id = self._write_recipe("test", (
            "recipe_name: Test\nsteps: [{step: cook}]\n"
            "ingredients:\n"
            "- Salt:\n"
            "    usda_num: '02047'\n"
            "    amounts: [{amount: '1', unit: tsp}]\n"
            "    notes: [to taste]\n"
        ))

        self.client.post(
            f"/recipes/{recipe_id}/edit",
            data=self._editor(("ingredient", "e0", "Salt", "1", "tsp")),
            follow_redirects=True,
        )

        conn = db.get_connection(self.db_path)
        try:
            row = conn.execute(
                "SELECT usda_num, ingredient_notes_json FROM recipe_ingredient WHERE name = 'Salt'"
            ).fetchone()
        finally:
            conn.close()
        self.assertEqual(row["usda_num"], "02047")
        self.assertEqual(row["ingredient_notes_json"], '["to taste"]')

    def test_renaming_an_ingredient_drops_its_stale_usda_num(self):
        recipe_id = self._write_recipe("test", (
            "recipe_name: Test\nsteps: [{step: cook}]\n"
            "ingredients:\n"
            "- Flour:\n"
            "    usda_num: '20581'\n"
            "    amounts: [{amount: '2', unit: cups}]\n"
        ))

        self.client.post(
            f"/recipes/{recipe_id}/edit",
            data=self._editor(("ingredient", "e0", "Cornstarch", "2", "cups")),
            follow_redirects=True,
        )

        conn = db.get_connection(self.db_path)
        try:
            row = conn.execute(
                "SELECT usda_num FROM recipe_ingredient WHERE name = 'Cornstarch'"
            ).fetchone()
        finally:
            conn.close()
        self.assertIsNone(row["usda_num"])

    def test_an_unknown_row_key_is_ignored_not_a_500(self):
        recipe_id = self._write_recipe("test", (
            "recipe_name: Test\nsteps: [{step: cook}]\n"
            "ingredients:\n"
            "- Flour:\n"
            "    amounts: [{amount: '2', unit: cups}]\n"
        ))

        data = self._editor(("ingredient", "e0", "Flour", "2", "cups"))
        data["row_order"] = data["row_order"] + ",e99,bogus"

        response = self.client.post(
            f"/recipes/{recipe_id}/edit", data=data, follow_redirects=True
        )

        self.assertEqual(response.status_code, 200)
        self.assertEqual([r["name"] for r in self._ingredients(recipe_id)], ["Flour"])


class TestRecipeEditWithMmfSections(EditRouteTestCase):
    def test_mmf_imported_recipe_with_sections_can_be_edited_and_moved(self):
        import meal_master
        mmf_text = (Path(__file__).parent / "fixtures" / "mealmaster" / "sections-only.mmf").read_text(encoding="utf-8")
        result = meal_master.parse_meal_master(mmf_text)
        yaml_text = result.recipes[0]["yaml_text"]
        (self.recipes_dir / "sections.yaml").write_text(yaml_text, encoding="utf-8")
        recipe_sync.sync_recipes(self.recipes_dir, self.db_path)
        conn = db.get_connection(self.db_path)
        try:
            recipe_id = conn.execute(
                "SELECT id FROM recipe WHERE file_path = ?", ("sections.yaml",)
            ).fetchone()["id"]
        finally:
            conn.close()

        review = self.client.get(f"/recipes/{recipe_id}/edit").get_data(as_text=True)
        self.assertIn("Sauce One", review)
        self.assertIn("Sauce Two", review)

        # Collapse both sauces into one by putting every ingredient under a
        # single heading.
        self.client.post(
            f"/recipes/{recipe_id}/edit",
            data=self._editor(
                ("section", "n0", "Sauce One"),
                ("ingredient", "e0", "Butter", "2", ""),
                ("ingredient", "e1", "Salt", "1/2", "ts"),
                ("ingredient", "e2", "Onion", "1", "sm"),
            ),
            follow_redirects=True,
        )

        detail = self.client.get(f"/recipes/{recipe_id}").get_data(as_text=True)
        self.assertEqual(detail.count("<h4>Sauce One</h4>"), 1)
        self.assertNotIn("Sauce Two", detail)


class TestRecipeEditDetailsAndSteps(EditRouteTestCase):
    RECIPE = (
        "recipe_name: Test Soup\n"
        "recipe_uuid: 11111111-1111-1111-1111-111111111111\n"
        "category: Soups\n"
        "steps:\n"
        "- step: Boil water. Add noodles. Simmer ten minutes.\n"
        "  notes: [Salt the water]\n"
        "- step: Serve.\n"
        "ingredients: [{Flour: {amounts: [{amount: '2', unit: cups}]}}]\n"
    )

    def _yaml(self, name="test"):
        import yaml
        return yaml.safe_load((self.recipes_dir / f"{name}.yaml").read_text(encoding="utf-8"))

    def _recipe_row(self, recipe_id):
        conn = db.get_connection(self.db_path)
        try:
            return conn.execute("SELECT * FROM recipe WHERE id = ?", (recipe_id,)).fetchone()
        finally:
            conn.close()

    def _steps(self, recipe_id):
        conn = db.get_connection(self.db_path)
        try:
            return [
                r["step_text"] for r in conn.execute(
                    'SELECT "step_text" FROM "recipe_step" WHERE "recipe_id" = ? ORDER BY "order_num"',
                    (recipe_id,),
                ).fetchall()
            ]
        finally:
            conn.close()

    @staticmethod
    def _steps_form(*pairs):
        data = {"step_order": ",".join(key for key, _ in pairs)}
        for key, text in pairs:
            data[f"step_text_{key}"] = text
        return data

    def test_renaming_the_recipe_updates_the_title_but_keeps_the_file(self):
        recipe_id = self._write_recipe("test", self.RECIPE)

        response = self.client.post(
            f"/recipes/{recipe_id}/edit", data={"recipe_name": "Chicken Noodle Soup"},
            follow_redirects=True,
        )

        self.assertIn("Recipe updated.", response.get_data(as_text=True))
        self.assertEqual(self._recipe_row(recipe_id)["name"], "Chicken Noodle Soup")
        self.assertEqual(self._yaml()["recipe_name"], "Chicken Noodle Soup")
        self.assertTrue((self.recipes_dir / "test.yaml").exists())
        # Untouched parts of the file are left alone.
        self.assertEqual(self._yaml()["category"], "Soups")
        self.assertEqual(len(self._yaml()["steps"]), 2)

    def test_a_blank_title_is_rejected(self):
        recipe_id = self._write_recipe("test", self.RECIPE)

        response = self.client.post(
            f"/recipes/{recipe_id}/edit", data={"recipe_name": "   "}, follow_redirects=True
        )

        self.assertIn("needs a title", response.get_data(as_text=True))
        self.assertEqual(self._recipe_row(recipe_id)["name"], "Test Soup")

    def test_a_title_already_used_by_another_recipe_is_rejected(self):
        recipe_id = self._write_recipe("test", self.RECIPE)
        self._write_recipe("other", (
            "recipe_name: Beef Stew\nsteps: [{step: cook}]\n"
            "ingredients: [{Beef: {amounts: [{amount: '1', unit: lb}]}}]\n"
        ))

        response = self.client.post(
            f"/recipes/{recipe_id}/edit", data={"recipe_name": "beef STEW"}, follow_redirects=True
        )

        self.assertIn("already called", response.get_data(as_text=True))
        self.assertEqual(self._recipe_row(recipe_id)["name"], "Test Soup")

    def test_re_saving_the_same_title_in_a_different_case_is_allowed(self):
        recipe_id = self._write_recipe("test", self.RECIPE)

        self.client.post(f"/recipes/{recipe_id}/edit", data={"recipe_name": "TEST SOUP"})

        self.assertEqual(self._recipe_row(recipe_id)["name"], "TEST SOUP")

    def test_details_are_written_and_blank_optional_fields_are_cleared(self):
        recipe_id = self._write_recipe("test", self.RECIPE + "author: Old Author\nnotes: [old note]\n")

        self.client.post(f"/recipes/{recipe_id}/edit", data={
            "category": "Main Dishes", "subcategory": "",
            "author": "", "source_url": "https://example.com/x",
            "oven_temp_amount": "375", "oven_temp_unit": "f", "oven_time": "1 hour",
            "notes": "Freezes well\n\n  Double it  \n",
            "servings_amount": "6", "servings_unit": "bowls",
        })

        data = self._yaml()
        self.assertEqual(data["category"], "Main Dishes")
        self.assertEqual(data["subcategory"], "None")
        self.assertNotIn("author", data)
        self.assertEqual(data["source_url"], "https://example.com/x")
        self.assertEqual(data["oven_temp"], [{"amount": 375, "unit": "F"}])
        self.assertEqual(data["oven_time"], "1 hour")
        self.assertEqual(data["notes"], ["Freezes well", "Double it"])
        self.assertEqual(data["yields"], [{"amount": 6, "unit": "bowls"}])
        row = self._recipe_row(recipe_id)
        self.assertEqual(row["category"], "Main Dishes")
        self.assertIsNone(row["author"])

    def test_blank_servings_removes_the_yield_and_a_bad_amount_is_rejected(self):
        recipe_id = self._write_recipe("test", self.RECIPE + "yields: [{amount: 4, unit: servings}]\n")

        response = self.client.post(
            f"/recipes/{recipe_id}/edit", data={"servings_amount": "lots"}, follow_redirects=True
        )
        self.assertIn("valid serving amount", response.get_data(as_text=True))
        self.assertEqual(self._yaml()["yields"], [{"amount": 4, "unit": "servings"}])

        self.client.post(f"/recipes/{recipe_id}/edit", data={"servings_amount": ""})
        self.assertNotIn("yields", self._yaml())

    def test_steps_can_be_edited_reordered_added_and_removed(self):
        recipe_id = self._write_recipe("test", self.RECIPE)

        self.client.post(f"/recipes/{recipe_id}/edit", data=self._steps_form(
            ("n0", "Gather everything."),
            ("e1", "Serve hot."),
            ("e0", "Boil water. Add noodles. Simmer ten minutes."),
        ))

        self.assertEqual(self._steps(recipe_id), [
            "Gather everything.", "Serve hot.", "Boil water. Add noodles. Simmer ten minutes.",
        ])

        self.client.post(f"/recipes/{recipe_id}/edit", data=self._steps_form(("e2", "Only step left.")))
        self.assertEqual(self._steps(recipe_id), ["Only step left."])

    def test_splitting_a_step_keeps_its_notes_on_the_first_half(self):
        recipe_id = self._write_recipe("test", self.RECIPE)

        self.client.post(f"/recipes/{recipe_id}/edit", data=self._steps_form(
            ("e0", "Boil water."),
            ("n0", "Add noodles."),
            ("n1", "Simmer ten minutes."),
            ("e1", "Serve."),
        ))

        steps = self._yaml()["steps"]
        self.assertEqual([s["step"] for s in steps], [
            "Boil water.", "Add noodles.", "Simmer ten minutes.", "Serve.",
        ])
        self.assertEqual(steps[0].get("notes"), ["Salt the water"])
        self.assertNotIn("notes", steps[1])
        self.assertNotIn("notes", steps[2])

    def test_blank_step_rows_are_dropped_and_zero_steps_is_rejected(self):
        recipe_id = self._write_recipe("test", self.RECIPE)

        self.client.post(f"/recipes/{recipe_id}/edit", data=self._steps_form(
            ("e0", "Boil water."), ("n0", "   "), ("e1", "Serve."),
        ))
        self.assertEqual(self._steps(recipe_id), ["Boil water.", "Serve."])

        response = self.client.post(
            f"/recipes/{recipe_id}/edit", data=self._steps_form(("e0", ""), ("e1", "")),
            follow_redirects=True,
        )
        self.assertIn("at least one instruction step", response.get_data(as_text=True))
        self.assertEqual(self._steps(recipe_id), ["Boil water.", "Serve."])

    def test_an_unknown_step_key_becomes_a_plain_new_step(self):
        recipe_id = self._write_recipe("test", self.RECIPE)

        self.client.post(f"/recipes/{recipe_id}/edit", data=self._steps_form(
            ("e99", "From nowhere."), ("bogus", "Also fine."),
        ))

        self.assertEqual(self._steps(recipe_id), ["From nowhere.", "Also fine."])

    def test_saving_the_whole_form_at_once_writes_every_part(self):
        recipe_id = self._write_recipe("test", self.RECIPE)

        data = {"recipe_name": "Noodle Soup", "category": "Soups", "servings_amount": "4",
                "servings_unit": "servings"}
        data.update(self._steps_form(("e0", "Boil."), ("e1", "Serve.")))
        data.update(self._editor(("ingredient", "e0", "Flour", "3", "cups"),
                                 ("ingredient", "n0", "Salt", "1", "tsp")))
        response = self.client.post(f"/recipes/{recipe_id}/edit", data=data, follow_redirects=True)

        self.assertIn("Recipe updated.", response.get_data(as_text=True))
        self.assertEqual(self._recipe_row(recipe_id)["name"], "Noodle Soup")
        self.assertEqual(self._steps(recipe_id), ["Boil.", "Serve."])
        self.assertEqual([r["name"] for r in self._ingredients(recipe_id)], ["Flour", "Salt"])
        self.assertEqual(self._yaml()["yields"], [{"amount": 4, "unit": "servings"}])


class TestIngredientNotes(EditRouteTestCase):
    RECIPE = (
        "recipe_name: Test\nsteps: [{step: cook}]\n"
        "ingredients:\n"
        "- Flour:\n"
        "    amounts: [{amount: '2', unit: cups}]\n"
        "    notes: [sifted]\n"
        "- Salt:\n"
        "    amounts: [{amount: '1', unit: tsp}]\n"
    )

    def _notes(self, recipe_id):
        conn = db.get_connection(self.db_path)
        try:
            return {
                r["name"]: json.loads(r["ingredient_notes_json"]) if r["ingredient_notes_json"] else None
                for r in conn.execute(
                    'SELECT "name", "ingredient_notes_json" FROM "recipe_ingredient" WHERE "recipe_id" = ?',
                    (recipe_id,),
                ).fetchall()
            }
        finally:
            conn.close()

    def test_edit_page_shows_an_existing_note_and_a_note_button_for_rows_without_one(self):
        recipe_id = self._write_recipe("test", self.RECIPE)

        body = self.client.get(f"/recipes/{recipe_id}/edit").get_data(as_text=True)

        self.assertIn('name="row_notes_e0" value="sifted"', body)
        self.assertIn('name="row_notes_e1" value=""', body)
        # Flour's note line is visible and its Note button hidden; Salt is the reverse.
        flour = body[body.index('data-key="e0"'):body.index('data-key="e1"')]
        salt = body[body.index('data-key="e1"'):]
        self.assertIn('class="pantry-row-btn js-add-note" title="Add a note to this ingredient (e.g. sifted, to taste)" hidden', flour)
        self.assertIn('<div class="ing-note-line">', flour)
        self.assertNotIn('js-add-note" title="Add a note to this ingredient (e.g. sifted, to taste)" hidden', salt)
        self.assertIn('<div class="ing-note-line" hidden>', salt)

    def test_notes_can_be_edited_added_and_cleared(self):
        recipe_id = self._write_recipe("test", self.RECIPE)

        data = self._editor(
            ("ingredient", "e0", "Flour", "2", "cups"),
            ("ingredient", "e1", "Salt", "1", "tsp"),
            ("ingredient", "n0", "Butter", "1", "tbsp"),
        )
        data["row_notes_e0"] = ""
        data["row_notes_e1"] = "  to taste ; or more  "
        data["row_notes_n0"] = "softened"
        self.client.post(f"/recipes/{recipe_id}/edit", data=data)

        self.assertEqual(self._notes(recipe_id), {
            "Flour": None, "Salt": ["to taste", "or more"], "Butter": ["softened"],
        })
        import yaml
        saved = yaml.safe_load((self.recipes_dir / "test.yaml").read_text(encoding="utf-8"))
        self.assertNotIn("notes", saved["ingredients"][0]["Flour"])
        self.assertEqual(saved["ingredients"][2]["Butter"]["notes"], ["softened"])

    def test_a_form_without_note_fields_leaves_notes_untouched(self):
        recipe_id = self._write_recipe("test", self.RECIPE)

        self.client.post(f"/recipes/{recipe_id}/edit", data=self._editor(
            ("ingredient", "e1", "Salt", "1", "tsp"),
            ("ingredient", "e0", "Flour", "3", "cups"),
        ))

        self.assertEqual(self._notes(recipe_id), {"Flour": ["sifted"], "Salt": None})

    def test_edited_note_shows_on_the_recipe_page(self):
        recipe_id = self._write_recipe("test", self.RECIPE)
        data = self._editor(("ingredient", "e0", "Flour", "2", "cups"))
        data["row_notes_e0"] = "sifted twice"
        self.client.post(f"/recipes/{recipe_id}/edit", data=data)

        body = self.client.get(f"/recipes/{recipe_id}").get_data(as_text=True)

        self.assertIn("<li>sifted twice</li>", body)
