"""Golden cases for the recipe-file logic the Android app ports: YAML typing,
Open Recipe Format parsing and editing, Meal Master import, and backup file
names. Collected into tests/fixtures/parity/ by `python -m tests.parity_support`
(see that module); the Android `domain` tests read the same files.
"""
import json
import sys
import tempfile
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

import app as web_app  # noqa: E402  (module-level helpers only; no app is created)
import meal_master  # noqa: E402
import recipe_sync  # noqa: E402

FIXTURES = ROOT / "tests" / "fixtures"

# -- Python string and YAML behaviour the ports rely on ----------------------

TITLE = ["main dish", "DON'T stop", "for the SAUCE", "\u00e9minc\u00e9 de veau", "x2y z", ""]
SPLITLINES = ["a\nb", "a\r\nb\r", "a\x0cb", "a\x1cb", "a\u2028b", "a\n", "", "\n\n", "a\x85b"]
SPLIT = [["  a  b ", -1], ["a b c ", 1], ["x\u00a0 y", 1], ["a\u00a0b c", 1], [" 2 cups flour", 1], ["", 1], ["one", 1]]
STRIP = ["  a ", "\u00a0a\u00a0", "\x1ca\x85", "\t\n"]
CP1252_BYTES = bytes([0x41, 0x81, 0x85, 0x93, 0x94, 0x96, 0xE9, 0x8D])
PY_STR = [1, 2.0, 1.5, True, False, None, "x", 10 ** 20, 0.1, -0.0, 350]
YAML_LOAD = [
    "a: 1:30", "a: 1:30.5", "a: yes", "a: On", "a: 'yes'", "a: y", "a: 017", "a: 0o17",
    "a: 0x1F", "a: 0b101", "a: 1_000", "a: 1.5", "a: .5", "a: 1.", "a: 1e3", "a: 1.0e+3",
    "a: ~", "a:", "a: None", "a: null", "a: +12", "a: 3/4",
    "a: 1 1/2", "a: [1, two, 3.0]", "a: {b: c}", "- x\n- y", "a: !!str 12",
    "a: &x 1\nb: *x", "a: 1\na: 2", "a: \"\\u00e9\"", "a: =",
]


def _load_as_json(text):
    try:
        return {"value": yaml.safe_load(text)}
    except yaml.YAMLError:
        return {"error": True}


# -- Open Recipe Format parsing ------------------------------------------------

ORF_INLINE = {
    "minimal": "recipe_name: Toast\ningredients:\n- Bread:\n    amounts:\n    - amount: 2\n      unit: slice\nsteps:\n- step: Toast it.\n",
    "loose fields": (
        "recipe_uuid: None\nrecipe_name: Soup\noven_fan: true\nrating: '4'\nsource_authors: Jane\n"
        "yields:\n- servings: 4\nnotes: just a string\ncategory: None\nsubcategory: ''\n"
        "ingredients:\n- Stock:\n    usda_num: 1234\n    amounts:\n    - amount: 250\n      unit: ml\n"
        "    substitutions:\n    - Water:\n        amounts:\n        - amount: 1\n          unit: l\n"
        "- Salt:\n    amounts:\n    - amount: to taste\n      unit: ''\n"
        "steps:\n- step: Simmer.\n  notes:\n  - gently\n"
    ),
    "sexagesimal time and fan off": "recipe_name: Roast\noven_time: 1:30\noven_fan: off\noven_temp:\n- amount: 350\n  unit: F\ningredients:\n- Beef:\n    amounts:\n    - amount: 3\n      unit: lb\nsteps:\n- step: Roast.\n",
    "rating true": "recipe_name: A\nrating: true\ningredients: []\nsteps: []\n",
    "rating out of range": "recipe_name: A\nrating: 9\ningredients: []\nsteps: []\n",
    "rating float text": "recipe_name: A\nrating: '3.0'\ningredients: []\nsteps: []\n",
    "yield amount/unit shape": "recipe_name: Bread\nyields:\n- amount: 2 1/2\n  unit: loaves\ningredients: []\nsteps: []\n",
    "missing steps": "recipe_name: A\ningredients: []\n",
    "empty document": "",
    "list document": "- a\n- b\n",
    "step without step key": "recipe_name: A\ningredients: []\nsteps:\n- text: nope\n",
    "ingredient with two keys": "recipe_name: A\ningredients:\n- Salt: {}\n  Pepper: {}\nsteps: []\n",
    "null and empty bodies": "recipe_name: A\ningredients:\n- Salt:\n- Pepper: ''\nsteps:\n- step: Season.\n",
    "amounts key present but null": "recipe_name: A\ningredients:\n- Salt:\n    amounts:\nsteps: []\n",
    "ingredients as empty map": "recipe_name: A\ningredients: {}\nsteps: []\n",
    "yaml syntax error": "recipe_name: [\n",
    "yields empty map": "recipe_name: A\nyields: {}\ningredients: []\nsteps: []\n",
    "yields as text": "recipe_name: A\nyields: 4 servings\ningredients: []\nsteps: []\n",
    "source authors map": "recipe_name: A\nsource_authors: {Ann: 1, Bob: 2}\ningredients: []\nsteps: []\n",
    "metric converted": "recipe_name: A\ningredients:\n- Flour:\n    amounts:\n    - amount: 500\n      unit: g\n      extra: kept\n- Milk:\n    amounts:\n    - unit: ml\nsteps: []\n",
    "tricky strings": (
        "recipe_name: '='\n"
        "notes: ['yes', '1e3', '0o17', '017', '1:30', '~', 'null', 'None', '=', '@x', '#x', '- x', 'a: b', \"'q'\", '\"d\"', '']\n"
        "ingredients:\n- '=':\n    amounts:\n    - amount: '='\n      unit: 'on'\n"
        "steps:\n- step: '='\n"
    ),
    "int amount and null unit": "recipe_name: A\ningredients:\n- Eggs:\n    amounts:\n    - amount: 5\n      unit:\nsteps: []\n",
    "list ingredient body": "recipe_name: A\ningredients:\n- Salt: [1, 2]\nsteps: []\n",
    "int source authors": "recipe_name: A\nsource_authors: 7\ningredients: []\nsteps: []\n",
    "string steps": "recipe_name: A\ningredients: []\nsteps: \"Stir.\"\n",
}


def _orf_cases():
    cases = [{"name": p.name, "yaml": p.read_text(encoding="utf-8")}
             for p in sorted(FIXTURES.glob("*.yaml"))]
    cases += [{"name": name, "yaml": text} for name, text in ORF_INLINE.items()]
    for case in cases:
        try:
            case["expected"] = recipe_sync.parse_recipe_yaml(case["yaml"])
        except ValueError as exc:
            message = str(exc)
            case["error"] = message if message.startswith("Missing required") else "*"
        except Exception:
            case["error"] = "*"
    return cases


# -- Recipe editing helpers ------------------------------------------------------

EDIT_DOC = yaml.safe_load(
    "recipe_name: Cake\n"
    "ingredients:\n"
    "- Flour:\n    usda_num: 20081\n    amounts:\n    - amount: 2\n      unit: cup\n    notes:\n    - sifted\n"
    "- Sugar:\n    amounts:\n    - amount: a pinch\n      unit: ''\n    section: Frosting\n"
    "- Butter:\n    amounts:\n    - amount: 1\n      unit: cup\n    - amount: 8\n      unit: oz\n    section: Frosting\n"
    "- Eggs:\n    amounts:\n    - amount: some\n      unit: ''\n    substitutions:\n    - Flax:\n        amounts:\n        - amount: 1\n          unit: tbsp\n        - amount: lots\n          unit: ''\n"
    "steps:\n- step: Mix.\n  notes:\n  - well\n- step: Bake.\n  haccp:\n    control_point: 350F\n"
)


def _form(rows, prefix=""):
    form = {f"{prefix}row_order": ",".join(r["key"] for r in rows)}
    for r in rows:
        key = r["key"]
        form[f"{prefix}row_kind_{key}"] = r["kind"]
        form[f"{prefix}row_name_{key}"] = r["name"]
        form[f"{prefix}row_amount_{key}"] = r.get("amount", "")
        form[f"{prefix}row_unit_{key}"] = r.get("unit", "")
        if r.get("notes") is not None:
            form[f"{prefix}row_notes_{key}"] = r["notes"]
    return form


INGREDIENT_FORMS = {
    "reorder, rename, metric, new row, notes": [
        {"key": "s0", "kind": "section", "name": "Base"},
        {"key": "e1", "kind": "ingredient", "name": "Sugar", "amount": "250", "unit": "ml", "notes": "fine; divided"},
        {"key": "e0", "kind": "ingredient", "name": "Cake flour", "amount": "2", "unit": "cup", "notes": ""},
        {"key": "n1", "kind": "ingredient", "name": "Salt", "amount": "1/2", "unit": "tsp"},
        {"key": "s1", "kind": "section", "name": ""},
        {"key": "e2", "kind": "ingredient", "name": "Butter", "amount": "", "unit": ""},
    ],
    "notes absent keeps them, blank names dropped": [
        {"key": "e0", "kind": "ingredient", "name": "Flour", "amount": "3", "unit": "cups"},
        {"key": "n2", "kind": "ingredient", "name": "   ", "amount": "1", "unit": "cup"},
        {"key": "e9", "kind": "ingredient", "name": "Vanilla", "amount": "1", "unit": "tsp"},
    ],
}

STEP_FORMS = {
    "reorder, split, new, blank": [
        {"key": "e1", "text": "Bake at 350."},
        {"key": "e0", "text": "Mix dry."},
        {"key": "n0", "text": "Then mix wet."},
        {"key": "n1", "text": "   "},
    ],
}


def _step_form(rows):
    form = {"step_order": ",".join(r["key"] for r in rows)}
    for r in rows:
        form[f"step_text_{r['key']}"] = r["text"]
    return form


def _editing_cases():
    docs = {p.name: yaml.safe_load(p.read_text(encoding="utf-8"))
            for p in sorted(FIXTURES.glob("*.yaml"))}
    docs["edit doc"] = EDIT_DOC
    out = {"docs": []}
    for name, doc in docs.items():
        editable = recipe_sync.build_editable_ingredients(doc)
        out["docs"].append({
            "name": name,
            "doc": doc,
            "unparseable": recipe_sync.find_unparseable_amount_slots(doc),
            "editable": editable,
            "editor_rows": web_app._editor_rows(editable),
            "first_yield": recipe_sync.first_yield(doc),
        })

    old = EDIT_DOC["ingredients"]
    out["ingredients_from_form"] = [
        {"name": name, "old": old, "rows": rows,
         "expected": web_app._ingredients_from_form(_form(rows), json.loads(json.dumps(old)))}
        for name, rows in INGREDIENT_FORMS.items()
    ]
    out["steps_from_form"] = [
        {"name": name, "old": EDIT_DOC["steps"], "rows": rows,
         "expected": web_app._steps_from_form(_step_form(rows), json.loads(json.dumps(EDIT_DOC["steps"])))}
        for name, rows in STEP_FORMS.items()
    ]

    corrections = []
    for slot, amount, unit in [(1, "1/4", "tsp"), (3, "250", "ml"), (5, "2", "tbsp")]:
        doc = json.loads(json.dumps(EDIT_DOC))
        recipe_sync.apply_amount_correction(doc, slot, amount, unit)
        corrections.append({"slot_index": slot, "amount": amount, "unit": unit,
                            "expected_ingredients": doc["ingredients"]})
    out["amount_corrections"] = {"doc": EDIT_DOC, "cases": corrections}

    new_ingredient_cases = [
        ["Milk", "250", "ml", None, None],
        ["Flour", "2", "cups", "Dough", {"usda_num": "1", "notes": ["sifted"], "amounts": [{"amount": 9}],
                                          "section": "Old", "processing": [], "substitutions": None}],
        ["Salt", "", "", "", {}],
    ]
    out["build_new_ingredient"] = [
        {"args": args, "expected": recipe_sync.build_new_ingredient(*args)} for args in new_ingredient_cases
    ]
    out["parse_yield_text"] = [
        {"text": t, "expected": recipe_sync.parse_yield_text(t)}
        for t in ["4 servings", "6", "Serves a crowd", "0", "12 Muffins", " 3  cups ", None, "None", "-2", "10pcs"]
    ]
    out["normalize_rating"] = [
        {"value": v, "expected": recipe_sync.normalize_rating(v)}
        for v in [3, "4", " 5 ", "3.0", 0, 6, True, None, "None", "", "+2", "2_0", 2.0, "x"]
    ]
    out["parse_notes_field"] = [
        {"text": t, "expected": recipe_sync.parse_notes_field(t)} for t in ["sifted", "a; b ;", "", None, " ; "]
    ]
    out["slugify"] = [
        {"name": n, "expected": web_app._slugify(n)}
        for n in ["Banana Bread", "Mom's Best!!", "---", "Cr\u00e8me br\u00fbl\u00e9e", "", "Chili 2.0"]
    ]
    with tempfile.TemporaryDirectory() as tmp:
        taken = set()
        names = ["Soup", "soup", "SOUP!", "Stew", "---", "!!!"]
        out["unique_filenames"] = {
            "names": names,
            "expected": [web_app._unique_recipe_path(Path(tmp), n, taken).name for n in names],
        }
    return out


# -- Meal Master -------------------------------------------------------------------

MMF_INLINE = {
    "continuations, notes, units, sections": (
        "MMMMM----- Recipe via Meal-Master (tm) v8.05\n\n"
        "      Title: Pasta Night\n Categories: Main dish, Pasta\n      Yield: 4 Servings\n\n"
        "      1 lb  Spaghetti\n      2 c   Tomato sauce -- warmed\n"
        "      1 ts  Oregano, dried and crumbled very\n            -finely\n"
        "      =====for the topping=====\n      1/2 c  Parmesan\n        Salt\n\n"
        "Boil the pasta.\nDrain it.\n\nToss with sauce.\n\n"
        "Source:\n  \"Grandma's\n  box\"\nNotes:\n  \"Freezes well.\"\nS(\"Italian\")\n  \"Italian\"\n"
        "Yield:\n  \"6 servings\"\nMMMMM\n"
    ),
    "form feed and no instructions": (
        "MMMMM----- Recipe\n\n      Title: Empty\n\n      1 c  Rice\n\x0cMMMMM\n"
    ),
}
# Real Meal Master exports have CRLF line endings.
MMF_INLINE["continuations, notes, units, sections (CRLF)"] = (
    MMF_INLINE["continuations, notes, units, sections"].replace("\n", "\r\n")
)


def _mealmaster_result(text):
    result = meal_master.parse_meal_master(text)
    return {
        "recipes": [{"title": r["title"], "data": yaml.safe_load(r["yaml_text"])} for r in result.recipes],
        "errors": [list(e) for e in result.errors],
    }


def _mealmaster_cases():
    cases = []
    for path in sorted((FIXTURES / "mealmaster").glob("*.mmf")):
        text = path.read_bytes().decode("cp1252", errors="replace")
        cases.append({"file": path.name, "expected": _mealmaster_result(text)})
    for name, text in MMF_INLINE.items():
        cases.append({"name": name, "text": text, "expected": _mealmaster_result(text)})
    return cases


def build_recipe_fixtures() -> dict:
    return {
        "pycompat.json": {
            "title": [{"text": t, "expected": t.title()} for t in TITLE],
            "splitlines": [{"text": t, "expected": t.splitlines()} for t in SPLITLINES],
            "split": [{"text": t, "maxsplit": m, "expected": t.split(None, m)} for t, m in SPLIT],
            "strip": [{"text": t, "expected": t.strip()} for t in STRIP],
            "str": [{"value": v, "expected": str(v)} for v in PY_STR],
            "cp1252": {"bytes": list(CP1252_BYTES), "expected": CP1252_BYTES.decode("cp1252", errors="replace")},
        },
        "yaml_load.json": [dict(text=t, **_load_as_json(t)) for t in YAML_LOAD],
        "orf.json": _orf_cases(),
        "orf_editing.json": _editing_cases(),
        "mealmaster.json": _mealmaster_cases(),
    }
