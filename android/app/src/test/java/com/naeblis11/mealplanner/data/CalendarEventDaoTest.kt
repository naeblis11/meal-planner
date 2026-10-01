package com.naeblis11.mealplanner.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CalendarEventDaoTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun row(calendar: Long, date: String, slot: String, event: Long) =
        CalendarEventEntity(calendarId = calendar, date = date, slot = slot, eventId = event, contentHash = "h$event")

    @Test
    fun oneEventPerCalendarDayAndSlot() = runTest {
        val dao = db.calendarEventDao()
        dao.record(row(1, "2026-09-28", "Dinner", 101))
        dao.record(row(1, "2026-09-28", "Dinner", 102)) // re-recorded: replaces
        dao.record(row(2, "2026-09-28", "Dinner", 201)) // another calendar: its own row
        dao.record(row(1, "2026-10-05", "Lunch", 103)) // next week

        assertEquals(listOf(102L), dao.inRange(1, "2026-09-28", "2026-10-04").map { it.eventId })
        assertEquals("h102", dao.inRange(1, "2026-09-28", "2026-10-04").single().contentHash)

        dao.forget(1, "2026-09-28", "Dinner")
        assertEquals(listOf(103L, 201L), dao.all().map { it.eventId })
    }

    @Test
    fun theMealPlanReadsAWeekAtOnce() = runTest {
        val soup = db.recipeDao().insertRecipe(
            RecipeEntity(
                recipeUuid = "u-soup", name = "Soup", author = null, sourceAuthorsJson = null, sourceUrl = null,
                sourceBookJson = null, ovenTempJson = null, ovenFan = null, ovenTime = null, yieldsJson = null,
                notesJson = null, category = null, subcategory = null, imageFilename = null, rating = null, rawYaml = "",
            ),
        )
        db.mealPlanDao().put(MealPlanEntity(date = "2026-09-28", slot = "Dinner", recipeId = soup, servings = "6"))
        db.mealPlanDao().put(MealPlanEntity(date = "2026-10-05", slot = "Dinner", recipeId = soup, servings = null))
        assertEquals(
            listOf("2026-09-28 Dinner Soup 6"),
            db.mealPlanDao().range("2026-09-28", "2026-10-04").map { "${it.date} ${it.slot} ${it.recipeName} ${it.servings}" },
        )
    }
}
