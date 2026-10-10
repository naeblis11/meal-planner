package com.naeblis11.mealplanner.data

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DesktopDatabaseTest {
    private val dir: File = Files.createTempDirectory("mp-db").toFile()
    private val file = File(dir, AppDatabase.FILE_NAME)

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun aFreshDesktopDatabaseOpensEmpty() = runTest {
        val db = AppDatabase.openAt(file)
        try {
            assertEquals(emptyList<String>(), db.recipeDao().names())
        } finally {
            db.close()
        }
    }

    @Test
    fun aVersion2DatabaseMigratesOnTheDesktopDriver() = runTest {
        val schemaDir = File(System.getProperty("schemaDir") ?: error("schemaDir is not set; run through Gradle"))
        val schema = Json.parseToJsonElement(File(schemaDir, "com.naeblis11.mealplanner.data.AppDatabase/2.json").readText())
            .jsonObject.getValue("database").jsonObject
        BundledSQLiteDriver().open(file.absolutePath).also { c ->
            for (entity in schema.getValue("entities").jsonArray) {
                val e = entity.jsonObject
                val table = e.getValue("tableName").jsonPrimitive.content
                c.execSQL(e.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                e["indices"]?.jsonArray?.forEach { index ->
                    c.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            schema.getValue("setupQueries").jsonArray.forEach { c.execSQL(it.jsonPrimitive.content) }
            c.execSQL("INSERT INTO recipe (id, recipe_uuid, name, raw_yaml) VALUES (7, 'u-7', 'Soup', 'recipe_name: Soup')")
            c.execSQL("INSERT INTO shopping_list_item (name, amount, unit, aisle, in_pantry, checked) VALUES ('Milk', '1', 'gal', 'Dairy & Eggs', 0, 1)")
            c.execSQL("PRAGMA user_version = 2")
        }.close()

        val db = AppDatabase.openAt(file)
        try {
            assertEquals(listOf("Soup"), db.recipeDao().names())
            assertTrue(db.shoppingDao().allInIdOrder().single().checked)
            assertEquals(emptyList<CalendarEventEntity>(), db.calendarEventDao().all())
        } finally {
            db.close()
        }
    }

    @Test
    fun aVersion4DatabaseMigratesToVersion5OnTheDesktopDriver() = runTest {
        val schemaDir = File(System.getProperty("schemaDir") ?: error("schemaDir is not set; run through Gradle"))
        val schema = Json.parseToJsonElement(File(schemaDir, "com.naeblis11.mealplanner.data.AppDatabase/4.json").readText())
            .jsonObject.getValue("database").jsonObject
        BundledSQLiteDriver().open(file.absolutePath).also { c ->
            for (entity in schema.getValue("entities").jsonArray) {
                val e = entity.jsonObject
                val table = e.getValue("tableName").jsonPrimitive.content
                c.execSQL(e.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                e["indices"]?.jsonArray?.forEach { index ->
                    c.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            schema.getValue("setupQueries").jsonArray.forEach { c.execSQL(it.jsonPrimitive.content) }
            c.execSQL("INSERT INTO recipe (id, recipe_uuid, name, raw_yaml, file_name, file_hash) VALUES (7, 'u-7', 'Soup', 'recipe_name: Soup', 'soup.yaml', 'h')")
            c.execSQL("INSERT INTO app_meta (`key`, `value`) VALUES ('legacy_import', 'done')")
            c.execSQL("PRAGMA user_version = 4")
        }.close()

        // Room checks every table against the entities as it opens: a google_event unlike 5.json's would throw here.
        val db = AppDatabase.openAt(file)
        try {
            assertEquals(listOf("Soup"), db.recipeDao().names())
            assertEquals("done", db.appMetaDao().get("legacy_import"))
            assertEquals(emptyList<GoogleEventEntity>(), db.googleEventDao().all())
            db.googleEventDao().record(GoogleEventEntity("family", "2026-10-05", "Dinner", "mpa", "h"))
            assertEquals(listOf("mpa"), db.googleEventDao().all().map { it.eventId })
        } finally {
            db.close()
        }
    }

    @Test
    fun aThrowInsideATransactionRollsItsWritesBack() = runBlocking {
        val db = AppDatabase.openAt(file)
        try {
            try {
                db.inTransaction {
                    db.pantryDao().insertOrIgnore(PantryItemEntity(name = "Rice"))
                    throw IllegalStateException("abort")
                }
                fail("the throw should have propagated")
            } catch (expected: IllegalStateException) {
                assertEquals("abort", expected.message)
            }
            assertEquals(emptyList<PantryItemEntity>(), db.pantryDao().observeAll().first())
        } finally {
            db.close()
        }
    }

    @Test
    fun aFlowReEmitsAfterACommittedTransaction() = runBlocking {
        val db = AppDatabase.openAt(file)
        try {
            val first = CompletableDeferred<List<PantryItemEntity>>()
            val collector = async {
                db.pantryDao().observeAll()
                    .onEach { if (!first.isCompleted) first.complete(it) }
                    .first { rows -> rows.any { it.name == "Rice" } }
            }
            // Write only once the collector has its first (empty) emission, so Rice can only arrive by invalidation.
            assertEquals(emptyList<PantryItemEntity>(), withTimeout(5_000) { first.await() })
            db.inTransaction { db.pantryDao().insertOrIgnore(PantryItemEntity(name = "Rice")) }

            assertEquals(listOf("Rice"), withTimeout(5_000) { collector.await() }.map { it.name })
        } finally {
            db.close()
        }
    }
}
