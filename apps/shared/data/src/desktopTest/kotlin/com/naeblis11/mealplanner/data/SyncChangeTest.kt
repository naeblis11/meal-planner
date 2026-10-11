package com.naeblis11.mealplanner.data

import androidx.room.useWriterConnection
import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.YamlMap
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** LAN sync stage 1: SyncSchema's triggers record each synced record's last change, whichever path wrote it. */
class SyncChangeTest {
    private val dir: File = Files.createTempDirectory("mp-sync").toFile()
    private val images = File(dir, "images").apply { mkdirs() }
    private var db = AppDatabase.openAt(File(dir, "test.db"))
    private val recipes get() = RecipeRepository(db, images)
    private val meals get() = MealPlanRepository(db)
    private val pantry get() = PantryRepository(db, today = { LocalDate.of(2026, 10, 10) })
    private val shopping get() = ShoppingRepository(db)

    @After
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    @Suppress("UNCHECKED_CAST")
    private fun doc(name: String, uuid: String, image: String? = null): YamlMap = RecipeYaml.load(
        "recipe_name: $name\nrecipe_uuid: $uuid\n" + (image?.let { "image: $it\n" } ?: "") +
            "ingredients:\n- Stock:\n    amounts:\n    - amount: 2\n      unit: cup\nsteps:\n- step: Simmer.\n",
    ) as YamlMap

    private suspend fun change(table: String, key: String) = db.syncDao().change(table, key)

    private suspend fun seq() = SyncSchema.changeSeq(db)

    @Test
    fun theDeviceIdAndCounterAreMadeOnceAndKept() = runBlocking {
        val id = SyncSchema.deviceId(db)
        assertTrue(id, Regex("[0-9a-f]{32}").matches(id))
        assertEquals(0L, seq())
        db.close()
        db = AppDatabase.openAt(File(dir, "test.db"))
        assertEquals(id, SyncSchema.deviceId(db))
    }

    @Test
    fun savingARecipeRecordsOneChangeByThisDevice() = runBlocking {
        val before = System.currentTimeMillis()
        recipes.save(doc("Soup", "u-1"))
        val after = System.currentTimeMillis()
        val c = change("recipe", "u-1")!!
        assertFalse(c.deleted)
        assertEquals(SyncSchema.deviceId(db), c.updatedBy)
        assertEquals(seq(), c.changeSeq)
        assertTrue("${c.updatedAt} not in $before..$after", c.updatedAt in (before - 5)..(after + 5))
    }

    @Test
    fun onlyARealEditCounts() = runBlocking {
        val id = recipes.save(doc("Soup", "u-1"))
        val first = seq()
        recipes.save(doc("Soup", "u-1"))
        assertEquals(first, seq())
        // The desktop's file bookkeeping is not an edit.
        val row = db.recipeDao().recipe(id)!!
        db.recipeDao().updateRecipe(row.copy(fileName = "soup.yaml", fileHash = "abc"))
        assertEquals(first, seq())
        recipes.setRating(id, 4)
        assertTrue(seq() > first)
        assertEquals(seq(), change("recipe", "u-1")!!.changeSeq)
    }

    @Test
    fun deletingARecipeLeavesTombstonesForItAndItsMeals() = runBlocking {
        val id = recipes.save(doc("Soup", "u-1"))
        meals.assign(LocalDate.of(2026, 10, 12), "Dinner", id, null)
        assertFalse(change("meal_plan", SyncSchema.mealKey("2026-10-12", "Dinner"))!!.deleted)
        recipes.delete(id)
        assertTrue(change("recipe", "u-1")!!.deleted)
        assertTrue(change("meal_plan", "2026-10-12|Dinner")!!.deleted)
    }

    @Test
    fun aMealReplacedInItsSlotStaysLiveAndClearingItLeavesATombstone() = runBlocking {
        val soup = recipes.save(doc("Soup", "u-1"))
        val stew = recipes.save(doc("Stew", "u-2"))
        val day = LocalDate.of(2026, 10, 12)
        meals.assign(day, "Dinner", soup, null)
        val first = change("meal_plan", "2026-10-12|Dinner")!!.changeSeq
        meals.assign(day, "Dinner", stew, "4")
        val replaced = change("meal_plan", "2026-10-12|Dinner")!!
        assertFalse(replaced.deleted)
        assertTrue(replaced.changeSeq > first)
        meals.unassign(day, "Dinner")
        assertTrue(change("meal_plan", "2026-10-12|Dinner")!!.deleted)
        meals.assign(day, "Dinner", soup, null)
        assertFalse(change("meal_plan", "2026-10-12|Dinner")!!.deleted)
    }

    @Test
    fun aPantryItemIsKeyedByItsNameInLowerCase() = runBlocking {
        pantry.add("Milk")
        assertEquals("milk", SyncSchema.nameKey("Milk"))
        val added = change("pantry_item", "milk")!!
        assertFalse(added.deleted)
        val id = db.pantryDao().byName("milk")!!.id
        pantry.setActive(id, false)
        assertTrue(change("pantry_item", "milk")!!.changeSeq > added.changeSeq)
        pantry.delete(id)
        assertTrue(change("pantry_item", "milk")!!.deleted)
    }

    @Test
    fun aRememberedAisleIsRecordedReplacedAndForgotten() = runBlocking {
        val dao = db.shoppingDao()
        dao.rememberAisle(IngredientAisleEntity(name = "Basil", aisle = "Produce"))
        val first = change("ingredient_aisle", "basil")!!
        dao.rememberAisle(IngredientAisleEntity(name = "basil", aisle = "Herbs"))
        val second = change("ingredient_aisle", "basil")!!
        assertFalse(second.deleted)
        assertTrue(second.changeSeq > first.changeSeq)
        dao.forgetAisle("Basil")
        assertTrue(change("ingredient_aisle", "basil")!!.deleted)
    }

    @Test
    fun aShoppingRowIsKeyedByItsSyncIdAndCheckOffIsASet() = runBlocking {
        val id = shopping.addItem("Eggs", "12").id
        val syncId = db.shoppingDao().item(id)!!.syncId
        val added = change("shopping_list_item", syncId)!!
        shopping.setChecked(id, true)
        val checked = change("shopping_list_item", syncId)!!.changeSeq
        assertTrue(checked > added.changeSeq)
        shopping.setChecked(id, true)
        assertEquals(checked, change("shopping_list_item", syncId)!!.changeSeq)
        shopping.clear()
        assertTrue(change("shopping_list_item", syncId)!!.deleted)
    }

    @Test
    fun changesAreListedInTheOrderTheyHappened() = runBlocking {
        recipes.save(doc("Soup", "u-1"))
        pantry.add("Milk")
        shopping.addItem("Eggs")
        val all = db.syncDao().changedSince(0)
        assertEquals(listOf("recipe", "pantry_item", "shopping_list_item"), all.map { it.tableName })
        assertEquals(all.map { it.changeSeq }.sorted().distinct(), all.map { it.changeSeq })
        assertEquals(emptyList<SyncChangeEntity>(), db.syncDao().changedSince(seq()))
    }

    @Test
    fun aChangeCanBeRedatedWithoutMovingItsOrder() = runBlocking {
        recipes.save(doc("Soup", "u-1"))
        val c = change("recipe", "u-1")!!
        db.syncDao().setUpdatedAt("recipe", "u-1", 1_000L)
        assertEquals(c.copy(updatedAt = 1_000L), change("recipe", "u-1"))
        assertNull(change("recipe", "u-2"))
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun addingTheWeekIntoARowKeepsItsSyncId() = runBlocking {
        val id = recipes.save(doc("Soup", "u-1"))
        val monday = LocalDate.of(2026, 10, 12)
        meals.assign(monday, "Dinner", id, null)
        val row = shopping.addItem("Stock", "1", "cup").id
        val syncId = db.shoppingDao().item(row)!!.syncId
        shopping.addWeek(monday)
        val merged = db.shoppingDao().item(row)!!
        assertEquals("3", merged.amount)
        assertEquals(syncId, merged.syncId)
        assertFalse(change("shopping_list_item", syncId)!!.deleted)
    }

    @Test
    fun aSavedRecipeKeepsItsPhotosHashAndANewPhotoUnderTheSameNameIsAChange() = runBlocking {
        val photo = File(images, "u-1.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val id = recipes.save(doc("Soup", "u-1", image = "u-1.jpg"))
        assertEquals(sha256(byteArrayOf(1, 2, 3)), db.recipeDao().recipe(id)!!.imageHash)
        val before = change("recipe", "u-1")!!.changeSeq
        photo.writeBytes(byteArrayOf(4, 5))
        recipes.setImage(id, "u-1.jpg")
        assertEquals(sha256(byteArrayOf(4, 5)), db.recipeDao().recipe(id)!!.imageHash)
        assertTrue(change("recipe", "u-1")!!.changeSeq > before)
        recipes.removeImage(id)
        assertNull(db.recipeDao().recipe(id)!!.imageHash)
    }

    @Test
    fun aRecipesNewUuidReRecordsItsMeals() = runBlocking {
        val id = recipes.save(doc("Soup", "u-1"))
        meals.assign(LocalDate.of(2026, 10, 12), "Dinner", id, null)
        meals.assign(LocalDate.of(2026, 10, 13), "Lunch", id, "2")
        val before = seq()
        // RecipeFolder keeps the row when the uuid is changed by hand in a file that is still there.
        val row = db.recipeDao().recipe(id)!!
        db.recipeDao().updateRecipe(row.copy(recipeUuid = "u-9", rawYaml = row.rawYaml.replace("u-1", "u-9")))
        assertTrue(change("recipe", "u-1")!!.deleted)
        assertFalse(change("recipe", "u-9")!!.deleted)
        for (key in listOf("2026-10-12|Dinner", "2026-10-13|Lunch")) {
            val meal = change("meal_plan", key)!!
            assertFalse(key, meal.deleted)
            assertTrue(key, meal.changeSeq > before)
        }
    }

    private suspend fun sql(statement: String) = db.useWriterConnection { connection ->
        connection.usePrepared(statement) { it.step() }
    }

    @Test
    fun aNameChangedOnlyInCaseIsAChange() = runBlocking {
        pantry.add("milk")
        db.shoppingDao().rememberAisle(IngredientAisleEntity(name = "basil", aisle = "Produce"))
        val pantryBefore = change("pantry_item", "milk")!!.changeSeq
        val aisleBefore = change("ingredient_aisle", "basil")!!.changeSeq
        sql("UPDATE pantry_item SET name = 'Milk' WHERE name = 'milk'")
        sql("UPDATE ingredient_aisle SET name = 'Basil' WHERE name = 'basil'")
        assertTrue(change("pantry_item", "milk")!!.changeSeq > pantryBefore)
        assertTrue(change("ingredient_aisle", "basil")!!.changeSeq > aisleBefore)
        assertFalse(change("ingredient_aisle", "basil")!!.deleted)
    }
}
