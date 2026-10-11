package com.naeblis11.mealplanner.sync

import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.IngredientAisleEntity
import com.naeblis11.mealplanner.data.PantryItemEntity
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.data.ShoppingItemEntity
import com.naeblis11.mealplanner.data.SyncPendingEntity
import com.naeblis11.mealplanner.data.SyncSchema
import com.naeblis11.mealplanner.data.openAt
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncApplierTest {
    private val dir: File = Files.createTempDirectory("mp-apply").toFile()
    private val db = AppDatabase.openAt(File(dir, "test.db"))
    private val recipes = RecipeRepository(db, File(dir, "images").apply { mkdirs() })
    private val client = SyncApplier(db, recipes, asHub = false)

    @After
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    private fun recipe(uuid: String, name: String, at: Long, by: String = "pc") = SyncRecord(
        SyncTables.RECIPE, uuid, at, by, false,
        buildJsonObject {
            put(SyncData.RAW_YAML, "recipe_name: $name\nrecipe_uuid: $uuid\ningredients:\n- Salt:\n    amounts:\n    - amount: 1\n      unit: tsp\nsteps:\n- step: Stir.\n")
            put(SyncData.IMAGE_HASH, null as String?)
        },
    )

    private fun meal(uuid: String, at: Long) = SyncRecord(
        SyncTables.MEAL, "2026-10-12|Dinner", at, "pc", false,
        buildJsonObject { put(SyncData.DATE, "2026-10-12"); put(SyncData.SLOT, "Dinner"); put(SyncData.RECIPE_UUID, uuid); put(SyncData.SERVINGS, null as String?) },
    )

    private fun mealOn(uuid: String, date: String, at: Long) = SyncRecord(
        SyncTables.MEAL, "$date|Dinner", at, "pc", false,
        buildJsonObject { put(SyncData.DATE, date); put(SyncData.SLOT, "Dinner"); put(SyncData.RECIPE_UUID, uuid); put(SyncData.SERVINGS, null as String?) },
    )

    private fun eggs(syncId: String, amount: String, at: Long, by: String = "pc", checked: Boolean = false) = SyncRecord(
        SyncTables.SHOPPING, syncId, at, by, false,
        buildJsonObject {
            put(SyncData.SYNC_ID, syncId); put(SyncData.NAME, "Eggs"); put(SyncData.AMOUNT, amount); put(SyncData.UNIT, null as String?)
            put(SyncData.AISLE, "Dairy"); put(SyncData.IN_PANTRY, false); put(SyncData.CHECKED, checked)
        },
    )

    @Test
    fun aNewRecipeIsSavedAndKeepsTheSendersStamp() = runBlocking {
        val result = client.apply(listOf(recipe("u-1", "Soup", 500L)))
        assertEquals(1, result.applied)
        assertEquals("Soup", db.recipeDao().recipe(db.recipeDao().idForUuid("u-1")!!)!!.name)
        val c = db.syncDao().change(SyncTables.RECIPE, "u-1")!!
        assertEquals(500L, c.updatedAt)
        assertEquals("pc", c.updatedBy)
    }

    @Test
    fun anOlderRecordLosesAndAClientTakesTheHubsVersionOnATie() = runBlocking {
        client.apply(listOf(recipe("u-1", "Soup", 500L)))
        client.apply(listOf(recipe("u-1", "Old soup", 400L)))
        assertEquals("Soup", db.recipeDao().allRecipes().single().name)
        client.apply(listOf(recipe("u-1", "Same-time soup", 500L)))
        assertEquals("Same-time soup", db.recipeDao().allRecipes().single().name)
    }

    @Test
    fun theHubKeepsItsOwnOnATie() = runBlocking {
        val hub = SyncApplier(db, recipes, asHub = true)
        hub.apply(listOf(recipe("u-1", "Soup", 500L)), requester = "phone")
        hub.apply(listOf(recipe("u-1", "Phone soup", 500L, by = "phone")), requester = "phone")
        assertEquals("Soup", db.recipeDao().allRecipes().single().name)
    }

    @Test
    fun aMealWaitsForItsRecipeAndIsAppliedWhenItArrives() = runBlocking {
        client.apply(listOf(meal("u-1", 600L)))
        assertNull(db.mealPlanDao().assignment("2026-10-12", "Dinner"))
        assertEquals(listOf(SyncApplier.MISSING_RECIPE), db.syncDao().pending().map { it.reason })
        client.apply(listOf(recipe("u-1", "Soup", 500L)))
        assertEquals("Soup", db.mealPlanDao().assignment("2026-10-12", "Dinner")!!.recipeName)
        assertEquals(emptyList<Any>(), db.syncDao().pending())
    }

    @Test
    fun anUnreadableRecipeIsSkippedByName() = runBlocking {
        val broken = recipe("u-2", "Stew", 500L).copy(data = buildJsonObject { put(SyncData.RAW_YAML, "recipe_name: Stew\nrecipe_uuid: u-9\n"); put(SyncData.IMAGE_HASH, null as String?) })
        val result = client.apply(listOf(broken))
        assertEquals(listOf("Stew"), result.skipped)
        assertEquals(0, db.recipeDao().count())
    }

    @Test
    fun aTombstoneDeletesAndIsKept() = runBlocking {
        client.apply(listOf(eggs("s-1", "6", 500L)))
        client.apply(listOf(SyncRecord(SyncTables.SHOPPING, "s-1", 600L, "pc", true, null)))
        assertNull(db.syncDao().shopping("s-1"))
        assertTrue(db.syncDao().change(SyncTables.SHOPPING, "s-1")!!.deleted)
    }

    @Test
    fun theHubAddsTheSameItemAddedElsewhereIntoOneRow() = runBlocking {
        val hub = SyncApplier(db, recipes, asHub = true)
        db.shoppingDao().insert(ShoppingItemEntity(name = "Eggs", amount = "6", syncId = "pc-row"))
        hub.apply(listOf(eggs("phone-row", "6", 700L, by = "phone")), requester = "phone")
        val rows = db.shoppingDao().allInIdOrder()
        assertEquals(listOf("pc-row" to "12"), rows.map { it.syncId to it.amount })
        val gone = db.syncDao().change(SyncTables.SHOPPING, "phone-row")!!
        assertTrue(gone.deleted)
        assertEquals(SyncSchema.deviceId(db), gone.updatedBy)
    }

    @Test
    fun onAFirstSyncTheHubMatchesTheSameItemWithoutAddingIt() = runBlocking {
        val hub = SyncApplier(db, recipes, asHub = true)
        db.shoppingDao().insert(ShoppingItemEntity(name = "Milk", amount = "1", unit = "gal", syncId = "pc-row"))
        val milk = eggs("phone-row", "1", 700L, by = "phone").let {
            it.copy(data = buildJsonObject {
                put(SyncData.SYNC_ID, "phone-row"); put(SyncData.NAME, "Milk"); put(SyncData.AMOUNT, "1"); put(SyncData.UNIT, "gal")
                put(SyncData.AISLE, "Dairy"); put(SyncData.IN_PANTRY, false); put(SyncData.CHECKED, false)
            })
        }
        hub.apply(listOf(milk), requester = "phone", firstSync = true)
        assertEquals(listOf("pc-row" to "1"), db.shoppingDao().allInIdOrder().map { it.syncId to it.amount })
    }

    @Test
    fun theHubHoldsAMassRemovalUntilItIsConfirmed() = runBlocking {
        val hub = SyncApplier(db, recipes, asHub = true)
        hub.apply((1..5).map { recipe("u-$it", "R$it", 100L) }, requester = "pc-self")
        val removals = (1..4).map { SyncRecord(SyncTables.RECIPE, "u-$it", 900L, "phone", true, null) }
        val result = hub.apply(removals, requester = "phone")
        assertEquals(4, result.held)
        assertEquals(5, db.recipeDao().count())
        assertEquals(4, hub.applyHeldRemovals(setOf("u-1", "u-2", "u-3", "u-4")))
        assertEquals(1, db.recipeDao().count())
        assertEquals(emptyList<Any>(), db.syncDao().pending())
    }

    @Test
    fun onlyTheConfirmedRecipesAndTheirMealsAreRemovedTheRestStayHeld() = runBlocking {
        val hub = SyncApplier(db, recipes, asHub = true)
        hub.apply((1..5).map { recipe("u-$it", "R$it", 100L) } + mealOn("u-1", "2026-10-12", 200L) + mealOn("u-3", "2026-10-13", 200L), requester = "pc-self")
        val removals = (1..4).map { SyncRecord(SyncTables.RECIPE, "u-$it", 900L, "phone", true, null) } +
            listOf("2026-10-12", "2026-10-13").map { SyncRecord(SyncTables.MEAL, "$it|Dinner", 900L, "phone", true, null) }
        assertEquals(6, hub.apply(removals, requester = "phone").held)
        // The user saw (and confirmed) two of them.
        assertEquals(2, hub.applyHeldRemovals(setOf("u-1", "u-2")))
        assertEquals(listOf("R3", "R4", "R5"), recipes.allRecipes().map { it.name }.sorted())
        assertNull(db.mealPlanDao().assignment("2026-10-12", "Dinner"))
        assertEquals("R3", db.mealPlanDao().assignment("2026-10-13", "Dinner")!!.recipeName)
        // u-3, u-4 and u-3's meal tombstone are still held.
        assertEquals(
            setOf(SyncTables.RECIPE to "u-3", SyncTables.RECIPE to "u-4", SyncTables.MEAL to "2026-10-13|Dinner"),
            db.syncDao().pending().filter { it.reason == SyncApplier.MASS_REMOVAL }.map { it.tableName to it.syncKey }.toSet(),
        )
    }

    @Test
    fun aRecipeWhosePhotoDiffersIsListedForItsPhoto() = runBlocking {
        val withPhoto = recipe("u-1", "Soup", 500L).let { r ->
            r.copy(data = buildJsonObject { put(SyncData.RAW_YAML, SyncWire.text(r.data!!, SyncData.RAW_YAML) + "image: u-1.jpg\n"); put(SyncData.IMAGE_HASH, "abc") })
        }
        assertEquals(listOf("u-1"), client.apply(listOf(withPhoto)).photosNeeded)
    }

    // A recipe's removal cascades to its meals; the cascade's tombstones take the removal's stamp, not this device's now,
    // so a meal the sender planned in that slot afterwards still wins.
    private fun replanAfterRemoval(applier: SyncApplier, requester: String?) = runBlocking {
        applier.apply(listOf(recipe("u-1", "Soup", 100L), recipe("u-2", "Stew", 100L), meal("u-1", 200L)), requester = "pc-self")
        val tombstone = SyncRecord(SyncTables.RECIPE, "u-1", 500L, "phone", true, null)
        val replanned = meal("u-2", 600L).copy(updatedBy = "phone")
        applier.apply(listOf(tombstone, replanned), requester = requester)
        assertEquals("Stew", db.mealPlanDao().assignment("2026-10-12", "Dinner")!!.recipeName)
        val c = db.syncDao().change(SyncTables.MEAL, "2026-10-12|Dinner")!!
        assertEquals(false, c.deleted)
        assertEquals(600L, c.updatedAt)
        assertEquals("phone", c.updatedBy)
    }

    @Test
    fun aClientKeepsAMealPlannedAfterItsRecipeWasRemoved() = replanAfterRemoval(client, null)

    @Test
    fun theHubKeepsAMealPlannedAfterItsRecipeWasRemoved() = replanAfterRemoval(SyncApplier(db, recipes, asHub = true), "phone")

    @Test
    fun aRemovedRecipesMealsTakeTheRemovalsStamp() = runBlocking {
        client.apply(listOf(recipe("u-1", "Soup", 100L), meal("u-1", 200L)))
        client.apply(listOf(SyncRecord(SyncTables.RECIPE, "u-1", 500L, "phone", true, null)))
        assertNull(db.mealPlanDao().assignment("2026-10-12", "Dinner"))
        val c = db.syncDao().change(SyncTables.MEAL, "2026-10-12|Dinner")!!
        assertTrue(c.deleted)
        assertEquals(500L, c.updatedAt)
        assertEquals("phone", c.updatedBy)
    }

    @Test
    fun aConfirmedHeldRemovalsMealsTakeTheRemovalsStamp() = runBlocking {
        val hub = SyncApplier(db, recipes, asHub = true)
        hub.apply((1..5).map { recipe("u-$it", "R$it", 100L) } + meal("u-1", 200L), requester = "pc-self")
        hub.apply((1..4).map { SyncRecord(SyncTables.RECIPE, "u-$it", 900L, "phone", true, null) }, requester = "phone")
        assertEquals(4, hub.applyHeldRemovals(setOf("u-1", "u-2", "u-3", "u-4")))
        val c = db.syncDao().change(SyncTables.MEAL, "2026-10-12|Dinner")!!
        assertTrue(c.deleted)
        assertEquals(900L, c.updatedAt)
        assertEquals("phone", c.updatedBy)
    }

    @Test
    fun anUnreadablePendingRowIsDroppedAndTheApplyCarriesOn() = runBlocking {
        db.syncDao().putPending(SyncPendingEntity(SyncTables.MEAL, "2026-10-12|Dinner", SyncApplier.MISSING_RECIPE, "not a record"))
        val result = client.apply(listOf(recipe("u-1", "Soup", 500L)))
        assertEquals(1, result.applied)
        assertEquals(emptyList<Any>(), db.syncDao().pending())
    }

    @Test
    fun anUnreadableHeldRemovalIsDroppedWhenConfirmed() = runBlocking {
        val hub = SyncApplier(db, recipes, asHub = true)
        db.syncDao().putPending(SyncPendingEntity(SyncTables.RECIPE, "u-9", SyncApplier.MASS_REMOVAL, "{\"table\":1}"))
        assertEquals(0, hub.applyHeldRemovals(setOf("u-9")))
        assertEquals(emptyList<Any>(), db.syncDao().pending())
    }

    private fun pantry(key: String, name: String, at: Long, aisle: String? = null) = SyncRecord(
        SyncTables.PANTRY, key, at, "pc", false,
        buildJsonObject { put(SyncData.NAME, name); put(SyncData.EXACT_MATCH, false); put(SyncData.AISLE, aisle); put(SyncData.ACTIVE, true); put(SyncData.ADDED_ON, null as String?) },
    )

    @Test
    fun aRecordWhoseKeyDoesntMatchItsDataIsSkippedAndTheRestOfTheBatchApplies() = runBlocking {
        db.pantryDao().insertOrIgnore(PantryItemEntity(name = "Pepper"))
        db.shoppingDao().rememberAisle(IngredientAisleEntity(name = "Flour", aisle = "Baking"))
        val pepper = db.pantryDao().byName("Pepper")
        val flour = db.syncDao().aisle("Flour")
        val result = client.apply(
            listOf(
                pantry("salt", "Pepper", 500L, aisle = "Spices"),
                SyncRecord(SyncTables.AISLE, "sugar", 500L, "pc", false, buildJsonObject { put(SyncData.NAME, "Flour"); put(SyncData.AISLE, "Produce") }),
                meal("u-1", 500L).copy(key = "2026-10-13|Lunch"),
                eggs("s-1", "6", 500L).copy(key = "s-2"),
                pantry("milk", "Milk", 500L),
                recipe("u-1", "Soup", 500L),
            ),
        )
        assertEquals(setOf("Pepper", "Flour", "2026-10-13|Lunch", "Eggs"), result.skipped.toSet())
        assertEquals(pepper, db.pantryDao().byName("Pepper"))
        assertEquals(flour, db.syncDao().aisle("Flour"))
        assertNull(db.mealPlanDao().assignment("2026-10-12", "Dinner"))
        assertNull(db.mealPlanDao().assignment("2026-10-13", "Lunch"))
        assertEquals(emptyList<Any>(), db.shoppingDao().allInIdOrder())
        listOf(SyncTables.PANTRY to "salt", SyncTables.AISLE to "sugar", SyncTables.MEAL to "2026-10-13|Lunch", SyncTables.SHOPPING to "s-2")
            .forEach { (t, k) -> assertNull("$t $k", db.syncDao().change(t, k)) }
        // The valid records in the same batch still applied.
        assertEquals("Milk", db.pantryDao().byName("milk")!!.name)
        assertEquals(1, db.recipeDao().count())
    }

    @Test
    fun aRecordWithAFieldThatCantBeReadIsSkippedWithoutUndoingTheOthers() = runBlocking {
        val broken = pantry("salt", "Salt", 500L).let { it.copy(data = buildJsonObject { put(SyncData.NAME, "Salt"); put(SyncData.EXACT_MATCH, "no"); put(SyncData.ACTIVE, true) }) }
        val noYaml = recipe("u-2", "Stew", 500L).copy(data = buildJsonObject { put(SyncData.IMAGE_HASH, null as String?) })
        val result = client.apply(listOf(pantry("milk", "Milk", 500L), broken, noYaml, recipe("u-1", "Soup", 500L)))
        assertEquals(setOf("Salt", "u-2"), result.skipped.toSet())
        assertEquals("Milk", db.pantryDao().byName("milk")!!.name)
        assertNull(db.pantryDao().byName("salt"))
        assertEquals(listOf("Soup"), db.recipeDao().allRecipes().map { it.name })
    }

    // The hub passes a record on by its counter step: a newer stamp on data that is already here still takes a new step
    // (the write changed nothing, so no trigger took one), or devices that synced since never hear of it.
    @Test
    fun aNewerStampOnDataAlreadyHereTakesANewCounterStep() = runBlocking {
        client.apply(listOf(recipe("u-1", "Soup", 500L), pantry("milk", "Milk", 500L)))
        val recipeBefore = db.syncDao().change(SyncTables.RECIPE, "u-1")!!.changeSeq
        val milkBefore = db.syncDao().change(SyncTables.PANTRY, "milk")!!.changeSeq
        client.apply(listOf(recipe("u-1", "Soup", 600L, by = "tablet"), pantry("milk", "Milk", 600L).copy(updatedBy = "tablet")))
        val recipe = db.syncDao().change(SyncTables.RECIPE, "u-1")!!
        val milk = db.syncDao().change(SyncTables.PANTRY, "milk")!!
        assertEquals(600L to "tablet", recipe.updatedAt to recipe.updatedBy)
        assertEquals(600L to "tablet", milk.updatedAt to milk.updatedBy)
        assertTrue(recipe.changeSeq > recipeBefore)
        assertTrue(milk.changeSeq > milkBefore)
    }
}
