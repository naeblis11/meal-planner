package com.naeblis11.mealplanner.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PantryRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var pantry: PantryRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        pantry = PantryRepository(db, Dispatchers.Unconfined) { LocalDate.of(2026, 10, 1) }
    }

    @After
    fun tearDown() = db.close()

    private suspend fun item(name: String) = db.pantryDao().byName(name)!!

    @Test
    fun addStampsTodayAndIgnoresTheSameNameInAnyCase() = runTest {
        assertEquals(PantryAdd.Added("Salt"), pantry.add("  Salt ", " "))
        assertEquals(PantryAdd.AlreadyThere("Salt"), pantry.add("SALT", "Spices & Baking"))
        val salt = pantry.observe().first().single()
        assertEquals("2026-10-01", salt.addedOn)
        assertNull(salt.aisle)
        assertTrue(salt.active)
        assertFalse(salt.exactMatch)

        pantry.add("Rice", addedOn = "2026-09-13")
        assertEquals("2026-09-13", item("rice").addedOn)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { pantry.add("  ") } }
    }

    @Test
    fun puttingAnItemBackRestampsIt() = runTest {
        pantry.add("Salt", addedOn = "2026-09-01")
        val id = item("Salt").id
        pantry.setActive(id, false)
        assertFalse(item("Salt").active)
        assertEquals("2026-09-01", item("Salt").addedOn)
        pantry.setActive(id, true)
        assertTrue(item("Salt").active)
        assertEquals("2026-10-01", item("Salt").addedOn)
    }

    @Test
    fun aBadDateChangesNothing() = runTest {
        pantry.add("Salt", addedOn = "2026-09-01")
        val id = item("Salt").id
        assertThrows(IllegalArgumentException::class.java) { runBlocking { pantry.setAddedOn(id, "01/09/2026") } }
        assertEquals("2026-09-01", item("Salt").addedOn)
        pantry.setAddedOn(id, "2026-09-13")
        assertEquals("2026-09-13", item("Salt").addedOn)
        pantry.setAddedOn(id, " ")
        assertNull(item("Salt").addedOn)
    }

    @Test
    fun matchingAisleAndDelete() = runTest {
        pantry.add("Salt")
        val id = item("Salt").id
        pantry.toggleExactMatch(id)
        assertTrue(item("Salt").exactMatch)
        pantry.setAisle(id, " Bulk ")
        assertEquals("Bulk", item("Salt").aisle)
        pantry.setAisle(id, "")
        assertNull(item("Salt").aisle)
        pantry.delete(id)
        assertEquals(emptyList<PantryItemEntity>(), pantry.observe().first())
    }

    @Test
    fun aisleAndDateChangeTogetherOrNotAtAll() = runTest {
        pantry.add("Salt", addedOn = "2026-09-01")
        val id = item("Salt").id
        pantry.updateDetails(id, " Bulk ", "2026-09-13")
        assertEquals("Bulk", item("Salt").aisle)
        assertEquals("2026-09-13", item("Salt").addedOn)

        assertThrows(IllegalArgumentException::class.java) { runBlocking { pantry.updateDetails(id, "Dairy & Eggs", "13/09/2026") } }
        assertEquals("Bulk", item("Salt").aisle)
        assertEquals("2026-09-13", item("Salt").addedOn)
    }
}
