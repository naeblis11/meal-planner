"""SQLite schema and connection management for mealplanner.db."""
import sqlite3
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS "recipe" (
    "id" INTEGER PRIMARY KEY,
    "file_path" TEXT UNIQUE NOT NULL,
    "file_mtime" REAL NOT NULL,
    "recipe_uuid" TEXT,
    "name" TEXT NOT NULL,
    "author" TEXT,
    "source_authors_json" TEXT,
    "source_url" TEXT,
    "source_book_json" TEXT,
    "oven_temp_json" TEXT,
    "oven_fan" TEXT,
    "oven_time" TEXT,
    "yields_json" TEXT,
    "notes_json" TEXT,
    "category" TEXT,
    "subcategory" TEXT,
    "image_filename" TEXT,
    "rating" INTEGER,
    "raw_yaml" TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS "recipe_ingredient" (
    "id" INTEGER PRIMARY KEY,
    "recipe_id" INTEGER NOT NULL REFERENCES "recipe"("id"),
    "order_num" INTEGER NOT NULL,
    "name" TEXT NOT NULL,
    "usda_num" TEXT,
    "amount" TEXT,
    "unit" TEXT,
    "section" TEXT,
    "amounts_json" TEXT NOT NULL,
    "processing_json" TEXT,
    "ingredient_notes_json" TEXT,
    "substitutions_json" TEXT
);

CREATE TABLE IF NOT EXISTS "recipe_step" (
    "id" INTEGER PRIMARY KEY,
    "recipe_id" INTEGER NOT NULL REFERENCES "recipe"("id"),
    "order_num" INTEGER NOT NULL,
    "step_text" TEXT NOT NULL,
    "step_notes_json" TEXT,
    "haccp_json" TEXT
);

CREATE INDEX IF NOT EXISTS "idx_recipe_ingredient_recipe_id" ON "recipe_ingredient"("recipe_id");
CREATE INDEX IF NOT EXISTS "idx_recipe_step_recipe_id" ON "recipe_step"("recipe_id");

CREATE TABLE IF NOT EXISTS "meal_plan" (
    "id" INTEGER PRIMARY KEY,
    "date" TEXT NOT NULL,
    "slot" TEXT NOT NULL,
    "recipe_id" INTEGER NOT NULL REFERENCES "recipe"("id"),
    -- How many servings this particular night is planned at. NULL means
    -- "whatever the recipe's own yield says"; the recipe's stored amounts
    -- are never rewritten to match.
    "servings" TEXT,
    UNIQUE("date", "slot")
);

CREATE INDEX IF NOT EXISTS "idx_meal_plan_date" ON "meal_plan"("date");

CREATE TABLE IF NOT EXISTS "pantry_item" (
    "id" INTEGER PRIMARY KEY,
    "name" TEXT UNIQUE NOT NULL COLLATE NOCASE,
    "exact_match" INTEGER NOT NULL DEFAULT 0,
    "aisle" TEXT,
    "active" INTEGER NOT NULL DEFAULT 1,
    "added_on" TEXT
);

CREATE TABLE IF NOT EXISTS "shopping_list_item" (
    "id" INTEGER PRIMARY KEY,
    "name" TEXT NOT NULL,
    "amount" TEXT,
    "unit" TEXT,
    "aisle" TEXT,
    "in_pantry" INTEGER NOT NULL DEFAULT 0,
    "checked" INTEGER NOT NULL DEFAULT 0,
    "ha_uid" TEXT
);

CREATE TABLE IF NOT EXISTS "ha_tombstone" (
    "uid" TEXT PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS "ingredient_aisle" (
    "id" INTEGER PRIMARY KEY,
    "name" TEXT UNIQUE NOT NULL COLLATE NOCASE,
    "aisle" TEXT NOT NULL
);

-- Events this app has put on a Google calendar, so a later push can update
-- or remove the one it made rather than adding a second. Keyed by day and
-- slot rather than by meal_plan row: the row is gone by the time we need to
-- delete its event. Never a record of what is ON the calendar -- the family
-- edits that freely and we only ever touch ids we put here ourselves.
CREATE TABLE IF NOT EXISTS "gcal_event" (
    "calendar_id" TEXT NOT NULL,
    "date" TEXT NOT NULL,
    "slot" TEXT NOT NULL,
    "event_id" TEXT NOT NULL,
    -- What we last wrote, so an unchanged meal costs no API call.
    "content_hash" TEXT NOT NULL,
    PRIMARY KEY("calendar_id", "date", "slot")
);
"""


def init_db(conn: sqlite3.Connection) -> None:
    conn.executescript(SCHEMA)
    _migrate(conn)
    conn.commit()


def _migrate(conn: sqlite3.Connection) -> None:
    # CREATE TABLE IF NOT EXISTS never adds columns to an already-existing
    # table, so a real (already-populated) database needs this to pick up
    # columns added after it was first created.
    try:
        conn.execute('ALTER TABLE "pantry_item" ADD COLUMN "exact_match" INTEGER NOT NULL DEFAULT 0')
    except sqlite3.OperationalError as exc:
        if "duplicate column name" not in str(exc):
            raise
    try:
        conn.execute('ALTER TABLE "recipe" ADD COLUMN "category" TEXT')
    except sqlite3.OperationalError as exc:
        if "duplicate column name" not in str(exc):
            raise
    try:
        conn.execute('ALTER TABLE "recipe" ADD COLUMN "subcategory" TEXT')
    except sqlite3.OperationalError as exc:
        if "duplicate column name" not in str(exc):
            raise
    try:
        conn.execute('ALTER TABLE "pantry_item" ADD COLUMN "aisle" TEXT')
    except sqlite3.OperationalError as exc:
        if "duplicate column name" not in str(exc):
            raise
    try:
        conn.execute('ALTER TABLE "shopping_list_item" ADD COLUMN "aisle" TEXT')
    except sqlite3.OperationalError as exc:
        if "duplicate column name" not in str(exc):
            raise
    try:
        conn.execute('ALTER TABLE "pantry_item" ADD COLUMN "active" INTEGER NOT NULL DEFAULT 1')
    except sqlite3.OperationalError as exc:
        if "duplicate column name" not in str(exc):
            raise
    try:
        # Home Assistant's uid for the mirrored to-do item; NULL until pushed.
        conn.execute('ALTER TABLE "shopping_list_item" ADD COLUMN "ha_uid" TEXT')
    except sqlite3.OperationalError as exc:
        if "duplicate column name" not in str(exc):
            raise
    try:
        # ISO date (YYYY-MM-DD) the item went into the pantry. Items from
        # before this column existed keep NULL rather than a made-up date.
        conn.execute('ALTER TABLE "pantry_item" ADD COLUMN "added_on" TEXT')
    except sqlite3.OperationalError as exc:
        if "duplicate column name" not in str(exc):
            raise
    try:
        conn.execute('ALTER TABLE "recipe_ingredient" ADD COLUMN "section" TEXT')
    except sqlite3.OperationalError as exc:
        if "duplicate column name" not in str(exc):
            raise
    try:
        conn.execute('ALTER TABLE "recipe" ADD COLUMN "image_filename" TEXT')
    except sqlite3.OperationalError as exc:
        if "duplicate column name" not in str(exc):
            raise
    try:
        # 1-5 stars from the recipe's `rating:` line; NULL when unrated.
        conn.execute('ALTER TABLE "recipe" ADD COLUMN "rating" INTEGER')
    except sqlite3.OperationalError as exc:
        if "duplicate column name" not in str(exc):
            raise
    try:
        conn.execute('ALTER TABLE "meal_plan" ADD COLUMN "servings" TEXT')
    except sqlite3.OperationalError as exc:
        if "duplicate column name" not in str(exc):
            raise


def get_connection(db_path: Path) -> sqlite3.Connection:
    conn = sqlite3.connect(str(db_path))
    conn.row_factory = sqlite3.Row
    init_db(conn)
    return conn
