package com.naeblis11.mealplanner.data

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P5-R7's one-tap add from the recipe page: the server's /pantry/add, except that an item marked out is put back. */
class PantryStockTest {
    private val dir: File = Files.createTempDirectory("mp-stock").toFile()
    private val db = AppDatabase.openAt(File(dir, "test.db"))
    private val pantry = PantryRepository(db, today = { LocalDate.of(2026, 10, 5) })

    @After
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    @Test
    fun aNewNameIsAddedStampedToday() = runBlocking {
        assertEquals(PantryStock(Stocked.ADDED, "Stock"), pantry.stock("  Stock "))
        val item = db.pantryDao().byName("stock")!!
        assertTrue(item.active)
        assertEquals("2026-10-05", item.addedOn)
    }

    @Test
    fun anItemMarkedOutIsPutBackAndRestamped() = runBlocking {
        pantry.add("Stock", addedOn = "2026-01-01")
        val id = db.pantryDao().byName("Stock")!!.id
        pantry.setActive(id, false)

        assertEquals(PantryStock(Stocked.PUT_BACK, "Stock"), pantry.stock("stock"))
        val item = db.pantryDao().item(id)!!
        assertTrue(item.active)
        assertEquals("2026-10-05", item.addedOn)
    }

    @Test
    fun anItemOnHandIsLeftAsItIs() = runBlocking {
        pantry.add("Salt", addedOn = "2026-01-01")
        assertEquals(PantryStock(Stocked.ALREADY_THERE, "Salt"), pantry.stock("SALT"))
        assertEquals("2026-01-01", db.pantryDao().byName("salt")!!.addedOn)
    }
}
