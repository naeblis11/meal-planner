"""Shopping list generation: aggregate a week's recipe ingredients, bucket by pantry."""
import json
import sqlite3
from datetime import date
from fractions import Fraction

import grocery_categories
import meal_calendar
import unit_conversion


def _planned_ratio(servings, yields_json) -> Fraction:
    """Scale factor for one planned meal, from its servings vs the recipe's yield."""
    if not servings:
        return Fraction(1)
    try:
        yields = json.loads(yields_json) if yields_json else None
    except (TypeError, ValueError):
        return Fraction(1)
    if not yields:
        return Fraction(1)
    return unit_conversion.servings_ratio(servings, yields[0].get("amount"))


def matches_pantry_item(pantry_row, ingredient_name: str) -> bool:
    """Whether a pantry row covers an ingredient line, honouring its match mode."""
    pantry_name = pantry_row["name"].lower()
    ingredient_lower = ingredient_name.lower()
    if pantry_row["exact_match"]:
        return pantry_name == ingredient_lower
    return pantry_name in ingredient_lower


def generate(conn: sqlite3.Connection, week_start: date) -> None:
    """Adds the week's planned ingredients to the shopping list, combining
    them with anything already on it. Never removes a row: the list is
    the user's running cart, emptied only by clear()/remove_item()."""
    dates = meal_calendar.get_week_dates(week_start)
    date_strs = [d.isoformat() for d in dates]
    date_placeholders = ", ".join("?" for _ in date_strs)

    # One row per planned meal, not per distinct recipe: the same dish planned
    # for two nights needs buying for twice, and each night can be planned at
    # its own serving size.
    assignments = conn.execute(
        f"""SELECT mp."recipe_id", mp."servings", r."yields_json"
            FROM "meal_plan" mp
            JOIN "recipe" r ON r."id" = mp."recipe_id"
            WHERE mp."date" IN ({date_placeholders})""",
        date_strs,
    ).fetchall()

    pantry_items = conn.execute(
        'SELECT "name", "exact_match" FROM "pantry_item"'
    ).fetchall()
    known_aisles = {
        row["name"].lower(): row["aisle"]
        for row in conn.execute('SELECT "name", "aisle" FROM "ingredient_aisle"').fetchall()
    }

    lines = []
    for assignment in assignments:
        ratio = _planned_ratio(assignment["servings"], assignment["yields_json"])
        rows = conn.execute(
            'SELECT "name", "amount", "unit" FROM "recipe_ingredient" WHERE "recipe_id" = ?',
            (assignment["recipe_id"],),
        ).fetchall()
        for row in rows:
            lines.append(
                (
                    row["name"],
                    unit_conversion.scale_amount_text(row["amount"], ratio),
                    row["unit"],
                )
            )
    lines = unit_conversion.combine_lines(lines)

    # Additive: the list is only ever emptied by the user (clear / remove),
    # never by generating. A generated ingredient folds into a row already
    # on the list when their amounts can be combined; otherwise it gets a
    # row of its own.
    existing = conn.execute(
        'SELECT "id", "name", "amount", "unit", "aisle" FROM "shopping_list_item"'
    ).fetchall()
    for name, amount, unit in lines:
        in_pantry = any(matches_pantry_item(item, name) for item in pantry_items)
        # A remembered manual correction always wins; otherwise start the
        # item off with a keyword best-guess instead of leaving it blank.
        aisle = known_aisles.get(name.lower()) or grocery_categories.categorize(name)

        merged = None
        for row in existing:
            if row["name"].lower() != name.lower():
                continue
            merged = _merge_amounts(row, amount, unit)
            if merged is not None:
                # What needs buying just changed, so the row is no longer
                # "bought"; a blank aisle is only ever "uncategorized", so
                # give it the same guess a fresh row would get.
                conn.execute(
                    """UPDATE "shopping_list_item"
                       SET "amount" = ?, "unit" = ?, "aisle" = ?, "in_pantry" = ?, "checked" = 0
                       WHERE "id" = ?""",
                    (merged[0], merged[1], row["aisle"] or aisle, 1 if in_pantry else 0, row["id"]),
                )
                break
        if merged is not None:
            continue
        conn.execute(
            """INSERT INTO "shopping_list_item"
               ("name", "amount", "unit", "aisle", "in_pantry", "checked")
               VALUES (?, ?, ?, ?, ?, 0)""",
            (name, amount, unit, aisle, 1 if in_pantry else 0),
        )
    conn.commit()


def _merge_amounts(row, amount, unit):
    """(amount, unit) for `row` after folding a new line of the same
    ingredient into it, or None when the two can't be combined (e.g.
    cups vs pounds) and the new line needs its own row. An amountless
    side (a hand-added "olive oil") simply takes the other's amount."""
    if row["amount"] is None:
        return (amount, unit)
    if amount is None:
        return (row["amount"], row["unit"])
    combined = unit_conversion.combine_lines(
        [(row["name"], row["amount"], row["unit"]), (row["name"], amount, unit)]
    )
    if len(combined) != 1:
        return None
    return (combined[0][1], combined[0][2])


def add_item(conn: sqlite3.Connection, name: str, aisle: str = None, amount: str = None, unit: str = None) -> tuple:
    """Puts a single named item on the list by hand -- from the pantry
    page or by voice. Returns a `(status, item_id)` pair. `status` is
    "added" for a new row; "merged" when an amount was given and folded
    into a row already there (which is then un-checked, since what needs
    buying changed); "duplicate" when the name is already on the list
    and there was no amount to add. `item_id` is the row that was added,
    merged into, or (for a duplicate) the existing row -- the same name
    can sit on the list twice with incompatible units ("flour 2 cups"
    and "flour 1 lb"), so callers must not re-find the row by name. Same-
    name rows are tried oldest first, so which one an amount merges into
    is deterministic. The aisle is the one given, else the one remembered
    for that ingredient, else a keyword guess. Like every other list item
    it stays until it is removed or the list is cleared -- adding a
    week's meals never drops it."""
    name = (name or "").strip()
    if not name:
        raise ValueError("Shopping list item name cannot be empty")
    amount = (amount or "").strip() or None
    unit = (unit or "").strip() or None

    existing = conn.execute(
        'SELECT "id", "name", "amount", "unit" FROM "shopping_list_item" '
        'WHERE "name" = ? COLLATE NOCASE ORDER BY "id"',
        (name,),
    ).fetchall()
    if existing and amount is None:
        return ("duplicate", existing[0]["id"])
    for row in existing:
        merged = _merge_amounts(row, amount, unit)
        if merged is not None:
            conn.execute(
                'UPDATE "shopping_list_item" SET "amount" = ?, "unit" = ?, "checked" = 0 WHERE "id" = ?',
                (merged[0], merged[1], row["id"]),
            )
            conn.commit()
            return ("merged", row["id"])

    aisle = (aisle or "").strip() or None
    if aisle is None:
        remembered = conn.execute(
            'SELECT "aisle" FROM "ingredient_aisle" WHERE "name" = ? COLLATE NOCASE', (name,)
        ).fetchone()
        aisle = remembered["aisle"] if remembered else grocery_categories.categorize(name)

    cursor = conn.execute(
        """INSERT INTO "shopping_list_item"
           ("name", "amount", "unit", "aisle", "in_pantry", "checked")
           VALUES (?, ?, ?, ?, 0, 0)""",
        (name, amount, unit, aisle),
    )
    conn.commit()
    return ("added", cursor.lastrowid)


def list_items(conn: sqlite3.Connection) -> list:
    return conn.execute(
        """SELECT * FROM "shopping_list_item"
           ORDER BY "in_pantry" ASC, "name" COLLATE NOCASE ASC"""
    ).fetchall()


def toggle_checked(conn: sqlite3.Connection, item_id: int) -> None:
    conn.execute(
        'UPDATE "shopping_list_item" SET "checked" = 1 - "checked" WHERE "id" = ?',
        (item_id,),
    )
    conn.commit()


def clear(conn: sqlite3.Connection) -> None:
    """Empty the shopping list. This is the only way the list ever empties:
    adding a week's meals is additive, so the user clears it by hand once
    the shopping is done. Not a permanent exclusion of anything -- adding
    the week again brings its ingredients back."""
    _tombstone_mirrored(conn, 'SELECT "ha_uid" FROM "shopping_list_item" WHERE "ha_uid" IS NOT NULL')
    conn.execute('DELETE FROM "shopping_list_item"')
    conn.commit()


def remove_item(conn: sqlite3.Connection, item_id: int) -> None:
    """Remove an item from the list. Not a permanent exclusion: adding a
    week's meals again puts the item back if it's still an ingredient."""
    _tombstone_mirrored(
        conn, 'SELECT "ha_uid" FROM "shopping_list_item" WHERE "id" = ? AND "ha_uid" IS NOT NULL',
        (item_id,),
    )
    conn.execute('DELETE FROM "shopping_list_item" WHERE "id" = ?', (item_id,))
    conn.commit()


def _tombstone_mirrored(conn: sqlite3.Connection, query: str, params: tuple = ()) -> None:
    """Remember the Home Assistant uids of rows about to be deleted here,
    so the mirror removes them from HA instead of adopting them back as
    "new in HA" on its next pass."""
    for row in conn.execute(query, params).fetchall():
        conn.execute('INSERT OR IGNORE INTO "ha_tombstone" ("uid") VALUES (?)', (row["ha_uid"],))


def set_aisle(conn: sqlite3.Connection, item_id: int, aisle: str) -> None:
    """Set an item's aisle and remember it for that ingredient's future shopping lists."""
    aisle = (aisle or "").strip() or None
    row = conn.execute(
        'SELECT "name" FROM "shopping_list_item" WHERE "id" = ?', (item_id,)
    ).fetchone()
    if row is None:
        return

    conn.execute(
        'UPDATE "shopping_list_item" SET "aisle" = ? WHERE "id" = ?', (aisle, item_id)
    )
    if aisle:
        conn.execute(
            'INSERT INTO "ingredient_aisle" ("name", "aisle") VALUES (?, ?) '
            'ON CONFLICT("name") DO UPDATE SET "aisle" = excluded."aisle"',
            (row["name"], aisle),
        )
    else:
        conn.execute('DELETE FROM "ingredient_aisle" WHERE "name" = ?', (row["name"],))
    conn.commit()


# ---- Home Assistant mirror (see ha_sync.py) -------------------------------

def sync_rows(conn: sqlite3.Connection) -> list:
    """The rows the Home Assistant to-do list mirrors: everything that
    needs buying. "Already in My Kitchen" rows stay out of the store list."""
    return conn.execute(
        'SELECT "id", "name", "amount", "unit", "aisle", "checked", "ha_uid" '
        'FROM "shopping_list_item" WHERE "in_pantry" = 0 ORDER BY "id"'
    ).fetchall()


def set_ha_uid(conn: sqlite3.Connection, item_id: int, ha_uid) -> None:
    conn.execute('UPDATE "shopping_list_item" SET "ha_uid" = ? WHERE "id" = ?', (ha_uid, item_id))
    conn.commit()


def set_checked(conn: sqlite3.Connection, item_id: int, checked: bool) -> None:
    conn.execute(
        'UPDATE "shopping_list_item" SET "checked" = ? WHERE "id" = ?', (1 if checked else 0, item_id)
    )
    conn.commit()


def update_fields(conn: sqlite3.Connection, item_id: int, *, name: str, amount, unit) -> None:
    """An item was renamed or re-quantified in Home Assistant."""
    conn.execute(
        'UPDATE "shopping_list_item" SET "name" = ?, "amount" = ?, "unit" = ? WHERE "id" = ?',
        (name, amount or None, unit or None, item_id),
    )
    conn.commit()


def insert_from_ha(conn: sqlite3.Connection, name: str, amount, unit, aisle, *, checked: bool,
                   ha_uid: str) -> int:
    """A row for an item that first appeared in Home Assistant. A plain
    insert, deliberately not add_item(): merging into a same-name row
    would leave HA's item mapped to nothing and be re-adopted (and
    re-merged) on every pass. The aisle is HA's description if it gave
    one, else remembered, else guessed."""
    aisle = (aisle or "").strip() or None
    if aisle is None:
        remembered = conn.execute(
            'SELECT "aisle" FROM "ingredient_aisle" WHERE "name" = ? COLLATE NOCASE', (name,)
        ).fetchone()
        aisle = remembered["aisle"] if remembered else grocery_categories.categorize(name)
    cursor = conn.execute(
        """INSERT INTO "shopping_list_item"
           ("name", "amount", "unit", "aisle", "in_pantry", "checked", "ha_uid")
           VALUES (?, ?, ?, ?, 0, ?, ?)""",
        (name, amount or None, unit or None, aisle, 1 if checked else 0, ha_uid),
    )
    conn.commit()
    return cursor.lastrowid


def tombstones(conn: sqlite3.Connection) -> set:
    return {row["uid"] for row in conn.execute('SELECT "uid" FROM "ha_tombstone"').fetchall()}


def forget_tombstones(conn: sqlite3.Connection, uids) -> None:
    conn.executemany('DELETE FROM "ha_tombstone" WHERE "uid" = ?', [(u,) for u in uids])
    conn.commit()


def remove_item_from_ha(conn: sqlite3.Connection, item_id: int) -> None:
    """Home Assistant already dropped this item, so no tombstone is needed."""
    conn.execute('DELETE FROM "shopping_list_item" WHERE "id" = ?', (item_id,))
    conn.commit()
