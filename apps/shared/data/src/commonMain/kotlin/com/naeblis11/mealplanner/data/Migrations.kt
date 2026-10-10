package com.naeblis11.mealplanner.data

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Every schema change since version 1, in order. Never a destructive fallback. These migrations need a
 * SQLiteDriver: without one, Room calls `migrate(SupportSQLiteDatabase)`, which throws, so every builder
 * that opens an existing file must call `setDriver`.
 */
object Migrations {
    /** Plan 4: the meal plan, pantry, shopping list and remembered aisles. Recipes are untouched. */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(connection: SQLiteConnection) {
            V2_TABLES.forEach { connection.execSQL(it) }
        }
    }

    /** Plan 5: calendar_event, the events "Send this week" put on a calendar. Nothing else changes. */
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(connection: SQLiteConnection) {
            V3_TABLES.forEach { connection.execSQL(it) }
        }
    }

    /**
     * Desktop plan 2: which recipe file each row came from (file_name, file_hash; both stay null on
     * Android) and app_meta for one-time markers. Existing rows keep every value.
     */
    val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override fun migrate(connection: SQLiteConnection) {
            V4_CHANGES.forEach { connection.execSQL(it) }
        }
    }

    /** Desktop plan 5: google_event, the events "Send this week" put on a Google calendar. Nothing else changes. */
    val MIGRATION_4_5: Migration = object : Migration(4, 5) {
        override fun migrate(connection: SQLiteConnection) {
            V5_TABLES.forEach { connection.execSQL(it) }
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

    // Byte for byte the createSql of schemas/.../2.json, so a migrated database is the same as a new one.
    private val V2_TABLES = listOf(
        "CREATE TABLE IF NOT EXISTS `meal_plan` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `date` TEXT NOT NULL, `slot` TEXT NOT NULL, `recipe_id` INTEGER NOT NULL, `servings` TEXT, FOREIGN KEY(`recipe_id`) REFERENCES `recipe`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_meal_plan_date_slot` ON `meal_plan` (`date`, `slot`)",
        "CREATE INDEX IF NOT EXISTS `index_meal_plan_recipe_id` ON `meal_plan` (`recipe_id`)",
        "CREATE TABLE IF NOT EXISTS `pantry_item` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL COLLATE NOCASE, `exact_match` INTEGER NOT NULL, `aisle` TEXT, `active` INTEGER NOT NULL, `added_on` TEXT)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_pantry_item_name` ON `pantry_item` (`name`)",
        "CREATE TABLE IF NOT EXISTS `shopping_list_item` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `amount` TEXT, `unit` TEXT, `aisle` TEXT, `in_pantry` INTEGER NOT NULL, `checked` INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `ingredient_aisle` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL COLLATE NOCASE, `aisle` TEXT NOT NULL)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_ingredient_aisle_name` ON `ingredient_aisle` (`name`)",
    )

    // Byte for byte the createSql of schemas/.../3.json, so a migrated database is the same as a new one.
    private val V3_TABLES = listOf(
        "CREATE TABLE IF NOT EXISTS `calendar_event` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `calendar_id` INTEGER NOT NULL, `date` TEXT NOT NULL, `slot` TEXT NOT NULL, `event_id` INTEGER NOT NULL, `content_hash` TEXT NOT NULL)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_calendar_event_calendar_id_date_slot` ON `calendar_event` (`calendar_id`, `date`, `slot`)",
    )

    // What Room's own auto-migration emits for these entity changes. The two column definitions are byte
    // for byte their text in schemas/.../4.json's recipe createSql (Room lists them last, where ALTER TABLE
    // puts them), and app_meta's statement is its createSql there.
    private val V4_CHANGES = listOf(
        "ALTER TABLE `recipe` ADD COLUMN `file_name` TEXT",
        "ALTER TABLE `recipe` ADD COLUMN `file_hash` TEXT",
        "CREATE TABLE IF NOT EXISTS `app_meta` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))",
    )

    // Byte for byte the createSql of schemas/.../5.json, so a migrated database is the same as a new one.
    private val V5_TABLES = listOf(
        "CREATE TABLE IF NOT EXISTS `google_event` (`calendar_id` TEXT NOT NULL, `date` TEXT NOT NULL, `slot` TEXT NOT NULL, `event_id` TEXT NOT NULL, `content_hash` TEXT NOT NULL, PRIMARY KEY(`calendar_id`, `date`, `slot`))",
    )
}
