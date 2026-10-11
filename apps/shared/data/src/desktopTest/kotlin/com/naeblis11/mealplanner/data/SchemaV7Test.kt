package com.naeblis11.mealplanner.data

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** Schema 7 (LAN sync, stage 2a): records that can't be applied yet wait in sync_pending. */
class SchemaV7Test {
    private val dir: File = Files.createTempDirectory("mp-v7").toFile()
    private val db = AppDatabase.openAt(File(dir, "test.db"))

    @After
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    @Test
    fun aPendingRecordIsKeptOncePerKeyAndDropped() = runBlocking {
        val sync = db.syncDao()
        sync.putPending(SyncPendingEntity("meal_plan", "2026-10-12|Dinner", "missing_recipe", "{}"))
        sync.putPending(SyncPendingEntity("meal_plan", "2026-10-12|Dinner", "missing_recipe", "{\"a\":1}"))
        assertEquals(listOf("{\"a\":1}"), sync.pending().map { it.record })
        sync.dropPending("meal_plan", "2026-10-12|Dinner")
        assertEquals(emptyList<SyncPendingEntity>(), sync.pending())
    }

    @Test
    fun aChangeCanBePutBackAndTheCounterStepped() = runBlocking {
        val sync = db.syncDao()
        val before = SyncSchema.changeSeq(db)
        sync.bumpChangeSeq()
        assertEquals(before + 1, SyncSchema.changeSeq(db))
        val change = SyncChangeEntity("recipe", "u-1", 5L, "dev", 9L, false)
        sync.putChange(change)
        assertEquals(listOf(change), sync.allChanges())
        sync.deleteChange("recipe", "u-1")
        assertEquals(emptyList<SyncChangeEntity>(), sync.allChanges())
    }
}
