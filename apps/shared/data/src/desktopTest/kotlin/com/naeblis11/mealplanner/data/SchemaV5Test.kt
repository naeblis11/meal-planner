package com.naeblis11.mealplanner.data

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** Schema 5 (P5-R2): the Google events "Send this week" wrote, and the household id their ids come from. */
class SchemaV5Test {
    private val dir: File = Files.createTempDirectory("mp-v5").toFile()
    private val db = AppDatabase.openAt(File(dir, "test.db"))

    @After
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    @Test
    fun aGoogleEventIsRecordedOncePerCalendarDayAndSlot() = runBlocking {
        val dao = db.googleEventDao()
        dao.record(GoogleEventEntity("family", "2026-10-05", "Dinner", "mpa", "h1"))
        dao.record(GoogleEventEntity("family", "2026-10-05", "Dinner", "mpa", "h2"))
        dao.record(GoogleEventEntity("work", "2026-10-05", "Dinner", "mpb", "h3"))
        dao.record(GoogleEventEntity("family", "2026-10-12", "Dinner", "mpc", "h4"))
        assertEquals(listOf("h2"), dao.inRange("family", "2026-10-05", "2026-10-11").map { it.contentHash })

        dao.forget("family", "2026-10-05", "Dinner")
        assertEquals(listOf("family" to "mpc", "work" to "mpb"), dao.all().map { it.calendarId to it.eventId })
    }

    @Test
    fun theHouseholdIdIsMadeOnceAndKept() = runBlocking {
        val first = Household(db) { ByteArray(16) { it.toByte() } }.id()
        assertEquals("000102030405060708090a0b0c0d0e0f", first)
        // Another instance, even with other random bytes, finds the one kept in app_meta.
        assertEquals(first, Household(db) { ByteArray(16) { 7 } }.id())
        assertEquals(first, db.appMetaDao().get(Household.KEY))
    }

    @Test
    fun manyFirstAsksAtOnceAgreeOnOneId() = runBlocking {
        val made = AtomicInteger()
        val household = Household(db) {
            val n = made.incrementAndGet()
            ByteArray(16) { n.toByte() }
        }
        val ids = (1..8).map { async(Dispatchers.IO) { household.id() } }.awaitAll()
        assertEquals(1, ids.toSet().size)
        assertEquals(ids.first(), db.appMetaDao().get(Household.KEY))
    }
}
