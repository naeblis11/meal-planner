import unittest

import grocery_categories


class TestCategorize(unittest.TestCase):
    def test_produce_ingredient(self):
        self.assertEqual(grocery_categories.categorize("Zucchini"), "Produce")

    def test_meat_ingredient(self):
        self.assertEqual(grocery_categories.categorize("Boneless Chicken Thighs"), "Meat & Seafood")

    def test_dairy_ingredient(self):
        self.assertEqual(grocery_categories.categorize("Unsalted Butter"), "Dairy & Eggs")

    def test_dry_goods_ingredient(self):
        self.assertEqual(grocery_categories.categorize("All Purpose Flour"), "Dry Goods & Pasta")

    def test_household_ingredient(self):
        self.assertEqual(grocery_categories.categorize("Aluminum Foil"), "Household")

    def test_plural_matches_singular_keyword(self):
        self.assertEqual(grocery_categories.categorize("Eggs"), "Dairy & Eggs")
        self.assertEqual(grocery_categories.categorize("Tomatoes"), "Produce")

    def test_multi_word_keyword_matches(self):
        self.assertEqual(grocery_categories.categorize("Extra Virgin Olive Oil"), "Condiments & Sauces")

    def test_unmatched_ingredient_is_uncategorized(self):
        self.assertEqual(grocery_categories.categorize("Xylitol"), "Uncategorized")

    def test_short_keyword_does_not_match_inside_longer_word(self):
        # "corn" must not fire on "cornstarch" (Dry Goods & Pasta, not Produce).
        self.assertEqual(grocery_categories.categorize("Cornstarch"), "Dry Goods & Pasta")
        self.assertEqual(grocery_categories.categorize("Corn"), "Produce")

    def test_case_insensitive(self):
        self.assertEqual(grocery_categories.categorize("GARLIC"), "Produce")

    def test_aisle_order_matches_app_default_aisles_vocabulary(self):
        # Every category name this module can return must be a real aisle
        # bucket, so a keyword guess and a hand-picked aisle always agree.
        returnable = {category for category, _ in grocery_categories._KEYWORD_CATEGORIES}
        self.assertTrue(returnable.issubset(set(grocery_categories.AISLE_ORDER)))

    def test_cookbook_staples_have_an_aisle(self):
        cases = {
            "oleo": "Dairy & Eggs", "Velveeta": "Dairy & Eggs", "grated Parmesan": "Dairy & Eggs",
            "ground chuck": "Meat & Seafood", "kielbasa": "Meat & Seafood",
            "Cool Whip": "Frozen", "tater tots": "Frozen",
            "elbow macaroni": "Dry Goods & Pasta", "cherry pie filling": "Canned & Jarred",
            "canola oil": "Condiments & Sauces", "Worcestershire sauce": "Condiments & Sauces",
            "chili powder": "Spices & Baking", "chopped pecans": "Spices & Baking",
            "yellow cake mix": "Spices & Baking", "brandy": "Beverages", "peanuts": "Snacks",
            "wax paper": "Household",
        }
        for name, aisle in cases.items():
            with self.subTest(name=name):
                self.assertEqual(grocery_categories.categorize(name), aisle)

    def test_generic_pepper_yields_to_specific_peppers(self):
        # "pepper" is a Spices keyword, but earlier aisles keep their own peppers.
        self.assertEqual(grocery_categories.categorize("pepper"), "Spices & Baking")
        self.assertEqual(grocery_categories.categorize("green peppers"), "Produce")
        self.assertEqual(grocery_categories.categorize("Pepper Jack cheese"), "Dairy & Eggs")
        self.assertEqual(grocery_categories.categorize("hot pepper sauce"), "Condiments & Sauces")
        self.assertEqual(grocery_categories.categorize("pepperoni"), "Meat & Seafood")

    def test_garlic_cloves_stay_produce(self):
        self.assertEqual(grocery_categories.categorize("garlic cloves"), "Produce")
        self.assertEqual(grocery_categories.categorize("ground cloves"), "Spices & Baking")

if __name__ == "__main__":
    unittest.main()
