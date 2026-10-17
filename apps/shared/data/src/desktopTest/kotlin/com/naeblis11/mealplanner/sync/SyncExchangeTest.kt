package com.naeblis11.mealplanner.sync

import com.naeblis11.mealplanner.data.MealPlanRepository
import com.naeblis11.mealplanner.data.PantryRepository
import com.naeblis11.mealplanner.data.ShoppingRepository
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.LocalDate
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The spec's phase 2 testing list, phone and master PC in one process (LAN sync spec, "Testing"). */
class SyncExchangeTest {
    private val h = SyncHarness()

    @After
    fun tearDown() = h.close()

    private suspend fun names(d: SyncHarness.Device) = d.recipes.allRecipes().map { it.name }.sorted()

    private fun jpeg(): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray()

    @Test
    fun theFirstSyncNeedsAChoiceAndMergeCopiesBothWays() = runBlocking {
        h.pc.recipes.save(h.doc("Soup", "u-pc"))
        h.phone.recipes.save(h.doc("Stew", "u-phone"))
        PantryRepository(h.phone.db).add("Milk")
        assertThrows(IllegalArgumentException::class.java) { runBlocking { h.client.sync() } }
        h.client.sync(FirstSync.MERGE)
        assertEquals(listOf("Soup", "Stew"), names(h.pc))
        assertEquals(listOf("Soup", "Stew"), names(h.phone))
        assertEquals("Milk", h.pc.db.pantryDao().byName("milk")!!.name)
        assertTrue(h.client.hasSynced())
    }

    @Test
    fun editsTravelBothWaysAndASecondSyncWithNothingNewMovesNothing() = runBlocking {
        val pcSoup = h.pc.recipes.save(h.doc("Soup", "u-1"))
        h.client.sync(FirstSync.MERGE)
        val phoneSoup = h.phone.recipes.idForUuid("u-1")!!
        h.phone.recipes.setRating(phoneSoup, 4)
        h.client.sync()
        assertEquals(4, h.pc.db.recipeDao().recipe(pcSoup)!!.rating)
        h.pc.recipes.setRating(pcSoup, 2)
        h.client.sync()
        assertEquals(2, h.phone.db.recipeDao().recipe(phoneSoup)!!.rating)
        val quiet = h.client.sync()
        assertEquals(0 to 0, quiet.sent to quiet.received)
    }

    @Test
    fun theNewerOfTwoEditsWinsAndAnOlderDeleteLosesToANewerEdit() = runBlocking {
        val pcSoup = h.pc.recipes.save(h.doc("Soup", "u-1"))
        h.client.sync(FirstSync.MERGE)
        val phoneSoup = h.phone.recipes.idForUuid("u-1")!!
        h.pc.recipes.setRating(pcSoup, 1)
        h.phone.recipes.setRating(phoneSoup, 5)
        h.pc.db.syncDao().setUpdatedAt(SyncTables.RECIPE, "u-1", 1_000L)
        h.phone.db.syncDao().setUpdatedAt(SyncTables.RECIPE, "u-1", 2_000L)
        h.client.sync()
        assertEquals(5, h.pc.db.recipeDao().recipe(pcSoup)!!.rating)

        h.pc.recipes.delete(pcSoup)
        h.pc.db.syncDao().setUpdatedAt(SyncTables.RECIPE, "u-1", 3_000L)
        h.phone.recipes.setRating(phoneSoup, 3)
        h.phone.db.syncDao().setUpdatedAt(SyncTables.RECIPE, "u-1", 4_000L)
        h.client.sync()
        assertEquals(listOf("Soup"), names(h.pc))
        assertEquals(3, h.pc.recipes.allRecipes().single().rating)
    }

    @Test
    fun theSameItemAddedOnBothSidesEndsAsOneRowEverywhere() = runBlocking {
        h.client.sync(FirstSync.MERGE)
        ShoppingRepository(h.pc.db).addItem("Eggs", "6")
        ShoppingRepository(h.phone.db).addItem("Eggs", "6")
        h.client.sync()
        assertEquals(listOf("Eggs" to "12"), h.pc.db.shoppingDao().allInIdOrder().map { it.name to it.amount })
        assertEquals(listOf("Eggs" to "12"), h.phone.db.shoppingDao().allInIdOrder().map { it.name to it.amount })
    }

    @Test
    fun theFirstMergeMatchesTheSameItemWithoutDoublingIt() = runBlocking {
        ShoppingRepository(h.pc.db).addItem("Milk", "1", "gal")
        ShoppingRepository(h.phone.db).addItem("Milk", "1", "gal")
        h.client.sync(FirstSync.MERGE)
        assertEquals(listOf("1"), h.pc.db.shoppingDao().allInIdOrder().map { it.amount })
        assertEquals(listOf("1"), h.phone.db.shoppingDao().allInIdOrder().map { it.amount })
    }

    @Test
    fun aCheckOffReachesThePcAsASet() = runBlocking {
        ShoppingRepository(h.pc.db).addItem("Eggs", "6")
        h.client.sync(FirstSync.MERGE)
        val row = h.phone.db.shoppingDao().allInIdOrder().single()
        ShoppingRepository(h.phone.db).setChecked(row.id, true)
        h.client.sync()
        assertTrue(h.pc.db.shoppingDao().allInIdOrder().single().checked)
    }

    @Test
    fun aMealAndItsRecipeArriveTogether() = runBlocking {
        val id = h.phone.recipes.save(h.doc("Soup", "u-1"))
        MealPlanRepository(h.phone.db).assign(LocalDate.of(2026, 10, 12), "Dinner", id, null)
        h.client.sync(FirstSync.MERGE)
        assertEquals("Soup", h.pc.db.mealPlanDao().assignment("2026-10-12", "Dinner")!!.recipeName)
    }

    @Test
    fun aDroppedSyncIsRetriedWithoutDuplicates() = runBlocking {
        h.client.sync(FirstSync.MERGE)
        ShoppingRepository(h.phone.db).addItem("Eggs", "6")
        h.dropNextReply = true
        assertThrows(IOException::class.java) { runBlocking { h.client.sync() } }
        h.client.sync()
        assertEquals(listOf("Eggs" to "6"), h.pc.db.shoppingDao().allInIdOrder().map { it.name to it.amount })
        assertEquals(listOf("Eggs" to "6"), h.phone.db.shoppingDao().allInIdOrder().map { it.name to it.amount })
    }

    @Test
    fun aCursorThePcDoesntKnowLeadsToEverything() = runBlocking {
        h.pc.recipes.save(h.doc("Soup", "u-1"))
        h.client.sync(FirstSync.MERGE)
        h.phone.db.appMetaDao().put(com.naeblis11.mealplanner.data.AppMetaEntity(SyncClient.CURSOR_KEY, "999999"))
        h.phone.recipes.allRecipes().forEach { h.phone.db.recipeDao().deleteRecipe(it.id) } // lost locally, quietly
        h.phone.db.syncDao().clearChanges()
        h.client.sync()
        assertEquals(listOf("Soup"), names(h.phone))
    }

    @Test
    fun aRebuiltHubAnswersWithEverythingThoughItsCounterIsAboveThePhonesCursor() = runBlocking {
        h.pc.recipes.save(h.doc("Soup", "u-1"))
        h.client.sync(FirstSync.MERGE)
        val cursor = h.phone.db.appMetaDao().get(SyncClient.CURSOR_KEY)!!.toLong()
        // The PC's database is rebuilt: a new file, its counter past the phone's cursor, one recipe of its own.
        val rebuilt = h.newDevice("pc-rebuilt")
        rebuilt.recipes.save(h.doc("Stew", "u-2"))
        repeat(cursor.toInt() + 5) { rebuilt.db.syncDao().bumpChangeSeq() }
        h.hub = SyncHub(rebuilt.db, rebuilt.recipes, rebuilt.images)
        h.client.sync()
        assertTrue(h.lastReply!!.full)
        assertEquals(listOf("Soup", "Stew"), names(h.phone))
        // Having been answered with everything, the phone sends everything next time.
        h.client.sync()
        assertEquals(listOf("Soup", "Stew"), names(rebuilt))
    }

    @Test
    fun thePhoneSendsAWantedPhotoOnlyWhenItsFileHasTheWantedHash() = runBlocking {
        h.client.sync(FirstSync.MERGE)
        File(h.phone.images, "u-1.jpg").writeBytes(jpeg())
        h.phone.recipes.save(h.doc("Soup", "u-1", image = "u-1.jpg"))
        h.failNextSendPhoto = true
        assertThrows(IOException::class.java) { runBlocking { h.client.sync() } }
        // The photo is replaced on disk without a save: it isn't the photo the PC asked for.
        File(h.phone.images, "u-1.jpg").writeBytes(
            ByteArrayOutputStream().also { ImageIO.write(BufferedImage(70, 20, BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray(),
        )
        h.photosSent = 0
        h.client.sync()
        assertEquals(0, h.photosSent)
    }

    private fun jpeg(w: Int, h: Int): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray()

    @Test
    fun aPhoneThatMissedANewerPhotoNeverSendsItsOldOneBack() = runBlocking {
        val old = jpeg(40, 30)
        File(h.pc.images, "u-1.jpg").writeBytes(old)
        val pcSoup = h.pc.recipes.save(h.doc("Soup", "u-1", image = "u-1.jpg"))
        h.client.sync(FirstSync.MERGE)
        assertArrayEquals(old, File(h.phone.images, "u-1.jpg").readBytes())
        // The PC changes the photo; the phone takes the recipe, but fetching the new photo fails.
        val newer = jpeg(50, 50)
        File(h.pc.images, "u-1.jpg").writeBytes(newer)
        h.pc.recipes.setImage(pcSoup, "u-1.jpg")
        h.failNextPhoto = true
        assertThrows(IOException::class.java) { runBlocking { h.client.sync() } }
        // The PC's change is dated well before the phone's edit, which the phone then sends as the newer version.
        h.pc.db.syncDao().setUpdatedAt(SyncTables.RECIPE, "u-1", 1_000L)
        h.phone.recipes.setRating(h.phone.recipes.idForUuid("u-1")!!, 4)
        h.client.sync()
        h.client.sync()
        assertEquals(4, h.pc.db.recipeDao().recipe(pcSoup)!!.rating)
        assertArrayEquals(newer, File(h.pc.images, "u-1.jpg").readBytes())
        assertArrayEquals(newer, File(h.phone.images, "u-1.jpg").readBytes())
    }

    @Test
    fun anotherDeviceEditingTheRecipeDoesntDropTheHubsWishForANewerPhoto() = runBlocking {
        val tablet = h.newDevice("tablet")
        val tabletClient = h.clientFor(tablet)
        val first = jpeg(40, 30)
        File(h.pc.images, "u-1.jpg").writeBytes(first)
        h.pc.recipes.save(h.doc("Soup", "u-1", image = "u-1.jpg"))
        h.pc.db.syncDao().setUpdatedAt(SyncTables.RECIPE, "u-1", 1_000L)
        h.client.sync(FirstSync.MERGE)
        tabletClient.sync(FirstSync.MERGE)
        // The phone changes the photo; the hub takes the recipe and wants the photo, but the upload fails.
        val phones = jpeg(50, 50)
        File(h.phone.images, "u-1.jpg").writeBytes(phones)
        h.phone.recipes.setImage(h.phone.recipes.idForUuid("u-1")!!, "u-1.jpg")
        h.failNextSendPhoto = true
        assertThrows(IOException::class.java) { runBlocking { h.client.sync() } }
        // Dated between the copies the devices have and the tablet's edit to come.
        h.pc.db.syncDao().setUpdatedAt(SyncTables.RECIPE, "u-1", 2_000L)
        // The tablet takes the phone's version (its photo can't be had yet) and edits the recipe.
        tabletClient.sync()
        tablet.recipes.setRating(tablet.recipes.idForUuid("u-1")!!, 5)
        tabletClient.sync()
        // The phone's upload goes through now, and the tablet then fetches it.
        h.client.sync()
        tabletClient.sync()
        assertEquals(5, h.pc.db.recipeDao().recipe(h.pc.recipes.idForUuid("u-1")!!)!!.rating)
        assertArrayEquals(phones, File(h.pc.images, "u-1.jpg").readBytes())
        assertArrayEquals(phones, File(tablet.images, "u-1.jpg").readBytes())
        assertArrayEquals(phones, File(h.phone.images, "u-1.jpg").readBytes())
    }

    @Test
    fun photosTravelByteForByteBothWays() = runBlocking {
        val bytes = jpeg()
        File(h.phone.images, "u-1.jpg").writeBytes(bytes)
        h.phone.recipes.save(h.doc("Soup", "u-1", image = "u-1.jpg"))
        h.client.sync(FirstSync.MERGE)
        assertArrayEquals(bytes, File(h.pc.images, "u-1.jpg").readBytes())
        assertTrue(File(h.pc.images, "u-1_thumb.jpg").isFile)

        val newer = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray()
        File(h.pc.images, "u-1.jpg").writeBytes(newer)
        h.pc.recipes.setImage(h.pc.recipes.idForUuid("u-1")!!, "u-1.jpg")
        h.client.sync()
        assertArrayEquals(newer, File(h.phone.images, "u-1.jpg").readBytes())
    }

    @Test
    fun replaceMakesThePhoneThePcsCopyAndSendsNoDeletes() = runBlocking {
        h.pc.recipes.save(h.doc("Soup", "u-shared"))
        h.client.sync(FirstSync.MERGE)
        assertEquals(listOf("Soup"), names(h.phone))
        // This device starts over: its next sync is a first sync again, and it has a recipe of its own.
        h.phone.db.appMetaDao().remove(SyncClient.CURSOR_KEY)
        h.phone.recipes.save(h.doc("Stew", "u-phone"))
        ShoppingRepository(h.phone.db).addItem("Junk")
        h.client.sync(FirstSync.REPLACE)
        assertEquals(listOf("Soup"), names(h.pc))
        assertEquals(names(h.pc), names(h.phone))
        assertEquals(emptyList<Any>(), h.phone.db.shoppingDao().allInIdOrder())
    }

    @Test
    fun aDeviceThatStartsOverWithReplaceGetsItsOwnRecipesBack() = runBlocking {
        h.pc.recipes.save(h.doc("Soup", "u-pc"))
        h.phone.recipes.save(h.doc("Stew", "u-phone"))
        h.client.sync(FirstSync.MERGE)
        h.phone.db.appMetaDao().remove(SyncClient.CURSOR_KEY)
        h.client.sync(FirstSync.REPLACE)
        assertEquals(listOf("Soup", "Stew"), names(h.pc))
        assertEquals(names(h.pc), names(h.phone))
        assertEquals("u-phone", h.phone.recipes.allRecipes().single { it.name == "Stew" }.recipeUuid)
    }

    @Test
    fun aFullReplyDoesntSendTheDevicesOwnRecordsBackAgain() = runBlocking {
        val s = stoppedClocks() // the phone's records come back with exactly the stamps it sent
        try {
            s.phone.recipes.save(s.doc("Stew", "u-phone"))
            ShoppingRepository(s.phone.db).addItem("Eggs", "6")
            s.client.sync(FirstSync.MERGE)
            val next = s.client.sync()
            assertEquals(0 to 0, next.sent to next.received)
        } finally {
            s.close()
        }
    }

    @Test
    fun aReplaceThatFailsLeavesThisDeviceAsItWas() = runBlocking {
        h.pc.recipes.save(h.doc("Soup", "u-pc"))
        h.phone.recipes.save(h.doc("Stew", "u-phone"))
        ShoppingRepository(h.phone.db).addItem("Bread")
        h.dropNextReply = true
        assertThrows(IOException::class.java) { runBlocking { h.client.sync(FirstSync.REPLACE) } }
        assertEquals(listOf("Stew"), names(h.phone))
        assertEquals(listOf("Bread"), h.phone.db.shoppingDao().allInIdOrder().map { it.name })
        assertFalse(h.client.hasSynced())
        h.client.sync(FirstSync.REPLACE)
        assertEquals(listOf("Soup"), names(h.phone))
    }

    @Test
    fun theSameNameWithAnotherUuidIsListedAsAPossibleDuplicate() = runBlocking {
        h.pc.recipes.save(h.doc("Soup", "u-pc"))
        h.phone.recipes.save(h.doc("soup", "u-phone"))
        val result = h.client.sync(FirstSync.MERGE)
        val dup = result.duplicates.single()
        assertEquals(h.phone.recipes.idForUuid("u-phone"), dup.thisDevicesId)
        assertEquals(h.phone.recipes.idForUuid("u-pc"), dup.pcsId)
    }

    @Test
    fun anEditDuringTheExchangeIsSentNextTime() = runBlocking {
        h.client.sync(FirstSync.MERGE)
        h.duringNextExchange = { ShoppingRepository(h.phone.db).addItem("Bread") }
        h.client.sync()
        assertEquals(emptyList<String>(), h.pc.db.shoppingDao().allInIdOrder().map { it.name })
        h.client.sync()
        assertEquals(listOf("Bread"), h.pc.db.shoppingDao().allInIdOrder().map { it.name })
    }

    @Test
    fun aPhoneWhoseClockRunsAheadStillTakesALaterPcEdit() = runBlocking {
        // The phone's clock runs an hour ahead. Its triggers stamp with the system clock, which both devices share in
        // one process, so the phone's own edit is stamped by its clock by hand, as its triggers would on a real phone.
        val phoneClock = { System.currentTimeMillis() + 3_600_000L }
        val ahead = SyncHarness(phoneClock)
        try {
            val pcSoup = ahead.pc.recipes.save(ahead.doc("Soup", "u-1"))
            ahead.pc.db.syncDao().setUpdatedAt(SyncTables.RECIPE, "u-1", System.currentTimeMillis() - 10_000L)
            ahead.client.sync(FirstSync.MERGE)
            val phoneSoup = ahead.phone.recipes.idForUuid("u-1")!!
            ahead.phone.recipes.setRating(phoneSoup, 4)
            ahead.phone.db.syncDao().setUpdatedAt(SyncTables.RECIPE, "u-1", phoneClock())
            ahead.client.sync()
            assertEquals(4, ahead.pc.db.recipeDao().recipe(pcSoup)!!.rating)
            // Made after the phone's edit, though its raw stamp is an hour behind the phone's.
            ahead.pc.recipes.setRating(pcSoup, 2)
            ahead.client.sync()
            assertEquals(2, ahead.phone.db.recipeDao().recipe(phoneSoup)!!.rating)
        } finally {
            ahead.close()
        }
    }

    // Five recipes on the PC, the first three planned; the phone takes them, the PC's stamps are dated well before now
    // (so the phone's removals are newer), and the phone removes four, its cascade removing the three meals with them.
    private suspend fun phoneRemovesFourOfFive() {
        val ids = (1..5).map { h.pc.recipes.save(h.doc("R$it", "u-$it")) }
        (0..2).forEach { MealPlanRepository(h.pc.db).assign(LocalDate.of(2026, 10, 12 + it), "Dinner", ids[it], null) }
        h.client.sync(FirstSync.MERGE)
        h.pc.db.syncDao().allChanges().forEach { h.pc.db.syncDao().setUpdatedAt(it.tableName, it.syncKey, 1_000L) }
        (1..4).forEach { h.phone.recipes.delete(h.phone.recipes.idForUuid("u-$it")!!) }
        assertEquals(0, h.phone.db.syncDao().meals().size)
        h.client.sync()
        assertEquals(4, h.hub.heldRemovals().size)
    }

    @Test
    fun aHeldMassRemovalKeepsTheMealsOfTheHeldRecipesAndKeepSendsThemBack() = runBlocking {
        phoneRemovesFourOfFive()
        assertEquals(5, h.pc.db.recipeDao().count())
        assertEquals(3, h.pc.db.syncDao().meals().size)
        h.hub.keepHeldRemovals(setOf("u-1", "u-2", "u-3", "u-4"))
        h.client.sync()
        assertEquals(listOf("R1", "R2", "R3", "R4", "R5"), names(h.phone))
        assertEquals(listOf("u-1", "u-2", "u-3"), h.phone.db.syncDao().meals().map { it.recipeUuid }.sorted())
        assertEquals(3, h.pc.db.syncDao().meals().size)
    }

    @Test
    fun confirmingAHeldMassRemovalRemovesTheMealsToo() = runBlocking {
        phoneRemovesFourOfFive()
        assertEquals(4, h.hub.confirmHeldRemovals(setOf("u-1", "u-2", "u-3", "u-4")))
        assertEquals(listOf("R5"), names(h.pc))
        assertEquals(0, h.pc.db.syncDao().meals().size)
        assertEquals(emptyList<Any>(), h.pc.db.syncDao().pending())
    }

    @Test
    fun confirmingTheShownRemovalsLeavesOnesHeldSinceThenHeldAndKeepKeepsOnlyWhatItIsGiven() = runBlocking {
        phoneRemovesFourOfFive()
        // The user was shown u-1 and u-2 (say), and confirmed those: u-3 and u-4, and u-3's meal, stay held.
        assertEquals(2, h.hub.confirmHeldRemovals(setOf("u-1", "u-2")))
        assertEquals(listOf("R3", "R4", "R5"), names(h.pc))
        assertEquals(listOf("u-3"), h.pc.db.syncDao().meals().map { it.recipeUuid })
        assertEquals(listOf("u-3", "u-4"), h.hub.heldRemovals().map { it.uuid }.sorted())
        // Keep for u-3 alone: u-4 stays held, and u-3 and its meal go back to the phone.
        h.hub.keepHeldRemovals(setOf("u-3"))
        assertEquals(listOf("u-4"), h.hub.heldRemovals().map { it.uuid })
        h.client.sync()
        assertEquals(listOf("R3", "R5"), names(h.phone))
        assertEquals(listOf("u-3"), h.phone.db.syncDao().meals().map { it.recipeUuid })
        assertEquals(listOf("R3", "R4", "R5"), names(h.pc))
    }

    // Both clocks stopped at one instant, so a record sent again ties the copy it left on the other side last time and
    // doesn't apply again: only a remembered photo wish can move the photo.
    private fun stoppedClocks(): SyncHarness {
        val now = System.currentTimeMillis() + 60_000L
        return SyncHarness(phoneClock = { now }, hubClock = { now })
    }

    private suspend fun photoWishes(d: SyncHarness.Device) =
        d.db.syncDao().pending().filter { it.reason == SyncApplier.PHOTO }.map { it.syncKey }

    @Test
    fun aFailedPhotoTransferDoesntMakeTheExchangeBeSentAgain() = runBlocking {
        h.client.sync(FirstSync.MERGE)
        File(h.pc.images, "u-1.jpg").writeBytes(jpeg())
        h.pc.recipes.save(h.doc("Soup", "u-1", image = "u-1.jpg"))
        ShoppingRepository(h.phone.db).addItem("Eggs", "6")
        h.failNextPhoto = true
        assertThrows(IOException::class.java) { runBlocking { h.client.sync() } }
        // The exchange itself went through: its marks are kept, and only the photo is tried again.
        assertEquals(h.lastReply!!.cursor.toString(), h.phone.db.appMetaDao().get(SyncClient.CURSOR_KEY))
        val next = h.client.sync()
        assertEquals(0 to 0, next.sent to next.received)
        assertEquals(1, next.photos)
    }

    @Test
    fun afterStartingOverWithReplaceTheRecordsThatCameBackArentSentAgain() = runBlocking {
        h.pc.recipes.save(h.doc("Soup", "u-pc"))
        h.phone.recipes.save(h.doc("Stew", "u-phone"))
        ShoppingRepository(h.phone.db).addItem("Eggs", "6")
        h.client.sync(FirstSync.MERGE)
        h.phone.db.appMetaDao().remove(SyncClient.CURSOR_KEY)
        h.client.sync(FirstSync.REPLACE)
        assertEquals(0, h.client.sync().sent)
    }

    @Test
    fun replaceOnAClientPcTrashesItsRecipeFilesSoTheFolderNeverSendsThemBack() = runBlocking {
        // Stage 4a: a client PC's library is files; clearing only its tables would let the folder sync index them again.
        val s = SyncHarness(withFiles = true)
        try {
            s.pc.recipes.save(s.doc("Soup", "u-soup"))
            s.pc.recipes.save(s.doc("Stew", "u-stew"))
            s.phone.recipes.save(s.doc("Mine", "u-mine"))
            val mine = s.phone.db.recipeDao().allRecipes().single().fileName!!
            val folder = com.naeblis11.mealplanner.folder.RecipeFolder(s.phone.folder, s.phone.db, s.phone.recipes, opener = {})
            folder.sync()

            s.client.sync(FirstSync.REPLACE)
            assertEquals(listOf(mine), s.phone.trashed)
            suspend fun names() = s.phone.db.recipeDao().allRecipes().map { it.name }.toSet()
            assertEquals(setOf("Soup", "Stew"), names())
            // On disk: exactly the hub's recipes, as the index has them.
            val indexed = s.phone.db.recipeDao().allRecipes().map { it.fileName }.toSet()
            assertEquals(indexed, s.phone.folder.listFiles()!!.map { it.name }.toSet())

            // A folder sync afterwards finds nothing to add, and the next exchange sends nothing back.
            folder.sync()
            assertEquals(setOf("Soup", "Stew"), names())
            assertEquals(0, s.client.sync().sent)
            assertEquals(setOf("Soup", "Stew"), s.pc.db.recipeDao().allRecipes().map { it.name }.toSet())
        } finally {
            s.close()
        }
    }

    @Test
    fun aFullExchangeOfAnUnchangedLibraryRewritesNoRecipeFile() = runBlocking {
        val s = SyncHarness(phoneClock = { 5_000_000_000_000L }, hubClock = { 5_000_000_000_000L }, withFiles = true)
        try {
            (1..3).forEach { s.pc.recipes.save(s.doc("R$it", "u-$it")) }
            s.client.sync(FirstSync.MERGE)
            // The client takes the hub's version on a tie: the same recipes, which it must not write again.
            s.phone.fileWrites = 0
            s.phone.db.appMetaDao().put(com.naeblis11.mealplanner.data.AppMetaEntity(SyncClient.FULL_NEXT_KEY, "1"))
            s.client.sync()
            assertTrue(s.lastReply!!.full)
            assertEquals(0, s.phone.fileWrites)
            // The phone's copies are newer than the PC's stamps: the PC takes them, the same recipes, without writing.
            s.pc.db.syncDao().allChanges().forEach { s.pc.db.syncDao().setUpdatedAt(it.tableName, it.syncKey, 1_000L) }
            s.pc.fileWrites = 0
            s.phone.db.appMetaDao().put(com.naeblis11.mealplanner.data.AppMetaEntity(SyncClient.FULL_NEXT_KEY, "1"))
            s.client.sync()
            assertTrue(s.pc.db.syncDao().allChanges().filter { it.tableName == SyncTables.RECIPE }.all { it.updatedAt > 1_000L })
            assertEquals(0, s.pc.fileWrites)
        } finally {
            s.close()
        }
    }

    @Test
    fun aPhoneSendThatFailsIsAskedForAgainNextTime() = runBlocking {
        val s = stoppedClocks()
        try {
            s.client.sync(FirstSync.MERGE)
            val bytes = jpeg()
            File(s.phone.images, "u-1.jpg").writeBytes(bytes)
            s.phone.recipes.save(s.doc("Soup", "u-1", image = "u-1.jpg"))
            s.failNextSendPhoto = true
            assertThrows(IOException::class.java) { runBlocking { s.client.sync() } }
            assertEquals(listOf("u-1"), photoWishes(s.pc))
            s.client.sync()
            assertArrayEquals(bytes, File(s.pc.images, "u-1.jpg").readBytes())
            assertEquals(emptyList<String>(), photoWishes(s.pc))
        } finally {
            s.close()
        }
    }

    @Test
    fun aPhotoFetchThatFailsIsTriedAgainNextTime() = runBlocking {
        val s = stoppedClocks()
        try {
            s.client.sync(FirstSync.MERGE)
            val bytes = jpeg()
            File(s.pc.images, "u-1.jpg").writeBytes(bytes)
            s.pc.recipes.save(s.doc("Soup", "u-1", image = "u-1.jpg"))
            s.failNextPhoto = true
            assertThrows(IOException::class.java) { runBlocking { s.client.sync() } }
            assertEquals(listOf("u-1"), photoWishes(s.phone))
            s.client.sync()
            assertArrayEquals(bytes, File(s.phone.images, "u-1.jpg").readBytes())
            assertEquals(emptyList<String>(), photoWishes(s.phone))
        } finally {
            s.close()
        }
    }

    @Test
    fun theHubRefusesAPhotoThatIsntTheOneItWants() = runBlocking {
        h.client.sync(FirstSync.MERGE)
        File(h.phone.images, "u-1.jpg").writeBytes(jpeg())
        h.phone.recipes.save(h.doc("Soup", "u-1", image = "u-1.jpg"))
        h.failNextSendPhoto = true
        assertThrows(IOException::class.java) { runBlocking { h.client.sync() } }
        val other = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(60, 20, BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray()
        assertFalse(h.hub.receivePhoto("u-1", other))
        assertEquals(listOf("u-1"), photoWishes(h.pc))
        assertFalse(File(h.pc.images, "u-1.jpg").exists())
    }
}
