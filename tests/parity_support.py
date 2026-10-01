"""Golden cases for logic the Python app and the Android app must agree on.

The Python modules are the reference. `python -m tests.parity_support` writes
their answers for every case below to tests/fixtures/parity/*.json; the
Android `domain` tests read the same files, and tests/test_parity_fixtures.py
fails whenever the Python answers drift from what is committed. So a
behaviour change on either side is caught: change Python, regenerate, and
the Kotlin tests fail until Android matches.
"""
import json
import sys
import tempfile
from datetime import date, timedelta
from fractions import Fraction
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

import db  # noqa: E402
import grocery_categories  # noqa: E402
import meal_calendar  # noqa: E402
import shopping_list  # noqa: E402
import unit_conversion  # noqa: E402
from tests import parity_recipes  # noqa: E402

FIXTURE_DIR = ROOT / "tests" / "fixtures" / "parity"


def _frac(value):
    return None if value is None else f"{value.numerator}/{value.denominator}"


PARSE_AMOUNT = ["2", "1/2", "1 1/2", "1.5", ".5", "5.", "1e2", "2E-1", "-1/2", "+3",
                "1_000", " 3 ", "", "abc", "1 2", "1/0", "1 1/0", "3/4 cup", "1 -1/2",
                None, "½", "0.125", "10/4", "1\u00a01/2"]
FORMAT_AMOUNT = ["0/1", "1/1", "3/2", "7/4", "-1/2", "-3/2", "1/3", "100/7", "6/4"]
NORMALIZE_UNIT = ["Cups", " TBSP ", "fl oz", "Fluid Ounces", "c", "l", "", None, "handful", "Pkg"]
KNOWN_UNIT_WORD = ["Cups", "fl oz", "flour", "", " lb "]
TO_IMPERIAL = [["250", "ml"], ["500", "ml"], ["1", "l"], ["100", "g"], ["1", "kg"],
               ["2", "cups"], ["3", "cloves"], ["some", "ml"], [None, "g"],
               ["1 1/2", "Litres"], ["5", "ML"], ["2", None]]
SERVINGS_RATIO = [["6", "4"], ["2", "4"], [None, "4"], ["6", None], ["0", "4"],
                  ["6", "0"], ["abc", "4"], ["1 1/2", "3"], ["-2", "4"]]
SCALE_AMOUNT_TEXT = [["1 1/2", "2/1"], ["2", "1/3"], ["to taste", "2/1"], [None, "2/1"],
                     ["3", "3/2"], ["1/4", "1/1"]]

COMBINE = [
    [["Flour", "2", "cups"], ["flour", "1 1/2", "cups"]],
    [["Butter", "1", "tbsp"], ["Butter", "2", "tbsp"]],
    [["Milk", "1", "cup"], ["Milk", "4", "tbsp"]],
    [["Garlic", "2", "cloves"], ["garlic", "3", "clove"]],
    [["Sugar", "1", "cup"], ["Sugar", "1", "lb"]],
    [["Salt", "to taste", None], ["Salt", "1", "tsp"], ["Salt", "to taste", None],
     ["Salt", "a pinch", None]],
    [["Eggs", "2", None], ["Eggs", "3", None]],
    [["Water", "1", "cup"], ["Water", "2", "cups"], ["Water", "1", "qt"]],
    [["Onion", None, None], ["Onion", None, None]],
    [["Cream", "1/2", "cup"], ["Cream", "1/2", "cup"]],
    [["Rice", "1", "cup"]],
    [["Beef", "8", "oz"], ["Beef", "1", "lb"]],
]

AISLES = ["Yellow onions", "eggs", "Cornstarch", "corn", "sour cream", "Heavy cream",
          "black pepper", "red bell pepper", "chicken thighs", "tomato paste",
          "Frozen peas", "berries", "paper towels", "dragon fruit", "Olive Oil",
          "cherries", "tomatoes", "Chocolate chips", ""]

PANTRY_MATCH = [["salt", False, "Kosher salt"], ["salt", True, "Kosher salt"],
                ["Salt", True, "salt"], ["oil", False, "Olive Oil"],
                ["butter", False, "peanut butter"], ["eggs", False, "egg"]]

# Each case: planned meals (servings, the recipe's first yield amount, its
# ingredient lines), pantry rows [name, exact, on hand], remembered aisles, and rows
# already on the list. Expected: every list row afterwards, in id order.
SHOPPING = [
    {"name": "fresh list, pantry bucket",
     "meals": [{"servings": None, "yield": None,
                "ingredients": [["Flour", "2", "cups"], ["Salt", "to taste", None], ["Eggs", "2", None]]}],
     "pantry": [["salt", False, True]], "known_aisles": {}, "existing": []},
    {"name": "servings scale each night separately",
     "meals": [{"servings": "8", "yield": "4", "ingredients": [["Butter", "1/2", "cup"]]},
               {"servings": None, "yield": "4", "ingredients": [["Butter", "1/2", "cup"]]}],
     "pantry": [], "known_aisles": {}, "existing": []},
    {"name": "merging into a checked row unchecks it and keeps its aisle",
     "meals": [{"servings": None, "yield": None, "ingredients": [["Milk", "1", "cup"]]}],
     "pantry": [], "known_aisles": {},
     "existing": [{"name": "Milk", "amount": "1", "unit": "cup", "aisle": "Dairy & Eggs", "checked": 1}]},
    {"name": "incompatible units get their own row",
     "meals": [{"servings": None, "yield": None, "ingredients": [["Sugar", "1", "cup"]]}],
     "pantry": [], "known_aisles": {},
     "existing": [{"name": "Sugar", "amount": "1", "unit": "lb", "aisle": None, "checked": 0}]},
    {"name": "amountless hand-added row takes the amount",
     "meals": [{"servings": None, "yield": None, "ingredients": [["olive oil", "2", "tbsp"]]}],
     "pantry": [], "known_aisles": {},
     "existing": [{"name": "Olive oil", "amount": None, "unit": None, "aisle": None, "checked": 0}]},
    {"name": "amountless row never loses an amount to a second incompatible line",
     "meals": [{"servings": None, "yield": None,
                "ingredients": [["flour", "2", "cups"], ["flour", "1", "lb"]]}],
     "pantry": [], "known_aisles": {},
     "existing": [{"name": "Flour", "amount": None, "unit": None, "aisle": None, "checked": 0}]},
    {"name": "remembered aisle wins over the guess",
     "meals": [{"servings": None, "yield": None, "ingredients": [["Flour", "1", "cup"]]}],
     "pantry": [], "known_aisles": {"flour": "Baking Aisle"}, "existing": []},
    {"name": "exact pantry match",
     "meals": [{"servings": None, "yield": None,
                "ingredients": [["Kosher salt", "1", "tsp"], ["salt", "1", "tsp"]]}],
     "pantry": [["Salt", True, True]], "known_aisles": {}, "existing": []},
    {"name": "a crossed-out pantry item still needs buying",
     "meals": [{"servings": None, "yield": None,
                "ingredients": [["Flour", "2", "cups"], ["Salt", "1", "tsp"]]}],
     "pantry": [["flour", False, False], ["salt", False, True]], "known_aisles": {}, "existing": []},
    {"name": "servings with no recorded yield never scale",
     "meals": [{"servings": "10", "yield": None, "ingredients": [["Rice", "1", "cup"]]}],
     "pantry": [], "known_aisles": {}, "existing": []},
]


def run_shopping_case(case: dict) -> list:
    week_start = date(2026, 8, 31)  # a Monday
    with tempfile.TemporaryDirectory() as tmp:
        conn = db.get_connection(Path(tmp) / "parity.db")
        try:
            for i, meal in enumerate(case["meals"]):
                yields = json.dumps([{"amount": meal["yield"]}]) if meal["yield"] else None
                recipe_id = conn.execute(
                    'INSERT INTO "recipe" ("file_path", "file_mtime", "name", "raw_yaml", "yields_json") '
                    "VALUES (?, 0, ?, '', ?)", (f"r{i}.yaml", f"R{i}", yields)).lastrowid
                for order, (name, amount, unit) in enumerate(meal["ingredients"]):
                    conn.execute(
                        'INSERT INTO "recipe_ingredient" ("recipe_id", "order_num", "name", "amount", '
                        '"unit", "amounts_json") VALUES (?, ?, ?, ?, ?, \'[]\')',
                        (recipe_id, order, name, amount, unit))
                day = (week_start + timedelta(days=i // 3)).isoformat()
                meal_calendar.assign_meal(conn, day, meal_calendar.SLOTS[i % 3], recipe_id,
                                          meal["servings"])
            for name, exact, active in case["pantry"]:
                conn.execute('INSERT INTO "pantry_item" ("name", "exact_match", "active") VALUES (?, ?, ?)',
                             (name, 1 if exact else 0, 1 if active else 0))
            for name, aisle in case["known_aisles"].items():
                conn.execute('INSERT INTO "ingredient_aisle" ("name", "aisle") VALUES (?, ?)', (name, aisle))
            for row in case["existing"]:
                conn.execute(
                    'INSERT INTO "shopping_list_item" ("name", "amount", "unit", "aisle", "in_pantry", '
                    '"checked") VALUES (?, ?, ?, ?, 0, ?)',
                    (row["name"], row["amount"], row["unit"], row["aisle"], row["checked"]))
            conn.commit()
            shopping_list.generate(conn, week_start)
            return [dict(r) for r in conn.execute(
                'SELECT "name", "amount", "unit", "aisle", "in_pantry", "checked" '
                'FROM "shopping_list_item" ORDER BY "id"')]
        finally:
            conn.close()


def build_fixtures() -> dict:
    """Every fixture file's content, keyed by file name."""
    uc = unit_conversion
    fixtures = {
        "amounts.json": {
            "parse_amount": [{"text": t, "expected": _frac(uc.parse_amount(t))} for t in PARSE_AMOUNT],
            "format_amount": [{"value": v, "expected": uc.format_amount(Fraction(v))} for v in FORMAT_AMOUNT],
            "normalize_unit": [{"text": t, "expected": uc.normalize_unit(t)} for t in NORMALIZE_UNIT],
            "is_known_unit_word": [{"text": t, "expected": uc.is_known_unit_word(t)} for t in KNOWN_UNIT_WORD],
            "to_imperial": [{"amount": a, "unit": u, "expected": list(uc.to_imperial(a, u))}
                            for a, u in TO_IMPERIAL],
            "servings_ratio": [{"planned": p, "base": b, "expected": _frac(uc.servings_ratio(p, b))}
                               for p, b in SERVINGS_RATIO],
            "scale_amount_text": [{"amount": a, "ratio": r,
                                   "expected": uc.scale_amount_text(a, Fraction(r))}
                                  for a, r in SCALE_AMOUNT_TEXT],
        },
        "combine.json": [{"rows": rows, "expected": [list(r) for r in uc.combine_lines(
            [tuple(r) for r in rows])]} for rows in COMBINE],
        "aisles.json": [{"name": n, "expected": grocery_categories.categorize(n)} for n in AISLES],
        "pantry_match.json": [
            {"pantry": p, "exact": e, "ingredient": i,
             "expected": shopping_list.matches_pantry_item({"name": p, "exact_match": e}, i)}
            for p, e, i in PANTRY_MATCH],
        "shopping_merge.json": [dict(case, expected=run_shopping_case(case)) for case in SHOPPING],
    }
    fixtures.update(parity_recipes.build_recipe_fixtures())
    return fixtures


def render(content) -> str:
    return json.dumps(content, indent=2, ensure_ascii=False) + "\n"


def main() -> int:
    FIXTURE_DIR.mkdir(parents=True, exist_ok=True)
    for name, content in build_fixtures().items():
        (FIXTURE_DIR / name).write_text(render(content), encoding="utf-8", newline="\n")
        print(f"wrote {FIXTURE_DIR / name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
