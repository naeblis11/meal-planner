"""Parse Open Recipe Format YAML and sync it into mealplanner.db."""
import json
import re
import sqlite3
import uuid as uuid_module
from dataclasses import dataclass, field
from pathlib import Path

import yaml

import db
import unit_conversion

NONE_TOKENS = {"none", "None", ""}
_YIELD_NUM_RE = re.compile(r"^(\d+)\s*(.*)$")


def _clean_none(value):
    return None if isinstance(value, str) and value in NONE_TOKENS else value


def patch_yaml_field(raw_yaml: str, field_name: str, value) -> str:
    """Replace (or insert) a single top-level `field_name: ...` line, safely
    YAML-encoding `value` so special characters (colons, quotes, backslashes)
    can never corrupt the surrounding YAML."""
    # Use yaml.safe_dump on a temp dict to safely encode the value, then extract
    # just the line we need (without the document terminator).
    temp_dict = {field_name: value}
    # width=100000 disables PyYAML's default line-wrapping: this function's
    # contract is "exactly one line", and a wrapped value would leave
    # orphaned continuation lines behind when re-patching a field whose
    # current value is already long enough to have wrapped.
    line = yaml.safe_dump(temp_dict, width=100000).rstrip('\n')
    pattern = re.compile(rf"^{re.escape(field_name)}:.*$", re.MULTILINE)
    if pattern.search(raw_yaml):
        # A callable replacement is used (not a plain string) so any
        # backslash in `line` is inserted literally, instead of being
        # interpreted by re.sub as a backreference escape sequence.
        return pattern.sub(lambda _match: line, raw_yaml, count=1)
    return f"{line}\n" + raw_yaml


def _write_uuid_line(raw_yaml: str, new_uuid: str) -> str:
    return patch_yaml_field(raw_yaml, "recipe_uuid", new_uuid)


def _ensure_recipe_uuid(raw_yaml: str) -> str:
    try:
        data = yaml.safe_load(raw_yaml) or {}
    except yaml.YAMLError:
        return raw_yaml

    existing = _clean_none(data.get("recipe_uuid")) if isinstance(data, dict) else None
    if existing:
        return raw_yaml

    return _write_uuid_line(raw_yaml, str(uuid_module.uuid4()))


def reassign_recipe_uuid(file_path: Path) -> None:
    raw_yaml = Path(file_path).read_text(encoding="utf-8")
    Path(file_path).write_text(
        _write_uuid_line(raw_yaml, str(uuid_module.uuid4())), encoding="utf-8"
    )


def find_duplicate_uuids(recipes_dir: Path) -> dict[str, list[str]]:
    uuid_to_files: dict[str, list[str]] = {}
    for path_obj in sorted(Path(recipes_dir).glob("*.yaml")):
        try:
            parsed = parse_recipe_yaml(path_obj.read_text(encoding="utf-8"))
        except Exception:
            continue
        this_uuid = parsed["recipe_uuid"]
        if this_uuid:
            uuid_to_files.setdefault(this_uuid, []).append(path_obj.name)
    return {u: files for u, files in uuid_to_files.items() if len(files) > 1}


def walk_amount_slots(data: dict) -> list:
    """Depth-first walk yielding (ingredient_name, amount_dict, notes) for
    every amounts-list entry across `data["ingredients"]` and their
    (recursive) substitutions, in a fixed, deterministic order.
    `amount_dict` is the actual nested dict object inside `data` --
    assigning to its "amount"/"unit" keys mutates `data` in place. `notes`
    is that ingredient's own notes list (or None)."""
    slots = []

    def _walk_ingredient(ing):
        (name, body), = ing.items()
        body = body or {}
        notes = body.get("notes")
        for amt in body.get("amounts") or []:
            slots.append((name, amt, notes))
        for sub in body.get("substitutions") or []:
            _walk_ingredient(sub)

    for ing in data.get("ingredients") or []:
        _walk_ingredient(ing)
    return slots


def _format_ingredient_line(name: str, amount_dict: dict, notes) -> str:
    """Reconstructs a readable ingredient line (e.g. "1 sm Onion --
    chopped") from already-parsed fields, for showing context on the
    amount-correction screens. Not guaranteed byte-identical to the
    original source line -- see the recipe-subsections design spec."""
    parts = [
        str(p) for p in (amount_dict.get("amount"), amount_dict.get("unit"), name)
        if p is not None and p != ""
    ]
    line = " ".join(parts)
    if notes:
        line += " -- " + ", ".join(str(n) for n in notes)
    return line


def find_unparseable_amount_slots(data: dict) -> list:
    """Returns [{"slot_index", "ingredient_name", "amount", "unit",
    "ingredient_line"}, ...] for every amount slot
    unit_conversion.parse_amount can't parse."""
    issues = []
    for i, (name, amt, notes) in enumerate(walk_amount_slots(data)):
        if unit_conversion.parse_amount(amt.get("amount")) is None:
            issues.append({
                "slot_index": i,
                "ingredient_name": name,
                "amount": amt.get("amount", ""),
                "unit": amt.get("unit", ""),
                "ingredient_line": _format_ingredient_line(name, amt, notes),
            })
    return issues


def apply_amount_correction(data: dict, slot_index: int, amount, unit) -> None:
    """Overwrite the slot_index-th amount slot (by walk_amount_slots order)
    with a corrected amount/unit, converting it to imperial via
    unit_conversion.to_imperial. Mutates `data` in place."""
    slots = walk_amount_slots(data)
    _, amt, _ = slots[slot_index]
    converted_amount, converted_unit = unit_conversion.to_imperial(amount, unit)
    amt["amount"] = converted_amount
    amt["unit"] = converted_unit


def _notes_list(notes) -> list:
    """An ingredient's notes as a list of strings; ORF specifies a list,
    but a hand-written file may carry a bare string or nothing."""
    notes = _clean_none(notes)
    if notes is None:
        return []
    if isinstance(notes, str):
        return [notes]
    return [str(n) for n in notes if _clean_none(n) is not None]


def parse_notes_field(text) -> list:
    """Splits the editor's single note field back into ORF's notes list.
    Several notes are separated with ";" so the common one-note case
    round-trips exactly."""
    return [part.strip() for part in str(text or "").split(";") if part.strip()]


def build_editable_ingredients(data: dict) -> list:
    """Returns [{"index", "name", "amount", "unit", "section", "notes",
    "multi_amount", "needs_input"}, ...] -- one entry per top-level
    ingredient in data["ingredients"], in original order. "index" is that
    ingredient's position in the list, used to address it when saving
    edits. "notes" is the ingredient's ORF notes list (the "sifted" /
    "to taste" line shown under it), always a list. "multi_amount" is
    True when the ingredient has more than one amounts entry (not
    editable through the edit screen). "needs_input" is True when any of
    its amounts (own or substitutions') can't be parsed -- the import
    screen highlights those rows."""
    editable = []
    for i, raw_ing in enumerate(data.get("ingredients") or []):
        (name, body), = raw_ing.items()
        body = body or {}
        amounts = body.get("amounts") or []
        first = amounts[0] if amounts else {}
        slots = walk_amount_slots({"ingredients": [raw_ing]})
        editable.append({
            "index": i,
            "name": name,
            "amount": first.get("amount", ""),
            "unit": first.get("unit", ""),
            "section": body.get("section"),
            "notes": _notes_list(body.get("notes")),
            "multi_amount": len(amounts) > 1,
            "needs_input": any(
                unit_conversion.parse_amount(amt.get("amount")) is None for _, amt, _ in slots
            ),
        })
    return editable


def build_new_ingredient(name: str, amount, unit, section, preserved: dict = None) -> dict:
    """Builds a single-amount ORF ingredient dict, converting amount/unit
    to imperial the same way import and corrections do. `preserved`, if
    given, supplies usda_num/processing/notes/substitutions to carry
    forward unchanged from an existing ingredient being edited (any other
    keys in it, like its old "amounts" or "section", are ignored); a
    brand new ingredient has none of them."""
    converted_amount, converted_unit = unit_conversion.to_imperial(amount, unit)
    body = {"amounts": [{"amount": converted_amount, "unit": converted_unit}]}
    if section:
        body["section"] = section
    if preserved:
        for key in ("usda_num", "processing", "notes", "substitutions"):
            if preserved.get(key):
                body[key] = preserved[key]
    return {name: body}


def _normalize_usda_num(value) -> str | None:
    value = _clean_none(value)
    return None if value is None else str(value)


def _normalize_yield_entry(entry: dict) -> dict:
    if "amount" in entry and "unit" in entry:
        return {"amount": entry["amount"], "unit": entry["unit"]}
    (key, value), = entry.items()
    return {"amount": value, "unit": key}


def _normalize_yields(raw_yields):
    raw_yields = _clean_none(raw_yields)
    return None if raw_yields is None else [_normalize_yield_entry(y) for y in raw_yields]


def first_yield(data: dict) -> dict | None:
    """Returns `{"amount": ..., "unit": ...}` for a raw (not yet fully
    parsed) recipe dict's first yield entry, in whichever of ORF's two
    shapes it was written (`{amount, unit}` or `{unit: amount}`), or
    None if the recipe has no yields at all."""
    yields = _normalize_yields(data.get("yields"))
    return yields[0] if yields else None


def parse_yield_text(text) -> dict | None:
    """Parses a leading integer off free-text yield description (e.g.
    "4 servings" -> {"amount": 4, "unit": "servings"}, "6" -> {"amount":
    6, "unit": "servings"}). Returns None when no leading integer is
    found (e.g. "Serves a crowd") or the parsed amount is not positive."""
    text = _clean_none(text)
    if not text:
        return None
    match = _YIELD_NUM_RE.match(str(text).strip())
    if not match:
        return None
    amount = int(match.group(1))
    if amount <= 0:
        return None
    unit = match.group(2).strip().lower() or "servings"
    return {"amount": amount, "unit": unit}


def _normalize_source_authors(value):
    value = _clean_none(value)
    if value is None:
        return None
    return [value] if isinstance(value, str) else list(value)


def _convert_amounts_to_imperial(amounts: list) -> list:
    converted = []
    for entry in amounts:
        amount, unit = unit_conversion.to_imperial(entry.get("amount", ""), entry.get("unit", ""))
        converted.append({**entry, "amount": amount, "unit": unit})
    return converted


def _normalize_ingredient(raw: dict) -> dict:
    (name, body), = raw.items()
    body = body or {}
    substitutions = body.get("substitutions")
    return {
        "name": name,
        "usda_num": _normalize_usda_num(body.get("usda_num")),
        "amounts": _convert_amounts_to_imperial(body.get("amounts", [])),
        "processing": body.get("processing"),
        "notes": body.get("notes"),
        "section": body.get("section"),
        "substitutions": (
            [_normalize_ingredient(s) for s in substitutions] if substitutions else None
        ),
    }


def _normalize_step(raw: dict) -> dict:
    return {
        "step_text": raw["step"],
        "notes": raw.get("notes"),
        "haccp": raw.get("haccp"),
    }


MAX_RATING = 5


def normalize_rating(value) -> int | None:
    """A recipe's star rating as an int 1..MAX_RATING, or None when it is
    missing, blank, zero, or not a sensible whole number -- a bad value in a
    hand-edited file just means "unrated" rather than a sync error."""
    value = _clean_none(value)
    if value is None or isinstance(value, bool):
        return None
    try:
        rating = int(str(value).strip())
    except ValueError:
        return None
    return rating if 1 <= rating <= MAX_RATING else None


def parse_recipe_yaml(yaml_text: str) -> dict:
    data = yaml.safe_load(yaml_text) or {}

    missing = [f for f in ("recipe_name", "steps", "ingredients") if data.get(f) is None]
    if missing:
        raise ValueError(f"Missing required field(s): {', '.join(missing)}")

    oven_fan = _clean_none(data.get("oven_fan"))
    if oven_fan is True:
        oven_fan = "On"
    elif oven_fan is False:
        oven_fan = "Off"

    return {
        "name": data["recipe_name"],
        "recipe_uuid": _clean_none(data.get("recipe_uuid")),
        "author": _clean_none(data.get("author")),
        "source_authors": _normalize_source_authors(data.get("source_authors")),
        "source_url": _clean_none(data.get("source_url")),
        "source_book": _clean_none(data.get("source_book")),
        "oven_temp": _clean_none(data.get("oven_temp")),
        "oven_fan": oven_fan,
        "oven_time": _clean_none(data.get("oven_time")),
        "yields": _normalize_yields(data.get("yields")),
        "notes": _clean_none(data.get("notes")),
        "category": _clean_none(data.get("category")),
        "subcategory": _clean_none(data.get("subcategory")),
        "image": _clean_none(data.get("image")),
        "rating": normalize_rating(data.get("rating")),
        "ingredients": [_normalize_ingredient(i) for i in data["ingredients"]],
        "steps": [_normalize_step(s) for s in data["steps"]],
    }


@dataclass
class SyncResult:
    indexed: int = 0
    unchanged: int = 0
    removed: int = 0
    errors: list[tuple[str, str]] = field(default_factory=list)
    indexed_files: list[str] = field(default_factory=list)


def _dump(value) -> str | None:
    return None if value is None else json.dumps(value)


def _write_recipe(conn: sqlite3.Connection, file_path: str, file_mtime: float,
                   raw_yaml: str, parsed: dict) -> int:
    existing = conn.execute(
        "SELECT id FROM recipe WHERE recipe_uuid = ?", (parsed["recipe_uuid"],)
    ).fetchone()
    if existing is None:
        existing = conn.execute(
            "SELECT id FROM recipe WHERE file_path = ?", (file_path,)
        ).fetchone()

    columns = {
        "file_path": file_path,
        "file_mtime": file_mtime,
        "recipe_uuid": parsed["recipe_uuid"],
        "name": parsed["name"],
        "author": parsed["author"],
        "source_authors_json": _dump(parsed["source_authors"]),
        "source_url": parsed["source_url"],
        "source_book_json": _dump(parsed["source_book"]),
        "oven_temp_json": _dump(parsed["oven_temp"]),
        "oven_fan": parsed["oven_fan"],
        "oven_time": None if parsed["oven_time"] is None else str(parsed["oven_time"]),
        "yields_json": _dump(parsed["yields"]),
        "notes_json": _dump(parsed["notes"]),
        "category": parsed["category"],
        "subcategory": parsed["subcategory"],
        "image_filename": parsed["image"],
        "rating": parsed["rating"],
        "raw_yaml": raw_yaml,
    }

    if existing is not None:
        recipe_id = existing[0]
        conn.execute("DELETE FROM recipe_ingredient WHERE recipe_id = ?", (recipe_id,))
        conn.execute("DELETE FROM recipe_step WHERE recipe_id = ?", (recipe_id,))
        set_clause = ", ".join(f'"{c}" = ?' for c in columns)
        conn.execute(
            f'UPDATE recipe SET {set_clause} WHERE id = ?',
            (*columns.values(), recipe_id),
        )
    else:
        col_names = ", ".join(f'"{c}"' for c in columns)
        placeholders = ", ".join("?" for _ in columns)
        cursor = conn.execute(
            f'INSERT INTO recipe ({col_names}) VALUES ({placeholders})',
            tuple(columns.values()),
        )
        recipe_id = cursor.lastrowid

    for order_num, ing in enumerate(parsed["ingredients"]):
        first_amount = ing["amounts"][0] if ing["amounts"] else {}
        conn.execute(
            """INSERT INTO recipe_ingredient
               ("recipe_id", "order_num", "name", "usda_num", "amount", "unit", "section", "amounts_json",
                "processing_json", "ingredient_notes_json", "substitutions_json")
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (
                recipe_id, order_num, ing["name"], ing["usda_num"],
                first_amount.get("amount"), first_amount.get("unit"), ing["section"],
                json.dumps(ing["amounts"]),
                _dump(ing["processing"]), _dump(ing["notes"]), _dump(ing["substitutions"]),
            ),
        )

    for order_num, step in enumerate(parsed["steps"]):
        conn.execute(
            """INSERT INTO recipe_step
               ("recipe_id", "order_num", "step_text", "step_notes_json", "haccp_json")
               VALUES (?, ?, ?, ?, ?)""",
            (recipe_id, order_num, step["step_text"], _dump(step["notes"]), _dump(step["haccp"])),
        )

    return recipe_id


def sync_recipes(recipes_dir: Path, db_path: Path) -> SyncResult:
    result = SyncResult()
    conn = db.get_connection(db_path)
    try:
        # Sorted: filesystem order differs by platform (NTFS alphabetical, Linux
        # arbitrary), and which file wins a recipe_uuid conflict must not.
        disk_files = {p.name: p for p in sorted(Path(recipes_dir).glob("*.yaml"))}

        uuid_to_file = {
            row["recipe_uuid"]: row["file_path"]
            for row in conn.execute(
                'SELECT "recipe_uuid", "file_path" FROM "recipe" WHERE "recipe_uuid" IS NOT NULL'
            ).fetchall()
        }

        for file_name, path_obj in disk_files.items():
            mtime = path_obj.stat().st_mtime
            row = conn.execute(
                "SELECT file_mtime FROM recipe WHERE file_path = ?", (file_name,)
            ).fetchone()
            if row is not None and row[0] == mtime:
                result.unchanged += 1
                continue

            try:
                raw_yaml = path_obj.read_text(encoding="utf-8")
                patched = _ensure_recipe_uuid(raw_yaml)
                if patched != raw_yaml:
                    path_obj.write_text(patched, encoding="utf-8")
                    raw_yaml = patched
                    mtime = path_obj.stat().st_mtime
                parsed = parse_recipe_yaml(raw_yaml)
            except Exception as exc:
                result.errors.append((file_name, str(exc)))
                continue

            this_uuid = parsed["recipe_uuid"]
            existing_owner = uuid_to_file.get(this_uuid)
            if existing_owner is not None and existing_owner != file_name and existing_owner in disk_files:
                result.errors.append(
                    (file_name, f"Duplicate recipe_uuid {this_uuid!r} (also used by {existing_owner!r})")
                )
                continue
            uuid_to_file[this_uuid] = file_name

            _write_recipe(conn, file_name, mtime, raw_yaml, parsed)
            result.indexed += 1
            result.indexed_files.append(file_name)

        db_file_names = {
            row[0] for row in conn.execute("SELECT file_path FROM recipe").fetchall()
        }
        for stale_name in db_file_names - set(disk_files.keys()):
            recipe_id = conn.execute(
                "SELECT id FROM recipe WHERE file_path = ?", (stale_name,)
            ).fetchone()[0]
            conn.execute("DELETE FROM meal_plan WHERE recipe_id = ?", (recipe_id,))
            conn.execute("DELETE FROM recipe_ingredient WHERE recipe_id = ?", (recipe_id,))
            conn.execute("DELETE FROM recipe_step WHERE recipe_id = ?", (recipe_id,))
            conn.execute("DELETE FROM recipe WHERE id = ?", (recipe_id,))
            result.removed += 1

        conn.commit()
    finally:
        conn.close()

    return result
