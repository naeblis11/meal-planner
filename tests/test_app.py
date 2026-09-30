import io
import json
import shutil
import tempfile
import unittest
from pathlib import Path

from PIL import Image

import app as app_module
import db
import pantry
import recipe_sync

FIXTURES = Path(__file__).parent / "fixtures"


def _fake_image_bytes(color=(200, 60, 60), size=(400, 300), fmt="PNG"):
    buf = io.BytesIO()
    Image.new("RGB", size, color).save(buf, format=fmt)
    buf.seek(0)
    return buf


class AppTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp_dir = tempfile.mkdtemp()
        self.recipes_dir = Path(self.tmp_dir) / "recipes"
        self.recipes_dir.mkdir()
        shutil.copy(FIXTURES / "banana-bread.yaml", self.recipes_dir)
        shutil.copy(FIXTURES / "orf-sample-1.yaml", self.recipes_dir)
        self.db_path = Path(self.tmp_dir) / "mealplanner.db"
        recipe_sync.sync_recipes(self.recipes_dir, self.db_path)
        self.images_dir = Path(self.tmp_dir) / "recipe-images"

        self.app = app_module.create_app(self.recipes_dir, self.db_path, self.images_dir)
        self.app.config["TESTING"] = True
        self.client = self.app.test_client()

    def tearDown(self):
        shutil.rmtree(self.tmp_dir, ignore_errors=True)


class TestIndexRedirect(AppTestCase):
    def test_index_redirects_to_recipes_list(self):
        response = self.client.get("/")
        self.assertEqual(response.status_code, 302)
        self.assertIn("/recipes", response.headers["Location"])


class TestRecipesList(AppTestCase):
    def test_lists_all_recipes(self):
        response = self.client.get("/recipes")
        self.assertEqual(response.status_code, 200)
        body = response.get_data(as_text=True)
        self.assertIn("Banana Bread", body)
        self.assertIn("My Recipe", body)

    def test_search_filters_by_name(self):
        response = self.client.get("/recipes?q=bread")
        body = response.get_data(as_text=True)
        self.assertIn("Banana Bread", body)
        self.assertNotIn("My Recipe", body)

    def _add_recipe(self, file_name, yaml_body):
        (self.recipes_dir / file_name).write_text(yaml_body, encoding="utf-8")
        recipe_sync.sync_recipes(self.recipes_dir, self.db_path)

    def test_search_matches_a_recipes_category(self):
        self._add_recipe("pot-roast.yaml", (
            "recipe_name: Pot Roast\ncategory: Main Dishes\n"
            "steps: [{step: cook}]\n"
            "ingredients: [{Chuck: {amounts: [{amount: '2', unit: lb}]}}]\n"
        ))

        body = self.client.get("/recipes?q=main dishes").get_data(as_text=True)

        self.assertIn("Pot Roast", body)
        self.assertNotIn("Banana Bread", body)

    def test_search_matches_a_recipes_subcategory(self):
        self._add_recipe("pot-roast.yaml", (
            "recipe_name: Pot Roast\ncategory: Main Dishes\nsubcategory: Beef\n"
            "steps: [{step: cook}]\n"
            "ingredients: [{Chuck: {amounts: [{amount: '2', unit: lb}]}}]\n"
        ))

        body = self.client.get("/recipes?q=beef").get_data(as_text=True)

        self.assertIn("Pot Roast", body)
        self.assertNotIn("Banana Bread", body)

    def test_search_matches_an_ingredient_name(self):
        self._add_recipe("pot-roast.yaml", (
            "recipe_name: Pot Roast\nsteps: [{step: cook}]\n"
            "ingredients: [{Parsnips: {amounts: [{amount: '2', unit: lb}]}}]\n"
        ))

        body = self.client.get("/recipes?q=parsnip").get_data(as_text=True)

        self.assertIn("Pot Roast", body)
        self.assertNotIn("Banana Bread", body)

    def test_search_matches_an_ingredient_section_heading(self):
        self._add_recipe("pot-roast.yaml", (
            "recipe_name: Pot Roast\nsteps: [{step: cook}]\n"
            "ingredients:\n"
            "- Butter:\n"
            "    amounts: [{amount: '1', unit: tbsp}]\n"
            "    section: Gremolata\n"
        ))

        body = self.client.get("/recipes?q=gremolata").get_data(as_text=True)

        self.assertIn("Pot Roast", body)
        self.assertNotIn("Banana Bread", body)

    def test_a_recipe_matching_several_ways_is_listed_once(self):
        self._add_recipe("beef-stew.yaml", (
            "recipe_name: Beef Stew\ncategory: Main Dishes\nsubcategory: Beef\n"
            "steps: [{step: cook}]\n"
            "ingredients:\n"
            "- Beef shin:\n"
            "    amounts: [{amount: '2', unit: lb}]\n"
            "    section: Beef\n"
            "- Beef stock:\n"
            "    amounts: [{amount: '1', unit: qt}]\n"
        ))

        body = self.client.get("/recipes?q=beef").get_data(as_text=True)

        self.assertEqual(body.count(">Beef Stew<"), 1)

    def test_search_still_matches_the_recipe_name(self):
        body = self.client.get("/recipes?q=bread").get_data(as_text=True)

        self.assertIn("Banana Bread", body)
        self.assertNotIn("My Recipe", body)

    def test_an_ingredient_match_finds_a_recipe_its_name_would_not(self):
        # "My Recipe" is named nothing like banana, but lists one as an
        # ingredient, which is the whole point of searching the ingredients.
        body = self.client.get("/recipes?q=banana").get_data(as_text=True)

        self.assertIn("My Recipe", body)

    def test_assign_search_also_matches_an_ingredient(self):
        self._add_recipe("pot-roast.yaml", (
            "recipe_name: Pot Roast\nsteps: [{step: cook}]\n"
            "ingredients: [{Parsnips: {amounts: [{amount: '2', unit: lb}]}}]\n"
        ))

        body = self.client.get(
            "/calendar/assign?date=2026-09-01&slot=Dinner&q=parsnip"
        ).get_data(as_text=True)

        self.assertIn("Pot Roast", body)

    def test_empty_state_message_when_no_recipes(self):
        empty_dir = Path(self.tmp_dir) / "empty_recipes"
        empty_dir.mkdir()
        empty_db = Path(self.tmp_dir) / "empty.db"
        recipe_sync.sync_recipes(empty_dir, empty_db)
        empty_app = app_module.create_app(empty_dir, empty_db)
        client = empty_app.test_client()

        response = client.get("/recipes")

        self.assertIn("No recipes yet", response.get_data(as_text=True))

    def test_shows_import_form_with_file_input_and_correct_action(self):
        response = self.client.get("/recipes")
        body = response.get_data(as_text=True)
        self.assertIn('action="/recipes/import"', body)
        self.assertIn('enctype="multipart/form-data"', body)
        self.assertIn('name="recipe_file"', body)
        self.assertIn('type="file"', body)

    def test_groups_recipes_by_category_and_subcategory_in_expected_order(self):
        (self.recipes_dir / "steak.yaml").write_text(
            "recipe_name: Steak Dinner\nsteps: [{step: cook}]\ningredients: [{Steak: {}}]\n"
            "category: Main Dishes\nsubcategory: Beef\n",
            encoding="utf-8",
        )
        (self.recipes_dir / "cookies.yaml").write_text(
            "recipe_name: Cookies\nsteps: [{step: bake}]\ningredients: [{Flour: {}}]\n"
            "category: Desserts\n",
            encoding="utf-8",
        )
        (self.recipes_dir / "grandmas.yaml").write_text(
            "recipe_name: Grandma's Stew\nsteps: [{step: simmer}]\ningredients: [{Beef: {}}]\n"
            "category: Family Favorites\n",
            encoding="utf-8",
        )
        recipe_sync.sync_recipes(self.recipes_dir, self.db_path)

        response = self.client.get("/recipes")
        body = response.get_data(as_text=True)

        # Default-list categories appear in their fixed order (Main Dishes
        # before Desserts); a custom category ("Family Favorites") sorts
        # alphabetically after all default categories present; Uncategorized
        # (Banana Bread, My Recipe) comes last of all.
        main_dishes_pos = body.index("Main Dishes")
        desserts_pos = body.index("Desserts")
        family_pos = body.index("Family Favorites")
        uncategorized_pos = body.index("Uncategorized")
        self.assertLess(main_dishes_pos, desserts_pos)
        self.assertLess(desserts_pos, family_pos)
        self.assertLess(family_pos, uncategorized_pos)
        self.assertIn("Beef", body)  # subcategory header under Main Dishes
        self.assertIn("Steak Dinner", body)
        self.assertIn("Cookies", body)

    def test_recipe_with_no_subcategory_appears_directly_under_its_category(self):
        (self.recipes_dir / "cookies.yaml").write_text(
            "recipe_name: Cookies\nsteps: [{step: bake}]\ningredients: [{Flour: {}}]\n"
            "category: Desserts\n",
            encoding="utf-8",
        )
        recipe_sync.sync_recipes(self.recipes_dir, self.db_path)

        response = self.client.get("/recipes")
        body = response.get_data(as_text=True)

        self.assertIn("<h2>Desserts</h2>", body)
        self.assertIn("Cookies", body)


class TestRecipeDetail(AppTestCase):
    def test_shows_ingredients_and_steps(self):
        import db
        conn = db.get_connection(self.db_path)
        recipe_id = conn.execute(
            "SELECT id FROM recipe WHERE name = 'Banana Bread'"
        ).fetchone()[0]
        conn.close()

        response = self.client.get(f"/recipes/{recipe_id}")

        body = response.get_data(as_text=True)
        self.assertEqual(response.status_code, 200)
        self.assertIn("Banana Bread", body)
        self.assertIn("Baking Soda", body)
        self.assertIn("Preheat oven to 350F", body)

    def test_404_for_missing_recipe(self):
        response = self.client.get("/recipes/999999")
        self.assertEqual(response.status_code, 404)

    def test_renders_oven_fan_and_substitution_amount(self):
        import db
        conn = db.get_connection(self.db_path)
        recipe_id = conn.execute(
            "SELECT id FROM recipe WHERE name = 'Banana Bread'"
        ).fetchone()[0]
        conn.close()

        response = self.client.get(f"/recipes/{recipe_id}")

        body = response.get_data(as_text=True)
        self.assertEqual(response.status_code, 200)
        self.assertIn("(fan: Off)", body)
        self.assertIn("3 1/2 cups Oat Flour", body)

    def test_shows_assign_to_calendar_form_with_recipe_id_and_slots(self):
        import db
        conn = db.get_connection(self.db_path)
        recipe_id = conn.execute(
            "SELECT id FROM recipe WHERE name = 'Banana Bread'"
        ).fetchone()[0]
        conn.close()

        response = self.client.get(f"/recipes/{recipe_id}")

        body = response.get_data(as_text=True)
        self.assertIn('action="/calendar/assign"', body)
        self.assertIn(f'value="{recipe_id}"', body)
        self.assertIn("Breakfast", body)
        self.assertIn("Lunch", body)
        self.assertIn("Dinner", body)

    def test_assigning_from_recipe_detail_form_creates_the_assignment(self):
        import db
        conn = db.get_connection(self.db_path)
        recipe_id = conn.execute(
            "SELECT id FROM recipe WHERE name = 'Banana Bread'"
        ).fetchone()[0]
        conn.close()

        # Mirrors exactly what the recipe detail page's new form submits.
        response = self.client.post(
            "/calendar/assign",
            data={"date": "2026-09-01", "slot": "Dinner", "recipe_id": str(recipe_id)},
        )

        self.assertEqual(response.status_code, 302)
        conn = db.get_connection(self.db_path)
        try:
            row = conn.execute(
                'SELECT "recipe_id" FROM "meal_plan" WHERE "date" = ? AND "slot" = ?',
                ("2026-09-01", "Dinner"),
            ).fetchone()
        finally:
            conn.close()
        self.assertEqual(row["recipe_id"], recipe_id)

    def test_shows_category_form_prefilled_with_current_values(self):
        import db
        conn = db.get_connection(self.db_path)
        recipe_id = conn.execute(
            "SELECT id FROM recipe WHERE name = 'Banana Bread'"
        ).fetchone()[0]
        conn.close()

        response = self.client.get(f"/recipes/{recipe_id}")

        body = response.get_data(as_text=True)
        self.assertIn(f'action="/recipes/{recipe_id}/category"', body)
        self.assertIn("Main Dishes", body)  # a default-list suggestion present in the datalist

    def test_saving_category_updates_db_and_on_disk_file(self):
        import db
        conn = db.get_connection(self.db_path)
        recipe_id = conn.execute(
            "SELECT id FROM recipe WHERE name = 'Banana Bread'"
        ).fetchone()[0]
        conn.close()

        response = self.client.post(
            f"/recipes/{recipe_id}/category",
            data={"category": "Breads & Baking", "subcategory": ""},
        )

        self.assertEqual(response.status_code, 302)
        conn = db.get_connection(self.db_path)
        try:
            row = conn.execute(
                'SELECT "category", "subcategory" FROM "recipe" WHERE "id" = ?', (recipe_id,)
            ).fetchone()
        finally:
            conn.close()
        self.assertEqual(row["category"], "Breads & Baking")
        self.assertIsNone(row["subcategory"])

        on_disk = (self.recipes_dir / "banana-bread.yaml").read_text(encoding="utf-8")
        self.assertIn("category: Breads & Baking", on_disk)

    def test_category_route_404s_for_missing_recipe(self):
        response = self.client.post(
            "/recipes/999999/category", data={"category": "X", "subcategory": ""}
        )
        self.assertEqual(response.status_code, 404)

    def test_links_to_the_edit_screen(self):
        import db
        conn = db.get_connection(self.db_path)
        recipe_id = conn.execute(
            "SELECT id FROM recipe WHERE name = 'Banana Bread'"
        ).fetchone()[0]
        conn.close()

        response = self.client.get(f"/recipes/{recipe_id}")

        body = response.get_data(as_text=True)
        self.assertIn(f'href="/recipes/{recipe_id}/edit"', body)


class TestRecipeServings(AppTestCase):
    def test_shows_no_serving_size_message_when_yields_missing(self):
        (self.recipes_dir / "no-yields.yaml").write_text(
            "recipe_name: No Yields Recipe\nsteps: [{step: cook}]\n"
            "ingredients: [{Flour: {amounts: [{amount: '2', unit: cups}]}}]\n",
            encoding="utf-8",
        )
        recipe_sync.sync_recipes(self.recipes_dir, self.db_path)
        conn = db.get_connection(self.db_path)
        recipe_id = conn.execute(
            "SELECT id FROM recipe WHERE name = 'No Yields Recipe'"
        ).fetchone()[0]
        conn.close()

        response = self.client.get(f"/recipes/{recipe_id}")

        body = response.get_data(as_text=True)
        self.assertIn("No serving size set", body)

    def test_shows_base_servings_and_scale_form(self):
        conn = db.get_connection(self.db_path)
        recipe_id = conn.execute(
            "SELECT id FROM recipe WHERE name = 'Banana Bread'"
        ).fetchone()[0]
        conn.close()

        response = self.client.get(f"/recipes/{recipe_id}")

        body = response.get_data(as_text=True)
        self.assertIn('name="servings"', body)
        self.assertIn('value="3"', body)
        self.assertIn("loaves", body)

    def test_scaling_servings_multiplies_ingredient_amounts_exactly(self):
        conn = db.get_connection(self.db_path)
        recipe_id = conn.execute(
            "SELECT id FROM recipe WHERE name = 'Banana Bread'"
        ).fetchone()[0]
        conn.close()

        # Base yield is 3 loaves; doubling to 6 should double every amount,
        # using exact fraction math (never floating point).
        response = self.client.get(f"/recipes/{recipe_id}?servings=6")

        body = response.get_data(as_text=True)
        self.assertIn("7 cups Oat Flour", body)  # substitution line, was "3 1/2 cups"
        self.assertIn('ingredient-amount">4 tsp</span> Baking Soda', body)  # was "2 tsp"
        self.assertIn("(from 3)", body)

    def test_invalid_servings_query_param_is_ignored(self):
        conn = db.get_connection(self.db_path)
        recipe_id = conn.execute(
            "SELECT id FROM recipe WHERE name = 'Banana Bread'"
        ).fetchone()[0]
        conn.close()

        response = self.client.get(f"/recipes/{recipe_id}?servings=not-a-number")

        body = response.get_data(as_text=True)
        self.assertEqual(response.status_code, 200)
        self.assertIn("3 1/2 cups Oat Flour", body)

    def test_saving_servings_updates_db_and_on_disk_file(self):
        conn = db.get_connection(self.db_path)
        recipe_id = conn.execute(
            "SELECT id FROM recipe WHERE name = 'Banana Bread'"
        ).fetchone()[0]
        conn.close()

        response = self.client.post(
            f"/recipes/{recipe_id}/servings", data={"amount": "8", "unit": "muffins"}
        )

        self.assertEqual(response.status_code, 302)
        conn = db.get_connection(self.db_path)
        try:
            row = conn.execute(
                'SELECT "yields_json" FROM "recipe" WHERE "id" = ?', (recipe_id,)
            ).fetchone()
        finally:
            conn.close()
        self.assertEqual(json.loads(row["yields_json"]), [{"amount": 8, "unit": "muffins"}])

        on_disk = (self.recipes_dir / "banana-bread.yaml").read_text(encoding="utf-8")
        self.assertIn("amount: 8", on_disk)
        self.assertIn("unit: muffins", on_disk)

    def test_saving_invalid_servings_amount_flashes_and_redirects(self):
        conn = db.get_connection(self.db_path)
        recipe_id = conn.execute(
            "SELECT id FROM recipe WHERE name = 'Banana Bread'"
        ).fetchone()[0]
        conn.close()

        response = self.client.post(
            f"/recipes/{recipe_id}/servings",
            data={"amount": "not-a-number", "unit": "servings"},
            follow_redirects=True,
        )

        self.assertEqual(response.status_code, 200)
        self.assertIn("valid serving amount", response.get_data(as_text=True))

    def test_servings_route_404s_for_missing_recipe(self):
        response = self.client.post(
            "/recipes/999999/servings", data={"amount": "4", "unit": "servings"}
        )
        self.assertEqual(response.status_code, 404)


class TestStockIngredientFromRecipe(AppTestCase):
    def _banana_bread_id(self):
        conn = db.get_connection(self.db_path)
        recipe_id = conn.execute(
            "SELECT id FROM recipe WHERE name = 'Banana Bread'"
        ).fetchone()[0]
        conn.close()
        return recipe_id

    def test_each_ingredient_offers_an_add_to_pantry_control(self):
        recipe_id = self._banana_bread_id()

        body = self.client.get(f"/recipes/{recipe_id}").get_data(as_text=True)

        self.assertIn('action="/pantry/add"', body)
        self.assertIn('name="from_recipe"', body)

    def test_adding_from_a_recipe_stocks_the_pantry(self):
        recipe_id = self._banana_bread_id()

        self.client.post(
            "/pantry/add", data={"name": "Salt", "from_recipe": str(recipe_id)}
        )

        conn = db.get_connection(self.db_path)
        try:
            names = [row["name"] for row in pantry.list_items(conn)]
        finally:
            conn.close()
        self.assertEqual(names, ["Salt"])

    def test_adding_from_a_recipe_returns_to_that_recipe(self):
        recipe_id = self._banana_bread_id()

        response = self.client.post(
            "/pantry/add", data={"name": "Salt", "from_recipe": str(recipe_id)}
        )

        self.assertEqual(response.headers["Location"], f"/recipes/{recipe_id}")

    def test_returning_to_the_recipe_keeps_the_chosen_serving_size(self):
        recipe_id = self._banana_bread_id()

        response = self.client.post(
            "/pantry/add",
            data={"name": "Salt", "from_recipe": str(recipe_id), "from_servings": "6"},
        )

        self.assertEqual(
            response.headers["Location"], f"/recipes/{recipe_id}?servings=6"
        )

    def test_a_stocked_ingredient_shows_as_in_pantry_instead_of_a_button(self):
        recipe_id = self._banana_bread_id()
        conn = db.get_connection(self.db_path)
        try:
            pantry.add_item(conn, "Salt")
        finally:
            conn.close()

        body = self.client.get(f"/recipes/{recipe_id}").get_data(as_text=True)

        self.assertIn("In pantry", body)

    def test_a_removed_pantry_item_does_not_count_as_stocked(self):
        recipe_id = self._banana_bread_id()
        conn = db.get_connection(self.db_path)
        try:
            pantry.add_item(conn, "Salt")
            item_id = pantry.list_items(conn)[0]["id"]
            pantry.set_active(conn, item_id, False)
        finally:
            conn.close()

        body = self.client.get(f"/recipes/{recipe_id}").get_data(as_text=True)

        self.assertNotIn("In pantry", body)

    def test_plain_pantry_add_still_returns_to_the_pantry(self):
        response = self.client.post("/pantry/add", data={"name": "Salt"})

        self.assertEqual(response.headers["Location"], "/pantry")


class TestRecipeDetailAmountlessIngredient(AppTestCase):
    def test_no_amount_renders_just_the_name(self):
        (self.recipes_dir / "seasoned.yaml").write_text(
            "\n".join([
                "recipe_name: Seasoned",
                "recipe_uuid: None",
                "ingredients:",
                "  - salt and black pepper: {amounts: [], notes: [to taste]}",
                "  - eggs: {amounts: [{amount: '2', unit: ''}]}",
                "steps: [{step: Season.}]",
                "",
            ]),
            encoding="utf-8",
        )
        recipe_sync.sync_recipes(self.recipes_dir, self.db_path)
        conn = db.get_connection(self.db_path)
        recipe_id = conn.execute("SELECT id FROM recipe WHERE name = 'Seasoned'").fetchone()["id"]
        conn.close()
        body = self.client.get(f"/recipes/{recipe_id}").get_data(as_text=True)
        self.assertNotIn("None None", body)
        self.assertNotIn("None salt", body)
        self.assertIn('<span class="ingredient-line">salt and black pepper', body)
        self.assertIn('<span class="ingredient-amount">2</span> eggs', body)


class TestRecipeDetailSections(AppTestCase):
    def test_ingredients_grouped_under_section_headings(self):
        (self.recipes_dir / "sectioned.yaml").write_text(
            "recipe_name: Sectioned Recipe\nsteps: [{step: cook}]\n"
            "ingredients:\n"
            "- Butter:\n"
            "    amounts: [{amount: '2', unit: tbsp}]\n"
            "    section: Sauce One\n"
            "- Flour:\n"
            "    amounts: [{amount: '2', unit: cups}]\n",
            encoding="utf-8",
        )
        recipe_sync.sync_recipes(self.recipes_dir, self.db_path)
        import db
        conn = db.get_connection(self.db_path)
        recipe_id = conn.execute(
            "SELECT id FROM recipe WHERE name = 'Sectioned Recipe'"
        ).fetchone()[0]
        conn.close()

        response = self.client.get(f"/recipes/{recipe_id}")

        body = response.get_data(as_text=True)
        self.assertIn("<h4>Sauce One</h4>", body)

    def test_no_section_heading_when_ingredients_have_no_section(self):
        import db
        conn = db.get_connection(self.db_path)
        recipe_id = conn.execute(
            "SELECT id FROM recipe WHERE name = 'Banana Bread'"
        ).fetchone()[0]
        conn.close()

        response = self.client.get(f"/recipes/{recipe_id}")

        body = response.get_data(as_text=True)
        self.assertNotIn("ingredient-section-heading", body)


class TestSync(AppTestCase):
    def test_sync_redirects_and_flashes_summary(self):
        response = self.client.post("/sync", follow_redirects=True)
        self.assertEqual(response.status_code, 200)
        body = response.get_data(as_text=True)
        self.assertIn("unchanged", body.lower())

    def test_sync_does_not_500_on_a_recipe_shaped_yaml_with_bad_ingredients(self):
        (self.recipes_dir / "malformed.yaml").write_text(
            "recipe_name: Malformed\nsteps: [{step: cook}]\ningredients: not-a-list\n",
            encoding="utf-8",
        )
        response = self.client.post("/sync", follow_redirects=True)
        self.assertEqual(response.status_code, 200)


class TestRecipeImage(AppTestCase):
    def _banana_bread_id(self):
        conn = db.get_connection(self.db_path)
        recipe_id = conn.execute(
            "SELECT id FROM recipe WHERE name = 'Banana Bread'"
        ).fetchone()[0]
        conn.close()
        return recipe_id

    def test_recipe_list_shows_no_thumbnail_by_default(self):
        response = self.client.get("/recipes")
        self.assertNotIn("recipe-thumb", response.get_data(as_text=True))

    def test_uploading_image_returns_to_recipe_detail(self):
        recipe_id = self._banana_bread_id()
        response = self.client.post(
            f"/recipes/{recipe_id}/image",
            data={"image": (_fake_image_bytes(), "photo.png")},
            content_type="multipart/form-data",
        )
        self.assertEqual(response.status_code, 302)
        self.assertIn(f"/recipes/{recipe_id}", response.headers["Location"])

    def test_uploading_image_records_filename_in_db_and_on_disk_yaml(self):
        recipe_id = self._banana_bread_id()
        self.client.post(
            f"/recipes/{recipe_id}/image",
            data={"image": (_fake_image_bytes(), "photo.png")},
            content_type="multipart/form-data",
        )

        conn = db.get_connection(self.db_path)
        row = conn.execute(
            "SELECT image_filename FROM recipe WHERE id = ?", (recipe_id,)
        ).fetchone()
        conn.close()
        self.assertIsNotNone(row["image_filename"])
        self.assertTrue(row["image_filename"].endswith(".jpg"))

        on_disk = (self.recipes_dir / "banana-bread.yaml").read_text(encoding="utf-8")
        self.assertIn(f"image: {row['image_filename']}", on_disk)

    def test_uploaded_image_files_are_written_and_thumbnail_is_square(self):
        recipe_id = self._banana_bread_id()
        self.client.post(
            f"/recipes/{recipe_id}/image",
            data={"image": (_fake_image_bytes(size=(800, 300)), "photo.png")},
            content_type="multipart/form-data",
        )

        conn = db.get_connection(self.db_path)
        image_filename = conn.execute(
            "SELECT image_filename FROM recipe WHERE id = ?", (recipe_id,)
        ).fetchone()["image_filename"]
        conn.close()

        thumb_name = image_filename.replace(".jpg", "_thumb.jpg")
        self.assertTrue((self.images_dir / image_filename).exists())
        with Image.open(self.images_dir / thumb_name) as thumb:
            self.assertEqual(thumb.size, (96, 96))

    def test_recipes_list_shows_thumbnail_after_upload(self):
        recipe_id = self._banana_bread_id()
        self.client.post(
            f"/recipes/{recipe_id}/image",
            data={"image": (_fake_image_bytes(), "photo.png")},
            content_type="multipart/form-data",
        )

        response = self.client.get("/recipes")
        body = response.get_data(as_text=True)
        self.assertIn('class="recipe-thumb"', body)
        self.assertIn("_thumb.jpg", body)

    def test_recipe_detail_shows_larger_image_after_upload(self):
        recipe_id = self._banana_bread_id()
        self.client.post(
            f"/recipes/{recipe_id}/image",
            data={"image": (_fake_image_bytes(), "photo.png")},
            content_type="multipart/form-data",
        )

        response = self.client.get(f"/recipes/{recipe_id}")
        body = response.get_data(as_text=True)
        self.assertIn('class="recipe-hero-image"', body)

    def test_assign_meal_search_results_show_thumbnail_after_upload(self):
        recipe_id = self._banana_bread_id()
        self.client.post(
            f"/recipes/{recipe_id}/image",
            data={"image": (_fake_image_bytes(), "photo.png")},
            content_type="multipart/form-data",
        )

        response = self.client.get("/calendar/assign?date=2026-09-01&slot=Dinner")
        self.assertIn('class="recipe-thumb"', response.get_data(as_text=True))

    def test_calendar_shows_thumbnail_for_assigned_meal_with_image(self):
        recipe_id = self._banana_bread_id()
        self.client.post(
            f"/recipes/{recipe_id}/image",
            data={"image": (_fake_image_bytes(), "photo.png")},
            content_type="multipart/form-data",
        )
        self.client.post(
            "/calendar/assign",
            data={"date": "2026-09-01", "slot": "Dinner", "recipe_id": str(recipe_id)},
        )

        response = self.client.get("/calendar?week=2026-08-31&day=2026-09-01")
        self.assertIn('class="meal-slot-thumb"', response.get_data(as_text=True))

    def test_uploading_non_image_file_is_rejected(self):
        recipe_id = self._banana_bread_id()
        response = self.client.post(
            f"/recipes/{recipe_id}/image",
            data={"image": (io.BytesIO(b"not an image"), "fake.jpg")},
            content_type="multipart/form-data",
            follow_redirects=True,
        )
        self.assertEqual(response.status_code, 200)
        self.assertIn("not a valid image", response.get_data(as_text=True).lower())

        conn = db.get_connection(self.db_path)
        row = conn.execute(
            "SELECT image_filename FROM recipe WHERE id = ?", (recipe_id,)
        ).fetchone()
        conn.close()
        self.assertIsNone(row["image_filename"])

    def test_image_upload_404s_for_missing_recipe(self):
        response = self.client.post(
            "/recipes/999999/image",
            data={"image": (_fake_image_bytes(), "photo.png")},
            content_type="multipart/form-data",
        )
        self.assertEqual(response.status_code, 404)

    def test_removing_image_clears_db_yaml_and_files(self):
        recipe_id = self._banana_bread_id()
        self.client.post(
            f"/recipes/{recipe_id}/image",
            data={"image": (_fake_image_bytes(), "photo.png")},
            content_type="multipart/form-data",
        )
        conn = db.get_connection(self.db_path)
        image_filename = conn.execute(
            "SELECT image_filename FROM recipe WHERE id = ?", (recipe_id,)
        ).fetchone()["image_filename"]
        conn.close()
        thumb_name = image_filename.replace(".jpg", "_thumb.jpg")

        response = self.client.post(f"/recipes/{recipe_id}/image/delete")

        self.assertEqual(response.status_code, 302)
        conn = db.get_connection(self.db_path)
        row = conn.execute(
            "SELECT image_filename FROM recipe WHERE id = ?", (recipe_id,)
        ).fetchone()
        conn.close()
        self.assertIsNone(row["image_filename"])
        on_disk = (self.recipes_dir / "banana-bread.yaml").read_text(encoding="utf-8")
        self.assertIn("image: None", on_disk)
        self.assertFalse((self.images_dir / image_filename).exists())
        self.assertFalse((self.images_dir / thumb_name).exists())


class TestProcessAndSaveRecipeImage(AppTestCase):
    def test_saves_detail_and_square_thumbnail_named_from_uuid(self):
        images_dir = Path(self.tmp_dir) / "standalone-images"
        filename = app_module._process_and_save_recipe_image(
            _fake_image_bytes(size=(800, 300)), images_dir, "test-uuid-123"
        )
        self.assertEqual(filename, "test-uuid-123.jpg")
        self.assertTrue((images_dir / filename).exists())
        with Image.open(images_dir / "test-uuid-123_thumb.jpg") as thumb:
            self.assertEqual(thumb.size, (96, 96))

    def test_invalid_image_data_raises(self):
        images_dir = Path(self.tmp_dir) / "standalone-images-2"
        with self.assertRaises(Exception):
            app_module._process_and_save_recipe_image(
                io.BytesIO(b"not an image"), images_dir, "test-uuid-456"
            )


if __name__ == "__main__":
    unittest.main()


class TestRecipeRating(AppTestCase):
    def _recipe_id(self, name="Banana Bread"):
        conn = db.get_connection(self.db_path)
        try:
            return conn.execute("SELECT id FROM recipe WHERE name = ?", (name,)).fetchone()["id"]
        finally:
            conn.close()

    def _rating(self, recipe_id):
        conn = db.get_connection(self.db_path)
        try:
            return conn.execute("SELECT rating FROM recipe WHERE id = ?", (recipe_id,)).fetchone()["rating"]
        finally:
            conn.close()

    def _yaml_rating(self):
        import yaml
        return yaml.safe_load((self.recipes_dir / "banana-bread.yaml").read_text(encoding="utf-8")).get("rating")

    def test_setting_a_rating_writes_the_yaml_and_redirects_back(self):
        recipe_id = self._recipe_id()

        response = self.client.post(
            f"/recipes/{recipe_id}/rating",
            data={"rating": "4", "next": f"/recipes#recipe-{recipe_id}"},
        )

        self.assertEqual(response.status_code, 302)
        self.assertTrue(response.headers["Location"].endswith(f"/recipes#recipe-{recipe_id}"))
        self.assertEqual(self._rating(recipe_id), 4)
        self.assertEqual(self._yaml_rating(), 4)

    def test_fetch_requests_get_json_instead_of_a_redirect(self):
        recipe_id = self._recipe_id()
        response = self.client.post(
            f"/recipes/{recipe_id}/rating",
            data={"rating": "5", "next": f"/recipes/{recipe_id}"},
            headers={"X-Requested-With": "fetch"},
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.get_json(), {"ok": True, "rating": 5, "max_rating": recipe_sync.MAX_RATING})
        self.assertEqual(self._rating(recipe_id), 5)

        response = self.client.post(
            f"/recipes/{recipe_id}/rating", data={"rating": "9"},
            headers={"X-Requested-With": "fetch"},
        )
        self.assertEqual(response.status_code, 400)
        self.assertFalse(response.get_json()["ok"])
        self.assertEqual(self._rating(recipe_id), 5)

    def test_star_buttons_carry_their_number_for_in_place_repaint(self):
        recipe_id = self._recipe_id()
        self.client.post(f"/recipes/{recipe_id}/rating", data={"rating": "3"})
        body = self.client.get(f"/recipes/{recipe_id}").get_data(as_text=True)
        self.assertIn('data-rating="3" data-max="5"', body)
        self.assertIn('data-star="3" class="star star--on"', body)
        self.assertIn('data-star="4" class="star"', body)
        self.assertIn('<span class="divider-meta" data-rating-text>3/5</span>', body)
        self.assertIn('star-rating.js', body)

    def test_zero_clears_the_rating(self):
        recipe_id = self._recipe_id()
        self.client.post(f"/recipes/{recipe_id}/rating", data={"rating": "3"})

        self.client.post(f"/recipes/{recipe_id}/rating", data={"rating": "0"})

        self.assertIsNone(self._rating(recipe_id))
        # Written as `rating: None`, the same "unset" spelling category/image use.
        self.assertIsNone(recipe_sync.normalize_rating(self._yaml_rating()))

    def test_out_of_range_or_junk_rating_is_rejected(self):
        recipe_id = self._recipe_id()
        self.client.post(f"/recipes/{recipe_id}/rating", data={"rating": "2"})

        for bad in ("6", "-1", "lots", ""):
            body = self.client.post(
                f"/recipes/{recipe_id}/rating", data={"rating": bad}, follow_redirects=True
            ).get_data(as_text=True)
            self.assertIn("0 to 5 stars", body, bad)
        self.assertEqual(self._rating(recipe_id), 2)

    def test_an_unsafe_next_falls_back_to_the_recipe_page(self):
        recipe_id = self._recipe_id()

        response = self.client.post(
            f"/recipes/{recipe_id}/rating", data={"rating": "1", "next": "https://evil.example/x"}
        )

        self.assertTrue(response.headers["Location"].endswith(f"/recipes/{recipe_id}"))

    def test_404_for_missing_recipe(self):
        self.assertEqual(self.client.post("/recipes/99999/rating", data={"rating": "3"}).status_code, 404)

    def test_list_shows_clickable_stars_next_to_each_recipe(self):
        recipe_id = self._recipe_id()
        self.client.post(f"/recipes/{recipe_id}/rating", data={"rating": "3"})

        body = self.client.get("/recipes").get_data(as_text=True)

        row = body[body.index(f'id="recipe-{recipe_id}"'):]
        row = row[:row.index("</li>")]
        self.assertIn(f'action="/recipes/{recipe_id}/rating"', row)
        self.assertIn('aria-label="Rated 3 of 5 stars"', row)
        self.assertEqual(row.count('class="star star--on"'), 3)
        self.assertEqual(row.count('class="star"'), 2)
        # Clicking the current star clears; the others set their own value.
        self.assertIn('value="0" data-star="3" class="star star--on" title="Clear rating"', row)
        self.assertIn('value="5" data-star="5" class="star"', row)
        self.assertIn(f'name="next" value="/recipes#recipe-{recipe_id}"', row)

    def test_list_search_keeps_the_query_in_the_return_url(self):
        recipe_id = self._recipe_id()
        body = self.client.get("/recipes?q=bread").get_data(as_text=True)
        self.assertIn(f'name="next" value="/recipes?q=bread#recipe-{recipe_id}"', body)

    def test_detail_shows_the_rating_with_stars_and_a_label(self):
        recipe_id = self._recipe_id()
        body = self.client.get(f"/recipes/{recipe_id}").get_data(as_text=True)
        self.assertIn("Not rated", body)
        self.assertIn('aria-label="Not rated yet"', body)

        self.client.post(f"/recipes/{recipe_id}/rating", data={"rating": "5"})
        body = self.client.get(f"/recipes/{recipe_id}").get_data(as_text=True)

        self.assertIn("5/5", body)
        self.assertEqual(body.count('class="star star--on"'), 5)
        self.assertIn(f'name="next" value="/recipes/{recipe_id}"', body)
