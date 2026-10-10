package com.naeblis11.mealplanner.data

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** P6-R4: when the household was created, kept in app_meta beside its id and never changed once written. */
class HouseholdCreatedTest {
    private val dir: File = Files.createTempDirectory("mp-household").toFile()
    private val db = AppDatabase.openAt(File(dir, "test.db"))
    private val meta = db.appMetaDao()

    @After
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    @Test
    fun aNewHouseholdKeepsWhenItWasMadeWithItsId() = runBlocking {
        Household(db, clock = { 1_000L }) { ByteArray(16) { 1 } }.id()
        assertEquals("1000", meta.get(Household.CREATED_KEY))
        assertEquals(1_000L, Household(db, clock = { 1_000L }).created())
        // Asked again later, by another instance with another clock and a fallback, it is still the first time.
        assertEquals(1_000L, Household(db, clock = { 9_000L }, createdFallback = { 5_000L }).created())
    }

    @Test
    fun askingWhenFirstMakesTheIdToo() = runBlocking {
        assertEquals(1_234L, Household(db, clock = { 1_234L }).created())
        assertNotNull(meta.get(Household.KEY))
    }

    @Test
    fun anIdFromBeforePlan6DatesFromTheFallbackAndKeepsIt() = runBlocking {
        meta.put(AppMetaEntity(Household.KEY, "000102030405060708090a0b0c0d0e0f"))
        assertEquals(4_000L, Household(db, clock = { 9_000L }, createdFallback = { 4_000L }).created())
        assertEquals("4000", meta.get(Household.CREATED_KEY))
        assertEquals(4_000L, Household(db, clock = { 20_000L }, createdFallback = { 15_000L }).created())
    }

    @Test
    fun aFallbackThatIsUnknownOrLaterThanNowGivesNow() = runBlocking {
        meta.put(AppMetaEntity(Household.KEY, "000102030405060708090a0b0c0d0e0f"))
        assertEquals(9_000L, Household(db, clock = { 9_000L }, createdFallback = { 20_000L }).created())
        val other = AppDatabase.openAt(File(dir, "other.db"))
        try {
            other.appMetaDao().put(AppMetaEntity(Household.KEY, "000102030405060708090a0b0c0d0e0f"))
            assertEquals(7_000L, Household(other, clock = { 7_000L }, createdFallback = { null }).created())
        } finally {
            other.close()
        }
    }

    @Test
    fun aTimeThatIsNotANumberIsReplaced() = runBlocking {
        meta.put(AppMetaEntity(Household.KEY, "000102030405060708090a0b0c0d0e0f"))
        meta.put(AppMetaEntity(Household.CREATED_KEY, "yesterday"))
        assertEquals(4_000L, Household(db, clock = { 9_000L }, createdFallback = { 4_000L }).created())
        assertEquals("4000", meta.get(Household.CREATED_KEY))
    }
}
