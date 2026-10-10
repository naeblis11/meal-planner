"""Grocery-aisle categorization: best-guess a shopping-list ingredient's aisle
from a keyword heuristic, so a newly generated item starts grouped sensibly
even before anyone has corrected it by hand. `shopping_list.generate()` only
falls back to this when no remembered `ingredient_aisle` override exists for
that ingredient name -- a manual correction always wins.

Category names match `DEFAULT_AISLES` in app.py exactly, so a keyword guess
and a hand-picked aisle land in the same grouping bucket.
"""
import re

AISLE_ORDER = [
    "Produce", "Dairy & Eggs", "Meat & Seafood", "Frozen", "Bakery",
    "Dry Goods & Pasta", "Canned & Jarred", "Condiments & Sauces",
    "Spices & Baking", "Beverages", "Snacks", "Household",
]

_KEYWORD_CATEGORIES = [
    ("Produce", [
        "onion", "garlic", "tomato", "potato", "carrot", "celery", "lettuce",
        "spinach", "kale", "broccoli", "cauliflower", "cucumber", "zucchini",
        "squash", "mushroom", "avocado", "lemon", "lime", "apple", "banana",
        "berry", "grape", "orange", "peach", "pear", "melon", "cilantro",
        "parsley", "basil", "mint", "scallion", "shallot", "ginger", "cabbage",
        "corn", "eggplant", "asparagus", "bell pepper", "jalapeno", "poblano",
        "green pepper", "green peppers", "bell peppers", "chile pepper",
        "chile peppers", "cubanelle", "chive", "tomatillo", "guacamole",
        "mixed greens", "cole slaw", "coleslaw", "wonton",
    ]),
    ("Dairy & Eggs", [
        "milk", "cream", "butter", "cheese", "yogurt", "egg", "buttermilk",
        "sour cream", "half and half",
        "margarine", "oleo", "cheddar", "parmesan", "parmigiano", "mozzarella",
        "romano", "velveeta", "monterey jack", "half & half", "refrigerated",
    ]),
    ("Meat & Seafood", [
        "chicken", "beef", "pork", "turkey", "bacon", "sausage", "ham",
        "steak", "shrimp", "salmon", "tuna", "fish", "lamb", "veal", "crab",
        "scallop",
        "hamburger", "chuck", "roast", "meat", "kielbasa", "pepperoni",
        "salami", "bologna", "prosciutto", "hot dog", "hot dogs", "giblet",
        "mignon",
    ]),
    ("Frozen", [
        "frozen", "ice cream",
        "cool whip", "tater tots", "hash browns", "hash brown", "puff pastry",
        "puffed pastry", "lemonade", "meatball", "crushed ice",
    ]),
    ("Bakery", [
        "bread", "bun", "roll", "tortilla", "bagel", "pita", "baguette",
        "croissant",
        "pie shell", "pie crust",
    ]),
    ("Dry Goods & Pasta", [
        "flour", "sugar", "rice", "pasta", "noodle", "oats", "cereal",
        "cornstarch",
        "macaroni", "spaghetti", "fettuccine", "linguine", "lasagna", "orzo",
        "rigatoni", "penne", "oatmeal", "breadcrumbs", "panko", "stuffing",
        "cheerios", "cornflakes", "chex", "crispix", "wheat germ", "flaxseed",
    ]),
    ("Canned & Jarred", [
        "broth", "stock", "bean", "coconut milk", "tomato sauce",
        "tomato paste",
        "pie filling", "crushed pineapple", "pineapple chunks",
        "pineapple tidbits", "canned pineapple", "fruit cocktail",
        "maraschino", "chestnut", "applesauce", "sauerkraut", "manwich",
        "ragu", "green chiles", "enchilada sauce", "chipotle",
        "canned pumpkin", "can pumpkin", "soup",
    ]),
    ("Condiments & Sauces", [
        "vinegar", "mustard", "ketchup", "mayonnaise", "soy sauce",
        "olive oil", "vegetable oil", "honey", "syrup",
        "oil", "worcestershire", "tabasco", "catsup", "mayo", "miracle whip",
        "dressing", "teriyaki", "horseradish", "caper", "pickle", "olive",
        "pimento", "barbecue", "bbq", "salsa", "picante", "hot sauce",
        "pepper sauce", "chili sauce", "jam", "jelly", "preserves",
        "caramel topping", "butterscotch topping", "fudge topping",
        "hot fudge", "caramel sauce", "gravy", "au jus", "kitchen bouquet",
        "liquid smoke", "cooking spray", "pam", "sauce",
    ]),
    ("Spices & Baking", [
        "salt", "cinnamon", "cumin", "paprika", "oregano", "thyme",
        "rosemary", "nutmeg", "vanilla", "black pepper", "baking soda",
        "baking powder", "cocoa", "yeast",
        "pepper", "peppercorn", "cayenne", "chili powder", "chilli powder",
        "allspice", "clove", "cardamom", "mace", "seasoning", "bay leaf",
        "bay leaves", "dill weed", "dill seed", "anise", "poppy", "sesame",
        "sage", "marjoram", "extract", "flavoring", "shortening", "crisco",
        "sprinkles", "morsel", "almond", "pecan", "walnut", "nut", "macadamia",
        "coconut", "marshmallow", "pectin", "gelatin", "tapioca", "jello",
        "pudding", "cake mix", "bisquick", "molasses", "raisin", "craisins",
        "spice", "all spice", "taco mix", "dream whip", "arbol", "nutmeat",
        "essence",
    ]),
    ("Beverages", [
        "juice", "soda", "coffee", "tea", "wine", "beer",
        "rum", "vodka", "brandy", "cognac", "whiskey", "bourbon", "tequila",
        "sherry", "triple sec", "galliano", "liqueur", "schnapps", "quik",
        "cider",
    ]),
    ("Snacks", [
        "chip", "cracker", "pretzel", "chocolate",
        "peanut", "cashew", "oreo", "snickers", "popcorn", "candy", "caramel",
        "saltine", "sunflower seeds", "cookie", "hershey",
    ]),
    ("Household", [
        "foil", "plastic wrap", "paper towel", "toilet paper", "dish soap",
        "trash bag", "sponge", "napkin", "detergent",
        "wax paper", "parchment", "paraffin",
    ]),
]


def _normalized_tokens(name: str) -> set:
    """Lowercase words from `name`, plus naive singular forms so a plural
    ingredient ("eggs", "tomatoes") still matches a singular keyword."""
    tokens = re.findall(r"[a-z]+", name.lower())
    normalized = set(tokens)
    for token in tokens:
        if token.endswith("ies") and len(token) > 3:
            normalized.add(token[:-3] + "y")
        elif token.endswith("es") and len(token) > 2:
            normalized.add(token[:-2])
        if token.endswith("s") and len(token) > 1:
            normalized.add(token[:-1])
    return normalized


def categorize(ingredient_name: str) -> str:
    """Best-guess grocery-aisle category for an ingredient name.

    Single-word keywords match whole normalized tokens (so "corn" doesn't
    fire on "cornstarch"); multi-word keywords match as a whole-word phrase.
    Falls back to "Uncategorized" when nothing matches, which is a
    first-class bucket in the UI, not an apology.
    """
    name = ingredient_name.lower()
    tokens = _normalized_tokens(name)
    for category, keywords in _KEYWORD_CATEGORIES:
        for keyword in keywords:
            if " " in keyword:
                if re.search(r"\b" + re.escape(keyword) + r"\b", name):
                    return category
            elif keyword in tokens:
                return category
    return "Uncategorized"
