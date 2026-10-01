package com.naeblis11.mealplanner.calendar

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.MealPlanRepository
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.YamlMap
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The policy of "Send this week", as tests/test_gcal.py has it for the Pi: idempotent,
 * one way, and never touching an event it didn't record.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CalendarSyncTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val gateway = FakeCalendarGateway()
    private val zone = ZoneId.of("America/Toronto")
    private val monday = LocalDate.of(2026, 9, 28)
    private lateinit var db: AppDatabase
    private lateinit var recipes: RecipeRepository
    private lateinit var plans: MealPlanRepository
    private lateinit var choice: CalendarChoice
    private lateinit var sync: CalendarSync

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        recipes = RecipeRepository(db, Files.createTempDirectory("images").toFile(), dispatcher = Dispatchers.Unconfined)
        plans = MealPlanRepository(db, Dispatchers.Unconfined)
        choice = CalendarChoice(prefs("calendar-sync-test"))
        choice.choose(gateway.calendars[0])
        sync = CalendarSync(db, gateway, choice, Dispatchers.Unconfined) { zone }
    }

    @After
    fun tearDown() = db.close()

    private fun prefs(name: String) =
        context.getSharedPreferences(name, Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    @Suppress("UNCHECKED_CAST")
    private suspend fun recipe(name: String, more: String = ""): Long = recipes.save(
        RecipeYaml.load(
            "recipe_name: $name\ningredients:\n- Rice:\n    amounts:\n    - amount: 1\n      unit: cup\n${more}steps:\n- step: Cook.\n",
        ) as YamlMap,
    )

    private suspend fun send(start: LocalDate = monday) = sync.sendWeek(start) as SendOutcome.Sent

    private fun at(day: LocalDate, hour: Int) = ZonedDateTime.of(day, LocalTime.of(hour, 0), zone).toInstant().toEpochMilli()

    @Test
    fun aPlannedMealBecomesOneTimedEvent() = runTest {
        val butter = "- Butter:\n    amounts:\n    - amount: 2\n      unit: tbsp\n    section: Topping\n"
        plans.assign(monday, "Dinner", recipe("Soup", butter), "6")

        assertEquals(1, send().added)

        val (calendarId, event) = gateway.events.values.single()
        assertEquals(1L, calendarId)
        assertEquals("Dinner: Soup", event.title)
        assertEquals(at(monday, 18), event.startMillis)
        assertEquals(at(monday, 19), event.endMillis)
        assertEquals("America/Toronto", event.timeZone)
        assertTrue(event.description, event.description.startsWith("Servings: 6\n\nIngredients:\n  - "))
        assertTrue(event.description, event.description.contains("Rice\n\nTopping:\n  - "))
        assertTrue(event.description, event.description.endsWith("Butter\n\nAdded by Meal Planner."))
        assertEquals(listOf("2026-09-28 Dinner"), db.calendarEventDao().all().map { "${it.date} ${it.slot}" })
    }

    @Test
    fun anEmptyWeekWritesNothing() = runTest {
        val sent = send()
        assertEquals(listOf(0, 0, 0, 0), listOf(sent.added, sent.updated, sent.removed, sent.unchanged))
        assertEquals(emptyList<String>(), gateway.writes)
    }

    @Test
    fun aSecondSendWithNoChangesWritesNothing() = runTest {
        plans.assign(monday, "Dinner", recipe("Soup"), null)
        send()
        gateway.writes.clear()

        val again = send()
        assertEquals(listOf(0, 0, 0, 1), listOf(again.added, again.updated, again.removed, again.unchanged))
        assertEquals(emptyList<String>(), gateway.writes)
        assertEquals(1, gateway.events.size)
    }

    @Test
    fun changingTheServingsOrRecipeUpdatesTheSameEvent() = runTest {
        val soup = recipe("Soup")
        plans.assign(monday, "Dinner", soup, "4")
        send()
        val id = gateway.events.keys.single()

        plans.assign(monday, "Dinner", soup, "8")
        assertEquals(1, send().updated)
        assertTrue(gateway.events.getValue(id).event.description.startsWith("Servings: 8"))

        plans.assign(monday, "Dinner", recipe("Stew"), null)
        assertEquals(1, send().updated)
        assertEquals(listOf(id), gateway.events.keys.toList())
        assertEquals(listOf("Dinner: Stew"), gateway.titles())
    }

    @Test
    fun removingAMealDeletesItsEventAndStopsTrackingIt() = runTest {
        plans.assign(monday, "Dinner", recipe("Soup"), null)
        send()
        plans.unassign(monday, "Dinner")

        assertEquals(1, send().removed)
        assertEquals(emptyList<String>(), gateway.titles())
        assertEquals(0, db.calendarEventDao().all().size)
    }

    @Test
    fun movingAMealMovesItsEvent() = runTest {
        val tacos = recipe("Tacos")
        plans.assign(monday, "Dinner", tacos, null)
        send()
        plans.unassign(monday, "Dinner")
        plans.assign(monday.plusDays(2), "Dinner", tacos, null)

        val sent = send()
        assertEquals(listOf(1, 1), listOf(sent.added, sent.removed))
        assertEquals(at(monday.plusDays(2), 18), gateway.events.values.single().event.startMillis)
    }

    @Test
    fun onlyRecordedEventsAreEverTouched() = runTest {
        val dentist = gateway.addTheirOwn(1L, "Dentist")
        plans.assign(monday, "Dinner", recipe("Soup"), null)
        send()
        plans.unassign(monday, "Dinner")
        send()

        assertEquals(listOf("Dentist"), gateway.titles())
        assertTrue(gateway.writes.toString(), gateway.writes.none { it.endsWith(" $dentist") })
    }

    @Test
    fun anEventDeletedByHandComesBackOnTheNextSend() = runTest {
        plans.assign(monday, "Dinner", recipe("Soup"), null)
        send()
        gateway.deleteByHand(gateway.events.keys.single())

        assertEquals(1, send().added)
        assertEquals(listOf("Dinner: Soup"), gateway.titles())
        // Tracked again under its new id: the next send writes nothing.
        gateway.writes.clear()
        send()
        assertEquals(emptyList<String>(), gateway.writes)
    }

    @Test
    fun anEventDeletedByHandIsRecreatedWhenItsMealChanged() = runTest {
        val soup = recipe("Soup")
        plans.assign(monday, "Dinner", soup, "4")
        send()
        gateway.deleteByHand(gateway.events.keys.single())
        plans.assign(monday, "Dinner", soup, "8")

        val sent = send()
        assertEquals(listOf(1, 0), listOf(sent.added, sent.updated))
        assertEquals(1, gateway.events.size)
    }

    @Test
    fun removalSurvivesTheEventAlreadyBeingGone() = runTest {
        plans.assign(monday, "Dinner", recipe("Soup"), null)
        send()
        gateway.deleteByHand(gateway.events.keys.single())
        plans.unassign(monday, "Dinner")

        assertEquals(1, send().removed)
        assertEquals(0, db.calendarEventDao().all().size)
    }

    @Test
    fun eachMealIsWrittenOnItsOwnAndFailuresAreListed() = runTest {
        val tuesday = monday.plusDays(1)
        plans.assign(monday, "Dinner", recipe("Soup"), null)
        plans.assign(tuesday, "Lunch", recipe("Stew"), null)
        gateway.failTitles += "Lunch: Stew"

        val sent = send()
        assertEquals(1, sent.added)
        assertEquals(listOf(SendFailure(tuesday, "Lunch", "The calendar refused Lunch: Stew")), sent.failures)
        assertEquals(listOf("Dinner: Soup"), gateway.titles())

        gateway.failTitles.clear()
        gateway.writes.clear()
        val retry = send()
        assertEquals(listOf(1, 1), listOf(retry.added, retry.unchanged))
        assertEquals(emptyList<SendFailure>(), retry.failures)
        assertEquals(1, gateway.writes.size)
    }

    @Test
    fun otherWeeksAreLeftAlone() = runTest {
        val soup = recipe("Soup")
        plans.assign(monday, "Dinner", soup, null)
        plans.assign(monday.plusDays(9), "Dinner", soup, null)
        send()
        assertEquals(1, gateway.events.size)

        send(monday.plusDays(7))
        assertEquals(2, gateway.events.size)
        send() // this week again: next week's event stays
        assertEquals(2, gateway.events.size)
    }

    @Test
    fun eventsAreKeptPerCalendar() = runTest {
        val work = CalendarInfo(2L, "Work", "work@example.com")
        gateway.calendars += work
        plans.assign(monday, "Dinner", recipe("Soup"), null)
        send()

        choice.choose(work)
        assertEquals(1, send().added)
        // The first calendar's event is left where it was.
        assertEquals(listOf(1L, 2L), gateway.events.values.map { it.calendarId }.sorted())
    }

    @Test
    fun withoutAChoicePermissionOrCalendarNothingIsWritten() = runTest {
        plans.assign(monday, "Dinner", recipe("Soup"), null)
        val unset = CalendarSync(db, gateway, CalendarChoice(prefs("calendar-sync-unset")), Dispatchers.Unconfined) { zone }
        assertEquals(SendOutcome.NotSetUp, unset.sendWeek(monday))

        gateway.permission = false
        assertEquals(SendOutcome.PermissionDenied, sync.sendWeek(monday))

        gateway.permission = true
        choice.choose(CalendarInfo(99L, "Gone", ""))
        assertEquals(SendOutcome.CalendarGone, sync.sendWeek(monday))
        assertEquals(emptyList<String>(), gateway.writes)
    }

    @Test
    fun permissionWithdrawnMidSendEndsTheSend() = runTest {
        plans.assign(monday, "Dinner", recipe("Soup"), null)
        gateway.refuseWritesWithSecurity = true
        assertEquals(SendOutcome.PermissionDenied, sync.sendWeek(monday))
        assertEquals(0, db.calendarEventDao().all().size)
    }

    @Test
    fun aFailedDeleteKeepsItsRecordUntilARetrySucceeds() = runTest {
        plans.assign(monday, "Dinner", recipe("Soup"), null)
        send()
        val id = gateway.events.keys.single()
        plans.unassign(monday, "Dinner")
        gateway.failEventIds += id

        val sent = send()
        assertEquals(0, sent.removed)
        assertEquals(listOf(SendFailure(monday, "Dinner", "The calendar refused event $id")), sent.failures)
        assertEquals(1, db.calendarEventDao().all().size)
        assertEquals(1, gateway.events.size)

        gateway.failEventIds.clear()
        assertEquals(1, send().removed)
        assertEquals(0, gateway.events.size)
        assertEquals(0, db.calendarEventDao().all().size)
    }

    @Test
    fun aFailedUpdateKeepsTheOldHashUntilARetrySucceeds() = runTest {
        val soup = recipe("Soup")
        plans.assign(monday, "Dinner", soup, "4")
        send()
        val id = gateway.events.keys.single()
        val oldHash = db.calendarEventDao().all().single().contentHash
        plans.assign(monday, "Dinner", soup, "8")
        gateway.failEventIds += id

        val sent = send()
        assertEquals(0, sent.updated)
        assertEquals(1, sent.failures.size)
        assertEquals(oldHash, db.calendarEventDao().all().single().contentHash)
        assertTrue(gateway.events.getValue(id).event.description.startsWith("Servings: 4"))

        gateway.failEventIds.clear()
        assertEquals(1, send().updated)
        assertTrue(gateway.events.getValue(id).event.description.startsWith("Servings: 8"))
        assertTrue(oldHash != db.calendarEventDao().all().single().contentHash)
        gateway.writes.clear()
        assertEquals(1, send().unchanged)
        assertEquals(emptyList<String>(), gateway.writes)
    }

    @Test
    fun twoSendsAtOnceInsertEachMealOnce() = runTest {
        val busy = CalendarSync(db, gateway, choice, Dispatchers.IO) { zone }
        plans.assign(monday, "Dinner", recipe("Soup"), null)
        plans.assign(monday.plusDays(1), "Lunch", recipe("Stew"), null)
        val gate = CountDownLatch(1)
        gateway.insertGate = gate

        val both = listOf(async(Dispatchers.IO) { busy.sendWeek(monday) }, async(Dispatchers.IO) { busy.sendWeek(monday) })
        withContext(Dispatchers.IO) { gateway.insertEntered.await() }
        gate.countDown()
        val outcomes = both.awaitAll().map { it as SendOutcome.Sent }

        assertEquals(2, gateway.writes.count { it.startsWith("insert") })
        assertEquals(2, gateway.events.size)
        assertEquals(2, db.calendarEventDao().all().size)
        assertEquals(listOf(0, 2), outcomes.map { it.added }.sorted())
    }

    @Test
    fun permissionLostPartWayKeepsWhatWasWrittenAndWritesNothingMore() = runTest {
        plans.assign(monday, "Dinner", recipe("Soup"), null)
        plans.assign(monday.plusDays(1), "Lunch", recipe("Stew"), null)
        gateway.securityAfterWrites = 1

        assertEquals(SendOutcome.PermissionDenied, sync.sendWeek(monday))
        assertEquals(listOf("Dinner: Soup"), gateway.titles())
        assertEquals(1, gateway.writes.size)
        assertEquals(listOf("2026-09-28 Dinner"), db.calendarEventDao().all().map { "${it.date} ${it.slot}" })
    }

    @Test
    fun leavingMidSendStillRecordsTheEventItWroteAndStopsThere() = runTest {
        val busy = CalendarSync(db, gateway, choice, Dispatchers.IO) { zone }
        plans.assign(monday, "Dinner", recipe("Soup"), null)
        plans.assign(monday.plusDays(1), "Lunch", recipe("Stew"), null)
        val gate = CountDownLatch(1)
        gateway.insertGate = gate

        // Back pressed while the provider is writing Monday's dinner: the job is cancelled,
        // but the provider insert still lands.
        val job = launch(Dispatchers.IO) { busy.sendWeek(monday) }
        withContext(Dispatchers.IO) { gateway.insertEntered.await() }
        gateway.insertGate = null
        job.cancel()
        gate.countDown()
        job.join()

        // The event that landed is recorded, and the send stopped after that slot.
        val id = gateway.events.keys.single()
        assertEquals(listOf("2026-09-28 Dinner $id"), db.calendarEventDao().all().map { "${it.date} ${it.slot} ${it.eventId}" })
        assertEquals(listOf("insert $id"), gateway.writes)

        // The next send adds only the meal it never reached: no duplicate dinner.
        gateway.writes.clear()
        val next = send()
        assertEquals(listOf(1, 1), listOf(next.added, next.unchanged))
        assertEquals(listOf("Dinner: Soup", "Lunch: Stew"), gateway.titles())
    }

    @Test
    fun aStaleIdNamingTheirOwnEventIsNeverUpdatedOrDeleted() = runTest {
        val soup = recipe("Soup")
        plans.assign(monday, "Breakfast", recipe("Oats"), null)
        plans.assign(monday, "Lunch", recipe("Stew"), null)
        plans.assign(monday, "Dinner", soup, "4")
        send()
        val ids = db.calendarEventDao().all().associate { it.slot to it.eventId }

        // Calendar Storage cleared: the provider has handed our recorded ids to the family's own events.
        gateway.theirOwnUnder(ids.getValue("Breakfast"), 1L, "Dentist")
        gateway.theirOwnUnder(ids.getValue("Lunch"), 1L, "Piano")
        gateway.theirOwnUnder(ids.getValue("Dinner"), 1L, "Book club")
        plans.unassign(monday, "Lunch") // removed: a delete is tried
        plans.assign(monday, "Dinner", soup, "8") // changed: an update is tried
        gateway.writes.clear()

        val sent = send()
        assertEquals(listOf(2, 0, 1, 0), listOf(sent.added, sent.updated, sent.removed, sent.unchanged))
        assertEquals(listOf("Book club", "Breakfast: Oats", "Dentist", "Dinner: Soup", "Piano"), gateway.titles())
        assertEquals("", gateway.events.getValue(ids.getValue("Dinner")).event.description)
        assertTrue(gateway.writes.toString(), gateway.writes.none { it.startsWith("update") })
        // Re-inserted under new ids and tracked: the next send writes nothing.
        assertEquals(setOf("Breakfast", "Dinner"), db.calendarEventDao().all().map { it.slot }.toSet())
        assertTrue(db.calendarEventDao().all().none { it.eventId in ids.values })
        gateway.writes.clear()
        send()
        assertEquals(emptyList<String>(), gateway.writes)
    }
}
