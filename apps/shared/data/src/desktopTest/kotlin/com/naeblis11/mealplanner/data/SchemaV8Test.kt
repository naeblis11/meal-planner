package com.naeblis11.mealplanner.data

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Schema 8 (LAN sync, stage 2b): the master PC's paired devices, kept by device id and found by their key's hash. Not synced. */
class SchemaV8Test {
    private val dir: File = Files.createTempDirectory("mp-v8").toFile()
    private val db = AppDatabase.openAt(File(dir, "test.db"))
    private val id = "0123456789abcdef0123456789abcdef"

    @After
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    @Test
    fun aDeviceIsFoundByItsKeyHashAndReplacedWhenItPairsAgain() = runBlocking<Unit> {
        val dao = db.pairedDeviceDao()
        dao.put(PairedDeviceEntity(id, "Pixel", "phone", "hash-1", 10L))
        assertEquals("Pixel", dao.byKeyHash("hash-1")!!.name)
        dao.put(PairedDeviceEntity(id, "Pixel 9", "phone", "hash-2", 20L))
        assertNull(dao.byKeyHash("hash-1"))
        assertEquals(listOf("Pixel 9"), dao.all().map { it.name })
        assertEquals(1, dao.count())
    }

    @Test
    fun aSyncIsDatedAndARemovedDeviceIsGone() = runBlocking<Unit> {
        val dao = db.pairedDeviceDao()
        dao.put(PairedDeviceEntity(id, "Pixel", "phone", "hash-1", 10L))
        assertNull(dao.byId(id)!!.lastSyncAt)
        dao.touch(id, 99L)
        assertEquals(99L, dao.byId(id)!!.lastSyncAt)
        assertEquals(listOf(id), dao.observeAll().first().map { it.deviceId })
        dao.remove(id)
        assertEquals(0, dao.count())
        assertEquals(emptyList<PairedDeviceEntity>(), dao.observeAll().first())
    }

    @Test
    fun pairingIsNotASyncedChange() = runBlocking<Unit> {
        db.pairedDeviceDao().put(PairedDeviceEntity(id, "Pixel", "phone", "hash-1", 10L))
        assertEquals(emptyList<SyncChangeEntity>(), db.syncDao().allChanges())
    }

    @Test
    fun theKeyHashIsNeverInToString() {
        assertFalse(PairedDeviceEntity(id, "Pixel", "phone", "secret-hash", 10L).toString().contains("secret-hash"))
    }
}
