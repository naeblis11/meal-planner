package com.naeblis11.mealplanner.calendar

import com.naeblis11.mealplanner.calendar.FakeGoogleCalendarApi.Companion.FAMILY
import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.GoogleEventEntity
import com.naeblis11.mealplanner.data.Household
import com.naeblis11.mealplanner.data.MealPlanRepository
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.data.openAt
import com.naeblis11.mealplanner.domain.GoogleEventIds
import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.YamlMap
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Send this week" to Google: idempotent, one way, and never touching an event that isn't this household's. */
class GoogleCalendarSyncTest {
    private val dir: File = Files.createTempDirectory("mp-gsync").toFile()
    private val db = AppDatabase.openAt(File(dir, "test.db"))
    private val recipes = RecipeRepository(db, File(dir, "images").apply { mkdirs() })
    private val plans = MealPlanRepository(db)
    private val google = FakeGoogleCalendarApi()
    private var signedIn = true
    private var calendar: String? = FAMILY
    private val zone = ZoneId.of("America/Toronto")
    private val monday = LocalDate.of(2026, 9, 28)
    private val household = Household(db) { ByteArray(16) { 1 } }
    private val sync = GoogleCalendarSync(db, { if (signedIn) google else null }, { calendar }, household, zone = { zone })

    @After
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun recipe(name: String): Long = recipes.save(
        RecipeYaml.load("recipe_name: $name\ningredients:\n- Rice:\n    amounts:\n    - amount: 1\n      unit: cup\nsteps:\n- step: Cook.\n") as YamlMap,
    )

    private suspend fun send(start: LocalDate = monday) = sync.sendWeek(start) as SendOutcome.Sent

    private suspend fun idFor(date: LocalDate, slot: String) = GoogleEventIds.forMeal(household.id(), date, slot)

    @Test
    fun aPlannedMealBecomesOneEventNamedForItsDayAndSlot() = runBlocking {
        plans.assign(monday, "Dinner", recipe("Soup"), "6")

        assertEquals(1, send().added)

        val id = idFor(monday, "Dinner")
        val event = google.live().getValue(id)
        assertEquals("Dinner: Soup", event.summary)
        assertEquals("2026-09-28T18:00:00-04:00", event.start)
        assertEquals("2026-09-28T19:00:00-04:00", event.end)
        assertEquals("Servings: 6\n\nIngredients:\n  - 1 cup Rice\n\nAdded by Meal Planner.", event.description)
        assertEquals(listOf(id), db.googleEventDao().all().map { it.eventId })
    }

    @Test
    fun anEmptyWeekWritesNothing() = runBlocking {
        assertEquals(SendOutcome.Sent(0, 0, 0, 0, emptyList()), sync.sendWeek(monday))
        assertEquals(emptyList<String>(), google.writes)
    }

    @Test
    fun aSecondSendWithNothingChangedWritesNothing() = runBlocking {
        plans.assign(monday, "Dinner", recipe("Soup"), null)
        plans.assign(monday.plusDays(2), "Lunch", recipe("Salad"), null)
        send()
        val writes = google.writes.toList()

        val again = send()
        assertEquals(2, again.unchanged)
        assertEquals(0, again.added + again.updated + again.removed)
        assertEquals(writes, google.writes)
    }

    @Test
    fun aChangedMealUpdatesTheSameEvent() = runBlocking {
        plans.assign(monday, "Dinner", recipe("Soup"), null)
        send()
        plans.assign(monday, "Dinner", recipe("Stew"), null)

        assertEquals(1, send().updated)
        val id = idFor(monday, "Dinner")
        assertEquals("update $id", google.writes.last())
        assertEquals(listOf("Dinner: Stew"), google.live().values.map { it.summary })
    }

    @Test
    fun aRemovedMealTakesItsEventWithIt() = runBlocking {
        plans.assign(monday, "Dinner", recipe("Soup"), null)
        send()
        plans.unassign(monday, "Dinner")

        assertEquals(1, send().removed)
        assertEquals("cancelled", google.status(idFor(monday, "Dinner")))
        assertEquals(emptyList<GoogleEventEntity>(), db.googleEventDao().all())
    }

    @Test
    fun aRemovalSurvivesTheEventAlreadyBeingGone() = runBlocking {
        plans.assign(monday, "Dinner", recipe("Soup"), null)
        send()
        google.deleteByHand(idFor(monday, "Dinner"))
        plans.unassign(monday, "Dinner")

        assertEquals(1, send().removed)
        assertTrue(google.writes.none { it.startsWith("delete") })
        assertEquals(emptyList<GoogleEventEntity>(), db.googleEventDao().all())
    }

    @Test
    fun anEventDeletedByHandComesBackWhenItsMealChanges() = runBlocking {
        val soup = recipe("Soup")
        plans.assign(monday, "Dinner", soup, "6")
        send()
        val id = idFor(monday, "Dinner")
        google.deleteByHand(id)
        plans.assign(monday, "Dinner", soup, "8")

        assertEquals(1, send().updated)
        assertEquals("confirmed", google.status(id))
        assertTrue(google.live().getValue(id).description.startsWith("Servings: 8"))
    }

    @Test
    fun aLostRecordFindsItsEventByIdInsteadOfAddingASecond() = runBlocking {
        plans.assign(monday, "Dinner", recipe("Soup"), null)
        send()
        val id = idFor(monday, "Dinner")
        // As if another device sent this week, or the record was lost: the insert answers 409 and the event is updated.
        db.googleEventDao().forget(FAMILY, monday.toString(), "Dinner")

        assertEquals(1, send().updated)
        assertEquals(1, google.events.size)
        assertEquals("update $id", google.writes.last())
        assertEquals(listOf(id), db.googleEventDao().all().map { it.eventId })
    }

    @Test
    fun aCancelledEventWithNoRecordComesBackAsAdded() = runBlocking {
        plans.assign(monday, "Dinner", recipe("Soup"), null)
        send()
        val id = idFor(monday, "Dinner")
        google.deleteByHand(id)
        db.googleEventDao().forget(FAMILY, monday.toString(), "Dinner")

        assertEquals(1, send().added)
        assertEquals("confirmed", google.status(id))
    }

    @Test
    fun eventsNeitherRecordedNorOursAreNeverTouched() = runBlocking {
        google.events[FAMILY to "dentist123"] = FakeGoogleCalendarApi.Stored(GoogleEvent("Dentist", "", "x", "y"), "confirmed")
        plans.assign(monday, "Dinner", recipe("Soup"), null)
        send()
        plans.unassign(monday, "Dinner")
        send()

        assertTrue(google.writes.none { "dentist123" in it })
        assertEquals("confirmed", google.status("dentist123"))
        assertEquals(emptyList<GoogleEventEntity>(), db.googleEventDao().all())
    }

    @Test
    fun aSlotRecordedUnderAnotherIdIsUpdatedInPlaceNotInsertedAgain() = runBlocking {
        // An event recorded under an id that isn't this household's (Google chose it), with a hash of another format.
        google.events[FAMILY to "recorded1"] = FakeGoogleCalendarApi.Stored(GoogleEvent("Dinner: Soup", "", "x", "y"), "confirmed")
        db.googleEventDao().record(GoogleEventEntity(FAMILY, monday.toString(), "Dinner", "recorded1", "other-hash"))
        plans.assign(monday, "Dinner", recipe("Soup"), null)

        assertEquals(1, send().updated)
        assertEquals(listOf("update recorded1"), google.writes)
        assertEquals(1, google.events.size)
        assertEquals(listOf("recorded1"), db.googleEventDao().all().map { it.eventId })
        // And from then on it is unchanged.
        assertEquals(1, send().unchanged)
        assertEquals(listOf("update recorded1"), google.writes)
    }

    @Test
    fun aRemovedMealRecordedUnderAnotherIdDeletesThatEvent() = runBlocking {
        google.events[FAMILY to "recorded1"] = FakeGoogleCalendarApi.Stored(GoogleEvent("Dinner: Soup", "", "x", "y"), "confirmed")
        db.googleEventDao().record(GoogleEventEntity(FAMILY, monday.toString(), "Dinner", "recorded1", "other-hash"))

        assertEquals(1, send().removed)
        assertEquals(listOf("delete recorded1"), google.writes)
        assertEquals("cancelled", google.status("recorded1"))
        assertEquals(emptyList<GoogleEventEntity>(), db.googleEventDao().all())
    }

    @Test
    fun otherWeeksAreLeftAlone() = runBlocking {
        plans.assign(monday, "Dinner", recipe("Soup"), null)
        send()
        plans.unassign(monday, "Dinner")

        send(monday.plusWeeks(1))
        assertEquals("confirmed", google.status(idFor(monday, "Dinner")))
        assertEquals(1, db.googleEventDao().all().size)
    }

    @Test
    fun aMealGoogleRefusesIsReportedAndTheRestAreSent() = runBlocking {
        plans.assign(monday, "Dinner", recipe("Soup"), null)
        plans.assign(monday, "Lunch", recipe("Salad"), null)
        google.failing += idFor(monday, "Lunch")

        val first = send()
        assertEquals(1, first.added)
        assertEquals(listOf(SendFailure(monday, "Lunch", "Google answered 500: Backend Error")), first.failures)
        assertEquals(listOf("Dinner"), db.googleEventDao().all().map { it.slot })

        google.failing.clear()
        assertEquals(1, send().added)
    }

    @Test
    fun withoutASignInOrACalendarItSaysWhatToDo() = runBlocking {
        signedIn = false
        assertEquals(SendOutcome.NeedsSettings(GoogleMessages.SIGN_IN), sync.sendWeek(monday))
        signedIn = true
        calendar = null
        assertEquals(SendOutcome.NotSetUp, sync.sendWeek(monday))
    }

    @Test
    fun accessGoogleNoLongerHonoursAsksToSignInAgain() = runBlocking {
        plans.assign(monday, "Dinner", recipe("Soup"), null)
        google.signedOut = true
        assertEquals(SendOutcome.NeedsSettings(GoogleMessages.SIGN_IN_AGAIN), sync.sendWeek(monday))
    }

    @Test
    fun aCalendarThatIsGoneAsksToChooseAgain() = runBlocking {
        plans.assign(monday, "Dinner", recipe("Soup"), null)
        calendar = "gone@group.calendar.google.com"
        assertEquals(SendOutcome.NeedsSettings(GoogleMessages.CALENDAR_GONE), sync.sendWeek(monday))
        assertEquals(emptyList<GoogleEventEntity>(), db.googleEventDao().all())
    }

    @Test
    fun anOwnEventGoneForGoodIsPutBackUnderItsIdAndCountedAsAdded() = runBlocking {
        val soup = recipe("Soup")
        plans.assign(monday, "Dinner", soup, "6")
        send()
        val id = idFor(monday, "Dinner")
        // Google no longer has it at all (not even cancelled): the update answers 404.
        google.events.remove(FAMILY to id)
        plans.assign(monday, "Dinner", soup, "8")

        val again = send()
        assertEquals(1, again.added)
        assertEquals(0, again.updated)
        assertEquals(listOf("insert $id"), google.writes.drop(1))
        assertTrue(google.live().getValue(id).description.startsWith("Servings: 8"))
        assertEquals(listOf(id), db.googleEventDao().all().map { it.eventId })
    }

    @Test
    fun aRecordedEventGoneForGoodIsPutBackUnderThisHouseholdsIdAndCountedAsAdded() = runBlocking {
        // An event recorded under another id, that Google answers 410 for.
        db.googleEventDao().record(GoogleEventEntity(FAMILY, monday.toString(), "Dinner", "recorded1", "other-hash"))
        google.goneForGood += "recorded1"
        plans.assign(monday, "Dinner", recipe("Soup"), null)

        val sent = send()
        assertEquals(1, sent.added)
        assertEquals(0, sent.updated)
        val id = idFor(monday, "Dinner")
        assertEquals(listOf("insert $id"), google.writes)
        assertEquals(listOf(id), db.googleEventDao().all().map { it.eventId })
        // And from then on it is unchanged, under the new id.
        assertEquals(1, send().unchanged)
    }

    @Test
    fun aCalendarGoneWhenAMealChangesAsksToChooseAgainAndKeepsTheRecord() = runBlocking {
        plans.assign(monday, "Dinner", recipe("Soup"), null)
        send()
        val before = db.googleEventDao().all()
        google.calendars.removeAll { it.id == FAMILY }
        plans.assign(monday, "Dinner", recipe("Stew"), null)

        // The update answers 404, the insert that follows does too: the calendar itself is gone.
        assertEquals(SendOutcome.NeedsSettings(GoogleMessages.CALENDAR_GONE), sync.sendWeek(monday))
        assertEquals(before, db.googleEventDao().all())
    }

    @Test
    fun aRemovalFromACalendarThatIsGoneIsForgottenAsGoogleSaysTheEventIsGone() = runBlocking {
        plans.assign(monday, "Dinner", recipe("Soup"), null)
        send()
        google.calendars.removeAll { it.id == FAMILY }
        plans.unassign(monday, "Dinner")

        // deleteEvent's contract: a 404 (here, for the calendar) is "already gone", not an error.
        val sent = send()
        assertEquals(1, sent.removed)
        assertEquals(emptyList<SendFailure>(), sent.failures)
        assertEquals(emptyList<GoogleEventEntity>(), db.googleEventDao().all())
    }

    @Test
    fun googleOutOfReachStopsTheSend() = runBlocking {
        plans.assign(monday, "Dinner", recipe("Soup"), null)
        google.offline = true
        assertEquals(SendOutcome.Failed(GoogleMessages.UNREACHABLE), sync.sendWeek(monday))
    }
}
