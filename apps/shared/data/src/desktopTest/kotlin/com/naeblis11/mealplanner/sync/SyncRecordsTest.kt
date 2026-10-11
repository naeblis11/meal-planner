package com.naeblis11.mealplanner.sync

import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.MealPlanRepository
import com.naeblis11.mealplanner.data.PantryRepository
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.data.ShoppingRepository
import com.naeblis11.mealplanner.data.SyncSchema
import com.naeblis11.mealplanner.data.openAt
import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.YamlMap
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncRecordsTest {
    private val dir: File = Files.createTempDirectory("mp-records").toFile()
    private val db = AppDatabase.openAt(File(dir, "test.db"))
    private val recipes = RecipeRepository(db, File(dir, "images").apply { mkdirs() })

    @After
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    @Suppress("UNCHECKED_CAST")
    private fun doc(name: String, uuid: String): YamlMap = RecipeYaml.load(
        "recipe_name: $name\nrecipe_uuid: $uuid\ningredients:\n- Stock:\n    amounts:\n    - amount: 2\n      unit: cup\nsteps:\n- step: Simmer.\n",
    ) as YamlMap

    @Test
    fun changesCarryTheirFieldsAndAMealCarriesItsRecipesUuid() = runBlocking {
        val id = recipes.save(doc("Soup", "u-1"))
        MealPlanRepository(db).assign(LocalDate.of(2026, 10, 12), "Dinner", id, "4")
        ShoppingRepository(db).addItem("Eggs", "12")
        val me = SyncSchema.deviceId(db)
        val out = SyncRecords(db).changedSince(0, by = me)
        assertEquals(listOf(SyncTables.RECIPE, SyncTables.MEAL, SyncTables.SHOPPING), out.map { it.table })
        val meal = out[1].data!!
        assertEquals("u-1", SyncWire.text(meal, SyncData.RECIPE_UUID))
        assertEquals("4", SyncWire.textOrNull(meal, SyncData.SERVINGS))
        assertTrue(SyncWire.text(out[0].data!!, SyncData.RAW_YAML).contains("Soup"))
        assertEquals(emptyList<SyncRecord>(), SyncRecords(db).changedSince(0, by = "someone-else"))
    }

    @Test
    fun aDeleteIsATombstoneAndEverythingIncludesUnchangedRowsAtZero() = runBlocking {
        val pantry = PantryRepository(db)
        pantry.add("Milk")
        pantry.delete(db.pantryDao().byName("milk")!!.id)
        recipes.save(doc("Soup", "u-1"))
        db.syncDao().deleteChange(SyncTables.RECIPE, "u-1") // as if it predated sync
        val all = SyncRecords(db).everything("me")
        val soup = all.single { it.table == SyncTables.RECIPE }
        assertEquals(0L, soup.updatedAt)
        assertEquals("me", soup.updatedBy)
        val milk = all.single { it.table == SyncTables.PANTRY }
        assertTrue(milk.deleted)
        assertEquals(null, milk.data)
    }

    @Test
    fun aHeldRecipeIsNotSent() = runBlocking {
        recipes.save(doc("Soup", "u-1"))
        val records = SyncRecords(db, isHeld = { true })
        assertEquals(emptyList<SyncRecord>(), records.changedSince(0))
        assertEquals(emptyList<SyncRecord>(), records.everything("me").filter { it.table == SyncTables.RECIPE })
    }
}
