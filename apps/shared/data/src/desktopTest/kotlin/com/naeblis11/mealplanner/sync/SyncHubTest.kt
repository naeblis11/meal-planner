package com.naeblis11.mealplanner.sync

import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.data.SyncSchema
import com.naeblis11.mealplanner.data.openAt
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncHubTest {
    private val dir: File = Files.createTempDirectory("mp-hub").toFile()
    private val images = File(dir, "images").apply { mkdirs() }
    private val db = AppDatabase.openAt(File(dir, "test.db"))
    private val recipes = RecipeRepository(db, images)
    private var now = 10_000_000L
    private val hub = SyncHub(db, recipes, images, clock = { now })

    @After
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    private fun pantry(name: String, at: Long, by: String = "phone") = SyncRecord(
        SyncTables.PANTRY, name.lowercase(), at, by, false,
        buildJsonObject { put(SyncData.NAME, name); put(SyncData.EXACT_MATCH, false); put(SyncData.AISLE, null as String?); put(SyncData.ACTIVE, true); put(SyncData.ADDED_ON, null as String?) },
    )

    @Test
    fun aClientsClockIsCorrectedToTheHubs() = runBlocking {
        // The phone's clock is an hour behind: its change made a second ago is stamped an hour and a second ago.
        hub.exchange("phone", ExchangeRequest(now - 3_600_000L, 0L, false, listOf(pantry("Milk", now - 3_601_000L))))
        assertEquals(now - 1_000L, db.syncDao().change(SyncTables.PANTRY, "milk")!!.updatedAt)
    }

    @Test
    fun theReplyLeavesOutTheRequestersOwnChangesAndCarriesTheCursor() = runBlocking {
        db.pantryDao().insertOrIgnore(com.naeblis11.mealplanner.data.PantryItemEntity(name = "Flour"))
        val reply = hub.exchange("phone", ExchangeRequest(now, 0L, false, listOf(pantry("Milk", now))))
        assertEquals(listOf("flour"), reply.records.map { it.key })
        assertFalse(reply.full)
        assertEquals(SyncSchema.changeSeq(db), reply.cursor)
        val again = hub.exchange("phone", ExchangeRequest(now, reply.cursor, false, emptyList(), hubId = reply.hubId))
        assertEquals(emptyList<SyncRecord>(), again.records)
    }

    @Test
    fun aFullReplyCarriesTheRequestersOwnRecordsToo() = runBlocking {
        hub.exchange("phone", ExchangeRequest(now, 0L, false, listOf(pantry("Milk", now))))
        val full = hub.exchange("phone", ExchangeRequest(now, 0L, true, emptyList()))
        assertEquals(listOf("milk" to "phone"), full.records.map { it.key to it.updatedBy })
    }

    @Test
    fun aCursorTheHubNeverGaveMeansEverything() = runBlocking {
        db.pantryDao().insertOrIgnore(com.naeblis11.mealplanner.data.PantryItemEntity(name = "Flour"))
        val reply = hub.exchange("phone", ExchangeRequest(now, 999_999L, false, emptyList()))
        assertTrue(reply.full)
        assertEquals(listOf("flour"), reply.records.map { it.key })
    }

    @Test
    fun aCursorFromAnotherHubMeansEverything() = runBlocking {
        db.pantryDao().insertOrIgnore(com.naeblis11.mealplanner.data.PantryItemEntity(name = "Flour"))
        val first = hub.exchange("phone", ExchangeRequest(now, 0L, true, emptyList(), hubId = null))
        val id = first.hubId
        assertEquals(id, db.appMetaDao().get(SyncHub.HUB_ID_KEY))
        assertTrue(id != SyncSchema.deviceId(db))
        repeat(3) { db.syncDao().bumpChangeSeq() }
        // The same hub and a cursor it gave: only changes.
        assertFalse(hub.exchange("phone", ExchangeRequest(now, first.cursor, false, emptyList(), hubId = id)).full)
        // Another hub's cursor, or a cursor with no hub named, is everything, though the number is one this hub has.
        val other = hub.exchange("phone", ExchangeRequest(now, first.cursor, false, emptyList(), hubId = "another-hub"))
        assertTrue(other.full)
        assertEquals(listOf("flour"), other.records.map { it.key })
        assertTrue(hub.exchange("phone", ExchangeRequest(now, first.cursor, false, emptyList(), hubId = null)).full)
        assertEquals(id, hub.exchange("phone", ExchangeRequest(now, 0L, false, emptyList())).hubId)
    }

    @Test
    fun aHeldRemovalIsListedAndKeptOrConfirmed() = runBlocking {
        val recipe = { uuid: String -> SyncRecord(SyncTables.RECIPE, uuid, 100L, "phone", false, buildJsonObject {
            put(SyncData.RAW_YAML, "recipe_name: R $uuid\nrecipe_uuid: $uuid\ningredients:\n- Salt:\n    amounts:\n    - amount: 1\n      unit: tsp\nsteps:\n- step: Stir.\n")
            put(SyncData.IMAGE_HASH, null as String?)
        }) }
        hub.exchange("phone", ExchangeRequest(now, 0L, false, (1..5).map { recipe("u-$it") }))
        hub.exchange("phone", ExchangeRequest(now, 0L, false, (1..4).map { SyncRecord(SyncTables.RECIPE, "u-$it", now, "phone", true, null) }))
        assertEquals((1..4).map { HeldRemoval("u-$it", "R u-$it") }, hub.heldRemovals().sortedBy { it.uuid })
        now += 1_000L
        hub.keepHeldRemovals(setOf("u-1", "u-2", "u-3", "u-4"))
        assertEquals(emptyList<HeldRemoval>(), hub.heldRemovals())
        assertEquals(5, db.recipeDao().count())
        // Kept: the four come back to the phone as the hub's newest changes (u-5 is still the phone's own, not echoed).
        val reply = hub.exchange("phone", ExchangeRequest(now, 0L, false, emptyList()))
        assertEquals(setOf("u-1", "u-2", "u-3", "u-4"), reply.records.filter { it.table == SyncTables.RECIPE && !it.deleted }.map { it.key }.toSet())
    }

    @Test
    fun aJoinedShoppingRowsTombstoneAndTheMergedRowComeBackInTheSameReply() = runBlocking {
        db.shoppingDao().insert(com.naeblis11.mealplanner.data.ShoppingItemEntity(name = "Milk", amount = "1", unit = "cup", syncId = "hub-milk"))
        val phoneRow = SyncRecord(SyncTables.SHOPPING, "phone-milk", now, "phone", false, buildJsonObject {
            put(SyncData.SYNC_ID, "phone-milk"); put(SyncData.NAME, "Milk"); put(SyncData.AMOUNT, "1"); put(SyncData.UNIT, "cup")
            put(SyncData.AISLE, null as String?); put(SyncData.IN_PANTRY, false); put(SyncData.CHECKED, false)
        })
        val reply = hub.exchange("phone", ExchangeRequest(now, 0L, false, listOf(phoneRow)))
        val tombstone = reply.records.single { it.table == SyncTables.SHOPPING && it.key == "phone-milk" }
        assertTrue(tombstone.deleted)
        assertEquals(SyncSchema.deviceId(db), tombstone.updatedBy)
        val merged = reply.records.single { it.table == SyncTables.SHOPPING && it.key == "hub-milk" }
        assertFalse(merged.deleted)
        assertEquals("2", merged.data!!["amount"]!!.toString().trim('"'))
    }

    @Test
    fun aStampInTheFutureIsStoredAsNowAndALaterHubEditBeatsIt() = runBlocking {
        hub.exchange("phone", ExchangeRequest(now, 0L, false, listOf(pantry("Milk", now + 86_400_000L))))
        assertEquals(now, db.syncDao().change(SyncTables.PANTRY, "milk")!!.updatedAt)
        now += 1_000L
        db.syncDao().setUpdatedAt(SyncTables.PANTRY, "milk", now)
        val r = hub.exchange("phone", ExchangeRequest(now, 0L, false, listOf(pantry("Milk", now - 500L))))
        assertEquals(now, db.syncDao().change(SyncTables.PANTRY, "milk")!!.updatedAt)
    }

    @Test
    fun aShiftBelowOneIsStoredAsOne() = runBlocking {
        hub.exchange("phone", ExchangeRequest(now + 5_000_000L, 0L, false, listOf(pantry("Milk", 100L))))
        assertEquals(1L, db.syncDao().change(SyncTables.PANTRY, "milk")!!.updatedAt)
    }

    @Test
    fun aNormalRequestCarriesOnlyTheRequestersOwnChangesButAFullOneMayRelayOthers() = runBlocking {
        val reply = hub.exchange("phone", ExchangeRequest(now, 0L, false, listOf(pantry("Milk", now), pantry("Salt", now, by = "tablet"))))
        assertEquals(listOf("Salt"), reply.skipped)
        assertEquals(null, db.pantryDao().byName("salt"))
        assertEquals(null, db.syncDao().change(SyncTables.PANTRY, "salt"))
        assertEquals("phone", db.syncDao().change(SyncTables.PANTRY, "milk")!!.updatedBy)
        hub.exchange("phone", ExchangeRequest(now, 0L, true, listOf(pantry("Salt", now, by = "tablet"))))
        assertEquals("tablet", db.syncDao().change(SyncTables.PANTRY, "salt")!!.updatedBy)
    }

    @Test
    fun theHubTakesOnlyAPhotoItAskedFor() = runBlocking {
        recipes.save(
            com.naeblis11.mealplanner.domain.RecipeYaml.load(
                "recipe_name: Soup\nrecipe_uuid: u-1\nimage: u-1.jpg\ningredients:\n- Salt:\n    amounts:\n    - amount: 1\n      unit: tsp\nsteps:\n- step: Stir.\n",
            ) as com.naeblis11.mealplanner.domain.YamlMap,
        )
        val bytes = java.io.ByteArrayOutputStream().also {
            javax.imageio.ImageIO.write(java.awt.image.BufferedImage(40, 30, java.awt.image.BufferedImage.TYPE_INT_RGB), "jpg", it)
        }.toByteArray()
        assertFalse(hub.receivePhoto("u-1", bytes))
        assertFalse(File(images, "u-1.jpg").exists())
    }

    @Test
    fun confirmingHeldRemovalsRemovesThem() = runBlocking {
        val yaml = { uuid: String -> "recipe_name: R $uuid\nrecipe_uuid: $uuid\ningredients:\n- Salt:\n    amounts:\n    - amount: 1\n      unit: tsp\nsteps:\n- step: Stir.\n" }
        val recipe = { uuid: String -> SyncRecord(SyncTables.RECIPE, uuid, 100L, "phone", false, buildJsonObject {
            put(SyncData.RAW_YAML, yaml(uuid))
            put(SyncData.IMAGE_HASH, null as String?)
        }) }
        hub.exchange("phone", ExchangeRequest(now, 0L, false, (1..5).map { recipe("u-$it") }))
        hub.exchange("phone", ExchangeRequest(now, 0L, false, (1..4).map { SyncRecord(SyncTables.RECIPE, "u-$it", now, "phone", true, null) }))
        assertEquals(4, hub.confirmHeldRemovals(setOf("u-1", "u-2", "u-3", "u-4")))
        assertEquals(1, db.recipeDao().count())
        assertEquals(emptyList<HeldRemoval>(), hub.heldRemovals())
    }
}
