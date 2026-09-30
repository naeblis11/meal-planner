"""Presence-only pantry item tracking: add, activate/deactivate, delete, list."""
import sqlite3
from datetime import date


def parse_added_on(value) -> str | None:
    """Normalises a user-entered date to ISO YYYY-MM-DD; blank means "no
    date". Raises ValueError for anything that is not a real date."""
    text = (value or "").strip()
    if not text:
        return None
    try:
        return date.fromisoformat(text).isoformat()
    except ValueError:
        raise ValueError(f"Not a valid date: {text!r}") from None


def add_item(conn: sqlite3.Connection, name: str, aisle: str = None, added_on: str = None) -> bool:
    """Adds a pantry item, stamped with today's date (or `added_on`, ISO
    YYYY-MM-DD). Returns False, changing nothing, when the name is
    already in the pantry."""
    name = name.strip()
    if not name:
        raise ValueError("Pantry item name cannot be empty")
    aisle = (aisle or "").strip() or None
    added_on = parse_added_on(added_on) or date.today().isoformat()

    cursor = conn.execute(
        'INSERT OR IGNORE INTO "pantry_item" ("name", "aisle", "added_on") VALUES (?, ?, ?)',
        (name, aisle, added_on),
    )
    conn.commit()
    return cursor.rowcount > 0


def set_active(conn: sqlite3.Connection, item_id: int, active: bool) -> None:
    """Marks an item on hand or not. Putting an item back restamps its
    added-on date, since it is going into the pantry afresh."""
    if active:
        conn.execute(
            'UPDATE "pantry_item" SET "active" = 1, "added_on" = ? WHERE "id" = ? AND "active" = 0',
            (date.today().isoformat(), item_id),
        )
    else:
        conn.execute('UPDATE "pantry_item" SET "active" = 0 WHERE "id" = ?', (item_id,))
    conn.commit()


def set_added_on(conn: sqlite3.Connection, item_id: int, added_on) -> None:
    """Overrides when an item was added; blank clears the date. Raises
    ValueError for a malformed date."""
    conn.execute(
        'UPDATE "pantry_item" SET "added_on" = ? WHERE "id" = ?',
        (parse_added_on(added_on), item_id),
    )
    conn.commit()


def toggle_exact_match(conn: sqlite3.Connection, item_id: int) -> None:
    conn.execute(
        'UPDATE "pantry_item" SET "exact_match" = 1 - "exact_match" WHERE "id" = ?',
        (item_id,),
    )
    conn.commit()


def set_aisle(conn: sqlite3.Connection, item_id: int, aisle: str) -> None:
    aisle = (aisle or "").strip() or None
    conn.execute(
        'UPDATE "pantry_item" SET "aisle" = ? WHERE "id" = ?', (aisle, item_id)
    )
    conn.commit()


def delete_item(conn: sqlite3.Connection, item_id: int) -> None:
    conn.execute('DELETE FROM "pantry_item" WHERE "id" = ?', (item_id,))
    conn.commit()


def list_items(conn: sqlite3.Connection) -> list:
    return conn.execute(
        'SELECT "id", "name", "exact_match", "aisle", "active", "added_on" '
        'FROM "pantry_item" ORDER BY "name" COLLATE NOCASE'
    ).fetchall()
