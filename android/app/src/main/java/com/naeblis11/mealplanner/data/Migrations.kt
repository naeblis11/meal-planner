package com.naeblis11.mealplanner.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Every schema change since version 1, in order. Never a destructive fallback. */
object Migrations {
    /** Plan 4: the meal plan, pantry, shopping list and remembered aisles. Recipes are untouched. */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            V2_TABLES.forEach { db.execSQL(it) }
        }
    }

    /** Plan 5: calendar_event, the events "Send this week" put on a calendar. Nothing else changes. */
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            V3_TABLES.forEach { db.execSQL(it) }
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

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
}
