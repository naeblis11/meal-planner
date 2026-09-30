"""Week-based meal calendar: date math and meal_plan queries.

Named meal_calendar.py (not calendar.py) to avoid shadowing Python's
stdlib calendar module.
"""
import sqlite3
from datetime import date, timedelta

SLOTS = ("Breakfast", "Lunch", "Dinner")


def get_week_start(d: date) -> date:
    return d - timedelta(days=d.weekday())


def get_week_dates(week_start: date) -> list[date]:
    return [week_start + timedelta(days=i) for i in range(7)]


def parse_week_param(value: str | None) -> date:
    if value:
        try:
            return get_week_start(date.fromisoformat(value))
        except ValueError:
            pass
    return get_week_start(date.today())


def get_week_plan(conn: sqlite3.Connection, week_start: date) -> dict:
    dates = get_week_dates(week_start)
    date_strs = [d.isoformat() for d in dates]
    plan = {(d, slot): None for d in date_strs for slot in SLOTS}

    placeholders = ", ".join("?" for _ in date_strs)
    rows = conn.execute(
        f"""SELECT mp."date", mp."slot", mp."recipe_id", mp."servings",
                   r."name", r."image_filename"
            FROM "meal_plan" mp
            JOIN "recipe" r ON r."id" = mp."recipe_id"
            WHERE mp."date" IN ({placeholders})""",
        date_strs,
    ).fetchall()

    for row in rows:
        plan[(row["date"], row["slot"])] = {
            "recipe_id": row["recipe_id"],
            "recipe_name": row["name"],
            "recipe_image": row["image_filename"],
            "servings": row["servings"],
        }

    return plan


def assign_meal(
    conn: sqlite3.Connection,
    date_iso: str,
    slot: str,
    recipe_id: int,
    servings: str | None = None,
) -> None:
    try:
        parsed_date = date.fromisoformat(date_iso)
    except ValueError:
        parsed_date = None
    if parsed_date is None or parsed_date.isoformat() != date_iso:
        raise ValueError(f"Invalid date: {date_iso!r}. Must be YYYY-MM-DD.")

    if slot not in SLOTS:
        raise ValueError(f"Invalid slot: {slot!r}. Must be one of {SLOTS}.")

    recipe_exists = conn.execute(
        'SELECT 1 FROM "recipe" WHERE "id" = ?', (recipe_id,)
    ).fetchone()
    if recipe_exists is None:
        raise ValueError(f"No recipe with id {recipe_id}")

    servings = (servings or "").strip() or None

    conn.execute(
        """INSERT INTO "meal_plan" ("date", "slot", "recipe_id", "servings")
           VALUES (?, ?, ?, ?)
           ON CONFLICT("date", "slot") DO UPDATE SET
               "recipe_id" = excluded."recipe_id",
               "servings" = excluded."servings\"""",
        (date_iso, slot, recipe_id, servings),
    )
    conn.commit()


def unassign_meal(conn: sqlite3.Connection, date_iso: str, slot: str) -> None:
    conn.execute(
        'DELETE FROM "meal_plan" WHERE "date" = ? AND "slot" = ?',
        (date_iso, slot),
    )
    conn.commit()
