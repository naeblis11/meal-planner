package com.naeblis11.mealplanner.sync

import com.naeblis11.mealplanner.data.MealPlanRepository
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.LocalDate
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** LAN sync stage 2c (D3-D5): the phone's sync as its screens drive it, against a real hub in-process. */
class PhoneSyncControlsTest {
    private val harness = SyncHarness()
    private val connector = HubConnector(harness)
    private val store = MemoryPairingStore()
    private val finder = FakePcFinder()

    @Volatile
    private var wifi = true
    private val engine = PhoneSync(harness.phone.db, harness.phone.recipes, harness.phone.images, store, finder, "Pixel", connector)
    private val controls = PhoneSyncControls(engine, harness.phone.recipes, harness.phone.images, wifi = { wifi })

    @After
    fun tearDown() {
        controls.close()
        harness.close()
    }

    private suspend fun paired() {
        connector.answerAt(HOME)
        controls.pairScanned(HubConnector.qr(HOME))
    }

    // The outcome of the sync just started, once it has finished.
    private suspend fun settled(): String = withTimeout(10_000) {
        controls.syncing.first { !it }
        controls.outcome.filterNotNull().first()
    }

    @Test
    fun itReadsThePairingAtStart() = runBlocking<Unit> {
        store.save(PhonePairing(listOf(HOME), 5001, HubConnector.FP, "KITCHEN", HubConnector.KEY, "f".repeat(32), HubConnector.HOUSEHOLD))
        val later = PhoneSyncControls(engine, harness.phone.recipes, harness.phone.images, wifi = { true })
        try {
            assertEquals(SyncStatus(known = true, pcName = "KITCHEN", lastSyncAt = null), withTimeout(5_000) { later.status.first { it.known } })
        } finally {
            later.close()
        }
    }

    @Test
    fun aFirstMergeRunsOnceAndSaysWhatMoved() = runBlocking<Unit> {
        harness.pc.recipes.save(harness.doc("Soup", "u-pc"))
        harness.phone.recipes.save(harness.doc("Stew", "u-phone"))
        paired()
        assertEquals("KITCHEN", controls.status.value.pcName)
        assertTrue(controls.needsFirstChoice())
        controls.sync(FirstSync.MERGE)
        // A second tap while it runs (or after a rotation) starts nothing.
        controls.sync(FirstSync.MERGE)
        assertTrue(settled().startsWith("Sent 1 change, got "))
        assertEquals(1, connector.exchanges.size)
        assertFalse(controls.needsFirstChoice())
        assertNotNull(controls.status.value.lastSyncAt)
    }

    @Test
    fun possibleDuplicatesWaitUntilEachIsSettled() = runBlocking<Unit> {
        harness.pc.recipes.save(harness.doc("Soup", "u-pc"))
        harness.pc.recipes.save(harness.doc("Chili", "u-pc-chili"))
        harness.phone.recipes.save(harness.doc("Soup", "u-phone"))
        harness.phone.recipes.save(harness.doc("chili", "u-phone-chili"))
        paired()
        controls.sync(FirstSync.MERGE)
        settled()
        val found = controls.duplicates.value
        assertEquals(listOf("Chili", "Soup"), found.map { it.name }.sorted())
        controls.keepBoth(found.single { it.name == "Soup" })
        controls.keepPcs(found.single { it.name == "Chili" })
        withTimeout(5_000) { while (harness.phone.recipes.allRecipes().any { it.recipeUuid == "u-phone-chili" }) delay(20) }
        assertTrue(controls.duplicates.value.isEmpty())
        assertEquals(listOf("Chili", "Soup", "Soup"), harness.phone.recipes.allRecipes().map { it.name }.sorted())
    }

    @Test
    fun keepThePcsMovesThisPhonesPlannedMealsToThePcsCopy() = runBlocking<Unit> {
        harness.pc.recipes.save(harness.doc("Soup", "u-pc"))
        harness.phone.recipes.save(harness.doc("Soup", "u-phone"))
        paired()
        controls.sync(FirstSync.MERGE)
        controls.outcomeShown(settled())
        val duplicate = controls.duplicates.value.single()
        val meals = MealPlanRepository(harness.phone.db)
        meals.assign(MONDAY, "Dinner", duplicate.thisDevicesId, "6")
        controls.keepPcs(duplicate)
        withTimeout(5_000) { while (harness.phone.recipes.idForUuid("u-phone") != null) delay(20) }
        val moved = meals.assignment(MONDAY, "Dinner")
        assertNotNull("the meal planned with this phone's copy must not be deleted", moved)
        assertEquals(duplicate.pcsId, moved!!.recipeId)
        assertEquals("6", moved.servings)
        // The next sync sends the meal with the PC's recipe, and the copy's removal.
        controls.sync()
        assertTrue(settled().startsWith("Sent "))
        val onPc = harness.pc.db.mealPlanDao().assignment(MONDAY.toString(), "Dinner")
        assertEquals(harness.pc.recipes.idForUuid("u-pc"), onPc?.recipeId)
        assertNull(harness.pc.recipes.idForUuid("u-phone"))
    }

    @Test
    fun aMealPlannedBeforeTheMergeMovesToThePcsCopyOnThePcToo() = runBlocking<Unit> {
        harness.pc.recipes.save(harness.doc("Soup", "u-pc"))
        harness.phone.recipes.save(harness.doc("Soup", "u-phone"))
        val meals = MealPlanRepository(harness.phone.db)
        meals.assign(MONDAY, "Dinner", harness.phone.recipes.idForUuid("u-phone")!!, "6")
        paired()
        controls.sync(FirstSync.MERGE)
        controls.outcomeShown(settled())
        // The Merge's full exchange took the meal to the PC, on the phone's copy.
        assertEquals(harness.pc.recipes.idForUuid("u-phone"), harness.pc.db.mealPlanDao().assignment(MONDAY.toString(), "Dinner")?.recipeId)
        val duplicate = controls.duplicates.value.single()
        controls.keepPcs(duplicate)
        withTimeout(5_000) { while (harness.phone.recipes.idForUuid("u-phone") != null) delay(20) }
        controls.sync()
        controls.outcomeShown(settled())
        // The moved meal reached the PC before the copy's removal could cascade it away.
        val slot = "$MONDAY|Dinner"
        val onPc = harness.pc.db.mealPlanDao().assignment(MONDAY.toString(), "Dinner")
        assertEquals(harness.pc.recipes.idForUuid("u-pc"), onPc?.recipeId)
        assertEquals("6", onPc?.servings)
        assertNull(harness.pc.recipes.idForUuid("u-phone"))
        assertEquals(false, harness.pc.db.syncDao().change(SyncTables.MEAL, slot)?.deleted)
        // And a second sync leaves both sides agreeing.
        controls.sync()
        assertTrue(settled().startsWith("Sent "))
        assertEquals(harness.phone.recipes.idForUuid("u-pc"), meals.assignment(MONDAY, "Dinner")?.recipeId)
        assertEquals(harness.pc.recipes.idForUuid("u-pc"), harness.pc.db.mealPlanDao().assignment(MONDAY.toString(), "Dinner")?.recipeId)
    }

    @Test
    fun keepThePcsWhenThePcsCopyIsGoneRemovesThisPhonesCopyAsADelete() = runBlocking<Unit> {
        harness.pc.recipes.save(harness.doc("Soup", "u-pc"))
        harness.phone.recipes.save(harness.doc("Soup", "u-phone"))
        paired()
        controls.sync(FirstSync.MERGE)
        controls.outcomeShown(settled())
        val duplicate = controls.duplicates.value.single()
        val meals = MealPlanRepository(harness.phone.db)
        meals.assign(MONDAY, "Dinner", duplicate.thisDevicesId, null)
        harness.phone.recipes.delete(duplicate.pcsId)
        controls.keepPcs(duplicate)
        withTimeout(5_000) { while (harness.phone.recipes.idForUuid("u-phone") != null) delay(20) }
        assertNull(meals.assignment(MONDAY, "Dinner"))
        assertNull(controls.outcome.value)
    }

    @Test
    fun aReplaceRefusedWhileAPairingRunsDeletesItsEmptyBackupAndSaysSo() = runBlocking<Unit> {
        paired()
        val inFlight = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        connector.duringNextPair = {
            inFlight.complete(Unit)
            release.await()
        }
        val pairing = async { controls.pairScanned(HubConnector.qr(HOME)) }
        withTimeout(5_000) { inFlight.await() }
        var deleted = false
        controls.replace(open = { error("must not back up") }, deleteBackup = { deleted = true })
        // The picker already made the file: a refused Replace must not leave an empty "backup" behind.
        assertTrue(deleted)
        assertEquals(SyncMessages.REPLACE_REFUSED, controls.outcome.value)
        assertFalse(controls.syncing.value)
        release.complete(Unit)
        withTimeout(5_000) { pairing.await() }
        assertTrue(connector.exchanges.isEmpty())
    }

    @Test
    fun replaceSavesTheBackupBeforeAnythingChanges() = runBlocking<Unit> {
        harness.pc.recipes.save(harness.doc("Soup", "u-pc"))
        harness.phone.recipes.save(harness.doc("Stew", "u-phone"))
        paired()
        val backup = ByteArrayOutputStream()
        controls.replace(open = { backup }, deleteBackup = { error("must not delete") })
        assertTrue(settled().startsWith("Sent 0 changes, got "))
        ZipInputStream(backup.toByteArray().inputStream()).use { assertEquals("recipes/stew.yaml", it.nextEntry!!.name) }
        assertEquals(listOf("Soup"), harness.phone.recipes.allRecipes().map { it.name })
    }

    @Test
    fun aBackupThatFailsReplacesNothing() = runBlocking<Unit> {
        harness.phone.recipes.save(harness.doc("Stew", "u-phone"))
        paired()
        var deleted = false
        controls.replace(open = { throw IOException("disk full") }, deleteBackup = { deleted = true })
        assertEquals(SyncMessages.BACKUP_FAILED, settled())
        assertTrue(deleted)
        assertTrue(connector.exchanges.isEmpty())
        assertEquals(listOf("Stew"), harness.phone.recipes.allRecipes().map { it.name })
    }

    @Test
    fun aPhoneRemovedOnThePcSaysSoByName() = runBlocking<Unit> {
        paired()
        controls.sync(FirstSync.MERGE)
        controls.outcomeShown(settled())
        assertNull(controls.outcome.value)
        connector.removed = true
        controls.sync()
        assertEquals("This phone was removed from KITCHEN. Your data is still here.", settled())
        assertNull(controls.status.value.pcName)
    }

    @Test
    fun anOlderOutcomeShownLeavesANewerOne() = runBlocking<Unit> {
        paired()
        connector.hosts.clear()
        controls.sync(FirstSync.MERGE)
        assertEquals(SyncHttp.UNREACHABLE, settled())
        controls.outcomeShown("something older")
        assertEquals(SyncHttp.UNREACHABLE, controls.outcome.value)
    }

    @Test
    fun joinAndLeaveFollowThePairing() = runBlocking<Unit> {
        assertFalse(withTimeout(5_000) { controls.status.first { it.known } }.paired)
        paired()
        assertEquals("KITCHEN", controls.status.value.pcName)
        controls.leave()
        assertNull(controls.status.value.pcName)
        assertEquals(listOf("$HOME:5001"), connector.left.toList())
    }

    @Test
    fun leaveIsRefusedWhileASyncRuns() = runBlocking<Unit> {
        paired()
        val inFlight = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        harness.duringNextExchange = {
            inFlight.complete(Unit)
            release.await()
        }
        controls.sync(FirstSync.MERGE)
        withTimeout(5_000) { inFlight.await() }
        assertTrue(controls.syncing.value)
        assertThrows(IllegalStateException::class.java) { runBlocking { controls.leave() } }
        release.complete(Unit)
        assertTrue(settled().startsWith("Sent "))
        assertTrue(connector.left.isEmpty())
        assertEquals("KITCHEN", controls.status.value.pcName)
    }

    @Test
    fun noSyncReplaceOrLeaveStartsWhileAPairingRuns() = runBlocking<Unit> {
        paired()
        controls.sync(FirstSync.MERGE)
        controls.outcomeShown(settled())
        val inFlight = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        connector.duringNextPair = {
            inFlight.complete(Unit)
            release.await()
        }
        val pairing = async { controls.pairScanned(HubConnector.qr(HOME)) }
        withTimeout(5_000) { inFlight.await() }
        // A sync now would run against marks the new pairing is about to clear: each is refused, not queued.
        controls.sync()
        // The refused sync says nothing (only a refused Replace does, F3).
        assertNull(controls.outcome.value)
        controls.replace(open = { error("must not back up") }, deleteBackup = {})
        assertFalse(controls.syncing.value)
        assertThrows(IllegalStateException::class.java) { runBlocking { controls.leave() } }
        release.complete(Unit)
        withTimeout(5_000) { pairing.await() }
        assertFalse(controls.syncing.value)
        assertEquals(1, connector.exchanges.size)
        assertTrue(connector.left.isEmpty())
        // The refused Replace says so (F3); the refused sync says nothing.
        assertEquals(SyncMessages.REPLACE_REFUSED, controls.outcome.value)
        controls.outcomeShown(SyncMessages.REPLACE_REFUSED)
        // Once the pairing is done, the next tap syncs (the new pairing asks the first-sync question again).
        assertTrue(controls.needsFirstChoice())
        controls.sync(FirstSync.MERGE)
        assertTrue(settled().startsWith("Sent "))
        assertEquals(2, connector.exchanges.size)
    }

    @Test
    fun aPairingAskedForWhileASyncRunsWaitsForIt() = runBlocking<Unit> {
        paired()
        val inFlight = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        harness.duringNextExchange = {
            inFlight.complete(Unit)
            release.await()
        }
        controls.sync(FirstSync.MERGE)
        withTimeout(5_000) { inFlight.await() }
        val pairing = async { controls.pairScanned(HubConnector.qr(HOME)) }
        release.complete(Unit)
        withTimeout(5_000) { pairing.await() }
        // The sync finished first, under the old pairing; the new pairing then cleared its marks.
        assertEquals(1, connector.exchanges.size)
        assertTrue(controls.needsFirstChoice())
        assertFalse(controls.syncing.value)
    }

    @Test
    fun aTypedCodePairsWithAPcFound() = runBlocking<Unit> {
        finder.found = listOf(FoundPc("KITCHEN", HOME, 5001))
        connector.answerAt(HOME)
        val pcs = controls.findPcs()
        controls.pairTyped(HubConnector.CODE, pcs.single())
        assertEquals("KITCHEN", controls.status.value.pcName)
    }

    @Test
    fun theWifiIsReadAgainWhenAsked() {
        assertTrue(controls.onWifi.value)
        wifi = false
        controls.recheckWifi()
        assertFalse(controls.onWifi.value)
    }

    private companion object {
        const val HOME = "192.0.2.20"
        val MONDAY: LocalDate = LocalDate.of(2026, 10, 12)
    }
}
