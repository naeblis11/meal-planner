package com.naeblis11.mealplanner.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phones already hold version-1 and version-2 libraries: opening either with this build
 * must keep everything and add the new tables exactly as a fresh install has them (Room
 * checks every table against the entities as it opens, and throws on a mismatch).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        context.deleteDatabase(AppDatabase.FILE_NAME)
    }

    /** A database exactly as Room created [version], from its exported schema, then [seed]ed. */
    private fun createAt(version: Int, seed: SQLiteDatabase.() -> Unit) {
        val dir = File(System.getProperty("schemaDir") ?: error("schemaDir is not set; run through Gradle"))
        val schema = Json.parseToJsonElement(File(dir, "com.naeblis11.mealplanner.data.AppDatabase/$version.json").readText())
            .jsonObject.getValue("database").jsonObject
        val file = context.getDatabasePath(AppDatabase.FILE_NAME).apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            for (entity in schema.getValue("entities").jsonArray) {
                val e = entity.jsonObject
                val table = e.getValue("tableName").jsonPrimitive.content
                db.execSQL(e.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                e["indices"]?.jsonArray?.forEach { index ->
                    db.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            db.seed()
            db.version = version
        }
    }

    private fun SQLiteDatabase.oneRecipe() {
        execSQL("INSERT INTO recipe (id, recipe_uuid, name, raw_yaml) VALUES (7, 'u-7', 'Soup', 'recipe_name: Soup')")
        execSQL("INSERT INTO recipe_ingredient (recipe_id, order_num, name, amount, unit, amounts_json) VALUES (7, 0, 'Stock', '2', 'cup', '[]')")
        execSQL("INSERT INTO recipe_step (recipe_id, order_num, step_text) VALUES (7, 0, 'Simmer.')")
    }

    @Test
    fun aVersion1LibraryOpensAsVersion3WithEveryRecipeKept() = runTest {
        createAt(1) { oneRecipe() }
        val db = AppDatabase.open(context)
        try {
            assertEquals(listOf("Soup"), db.recipeDao().names())
            assertEquals(listOf("Stock"), db.recipeDao().ingredients(7).map { it.name })
            assertEquals(listOf("Simmer."), db.recipeDao().steps(7).map { it.stepText })
            db.mealPlanDao().put(MealPlanEntity(date = "2026-10-05", slot = "Dinner", recipeId = 7, servings = null))
            assertEquals("Soup", db.mealPlanDao().assignment("2026-10-05", "Dinner")!!.recipeName)
            db.calendarEventDao().record(CalendarEventEntity(calendarId = 1, date = "2026-10-05", slot = "Dinner", eventId = 9, contentHash = "h"))
            assertEquals(listOf(9L), db.calendarEventDao().all().map { it.eventId })
            assertEquals(3, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }

    @Test
    fun aVersion2LibraryOpensAsVersion3WithItsPlanPantryAndListKept() = runTest {
        createAt(2) {
            oneRecipe()
            execSQL("INSERT INTO meal_plan (date, slot, recipe_id, servings) VALUES ('2026-10-05', 'Dinner', 7, '4')")
            execSQL("INSERT INTO pantry_item (name, exact_match, aisle, active, added_on) VALUES ('Salt', 0, NULL, 1, '2026-09-01')")
            execSQL("INSERT INTO shopping_list_item (name, amount, unit, aisle, in_pantry, checked) VALUES ('Milk', '1', 'gal', 'Dairy & Eggs', 0, 1)")
            execSQL("INSERT INTO ingredient_aisle (name, aisle) VALUES ('Milk', 'Dairy & Eggs')")
        }
        val db = AppDatabase.open(context)
        try {
            assertEquals("4", db.mealPlanDao().assignment("2026-10-05", "Dinner")!!.servings)
            assertEquals("2026-09-01", db.pantryDao().byName("salt")!!.addedOn)
            val milk = db.shoppingDao().allInIdOrder().single()
            assertTrue(milk.checked)
            assertEquals("Dairy & Eggs", db.shoppingDao().rememberedAisle("milk"))
            assertEquals(emptyList<CalendarEventEntity>(), db.calendarEventDao().all())
            assertEquals(3, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }

    @Test
    fun withoutTheMigrationOpeningRefusesRatherThanWiping() = runTest {
        createAt(2) { oneRecipe() }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.FILE_NAME).build()
        try {
            assertThrows(IllegalStateException::class.java) { db.openHelper.writableDatabase }
        } finally {
            db.close()
        }
    }
}
