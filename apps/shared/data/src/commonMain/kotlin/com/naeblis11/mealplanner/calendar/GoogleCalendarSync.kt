package com.naeblis11.mealplanner.calendar

import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.GoogleEventEntity
import com.naeblis11.mealplanner.data.Household
import com.naeblis11.mealplanner.data.PlannedMealRow
import com.naeblis11.mealplanner.domain.GoogleEventIds
import com.naeblis11.mealplanner.domain.Week
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * "Send this week" to a Google calendar (P5-R1, P5-R3): gcal.push_week's rules, over [GoogleCalendarApi].
 *
 * One way only, and only ever touching the app's own events: the one recorded in google_event for a slot (under
 * whatever id it was recorded with: it is updated in place), else this household's GoogleEventIds.forMeal id. Every
 * write is recorded in google_event straight after. New meals are inserted under their id; a 409 means the event is
 * already there (a lost record, or another device), so it is updated instead. Changed meals are updated, removed ones deleted, and an unchanged meal (the same GoogleEvents.hash)
 * is not written at all, so a second send with nothing changed writes nothing. Other weeks are never read.
 *
 * [api] is null while nobody is signed in; [calendarId] null while no calendar is chosen. A meal Google refuses is a
 * SendFailure and the rest go on; a refused sign-in, a calendar that is gone, or Google out of reach stops the send.
 */
class GoogleCalendarSync(
    private val db: AppDatabase,
    private val api: () -> GoogleCalendarApi?,
    private val calendarId: () -> String?,
    private val household: Household = Household(db),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val events = db.googleEventDao()

    // Two sends must not interleave their read-modify-write of google_event. Not re-entrant.
    private val writeLock = Mutex()

    private enum class Change { ADDED, UPDATED, REMOVED, UNCHANGED, NONE }

    // Google says the chosen calendar isn't there (an insert answered 404): only choosing again helps.
    private class CalendarGone : Exception()

    suspend fun sendWeek(start: LocalDate): SendOutcome = writeLock.withLock {
        withContext(dispatcher) {
            try {
                val google = api() ?: return@withContext SendOutcome.NeedsSettings(GoogleMessages.SIGN_IN)
                val calendar = calendarId() ?: return@withContext SendOutcome.NotSetUp
                sendLocked(google, calendar, start)
            } catch (e: GoogleAuthException) {
                SendOutcome.NeedsSettings(GoogleMessages.SIGN_IN_AGAIN)
            } catch (e: CalendarGone) {
                SendOutcome.NeedsSettings(GoogleMessages.CALENDAR_GONE)
            } catch (e: IOException) {
                SendOutcome.Failed(GoogleMessages.UNREACHABLE)
            }
        }
    }

    private suspend fun sendLocked(google: GoogleCalendarApi, calendar: String, start: LocalDate): SendOutcome.Sent {
        val householdId = household.id()
        val first = start.toString()
        val last = start.plusDays(6).toString()
        val planned = db.mealPlanDao().range(first, last).associateBy { it.date to it.slot }
        val recorded = events.inRange(calendar, first, last).associateBy { it.date to it.slot }
        val zone = zone()
        val counts = mutableMapOf<Change, Int>()
        val failures = mutableListOf<SendFailure>()
        // Every day and slot of the week, empty ones too: a meal removed since the last send shows up as an empty slot
        // with a recorded event, and is deleted. Rows for other weeks are not read, so they are left alone.
        for (date in Week.dates(start)) {
            for (slot in Week.SLOTS) {
                currentCoroutineContext().ensureActive()
                val key = date.toString() to slot
                try {
                    val change = sendSlot(google, calendar, householdId, date, slot, planned[key], recorded[key], zone)
                    counts[change] = (counts[change] ?: 0) + 1
                } catch (e: GoogleApiException) {
                    // This meal only; its old record (if any) is kept, so the next send tries it again.
                    failures += SendFailure(date, slot, e.message ?: "Google answered ${e.status}")
                }
            }
        }
        return SendOutcome.Sent(
            added = counts[Change.ADDED] ?: 0,
            updated = counts[Change.UPDATED] ?: 0,
            removed = counts[Change.REMOVED] ?: 0,
            unchanged = counts[Change.UNCHANGED] ?: 0,
            failures = failures,
        )
    }

    private suspend fun sendSlot(
        google: GoogleCalendarApi,
        calendar: String,
        householdId: String,
        date: LocalDate,
        slot: String,
        meal: PlannedMealRow?,
        known: GoogleEventEntity?,
        zone: ZoneId,
    ): Change {
        // The slot's event: the one recorded, under its recorded id, else this household's id for the day and slot.
        val fresh = GoogleEventIds.forMeal(householdId, date, slot)
        val id = known?.eventId ?: fresh
        if (meal == null) {
            if (known == null) return Change.NONE
            // False means it was already gone (deleted by hand): nothing to do but stop tracking it.
            return bookkept {
                google.deleteEvent(calendar, id)
                events.forget(calendar, known.date, known.slot)
                Change.REMOVED
            }
        }
        val ingredients = db.recipeDao().ingredients(meal.recipeId).map { EventIngredient(it.name, it.amount, it.unit, it.section) }
        val event = GoogleEvents.build(date, slot, meal.recipeName, meal.servings, ingredients, zone)
        val hash = GoogleEvents.hash(event)
        if (known != null && known.contentHash == hash) return Change.UNCHANGED
        return bookkept {
            val (change, written) = if (known != null) update(google, calendar, id, fresh, event) else put(google, calendar, fresh, event) to fresh
            events.record(GoogleEventEntity(calendar, date.toString(), slot, written, hash))
            change
        }
    }

    // gcal.push_week: an update of an event gone for good puts the meal back as a new event, under this household's
    // [fresh] id. Returns what changed and the id now recorded.
    private fun update(google: GoogleCalendarApi, calendar: String, id: String, fresh: String, event: GoogleEvent): Pair<Change, String> =
        try {
            google.updateEvent(calendar, id, event)
            Change.UPDATED to id
        } catch (e: GoogleApiException) {
            if (e.status != 404 && e.status != 410) throw e
            put(google, calendar, fresh, event) to fresh
        }

    // An insert under the meal's own id. A 409 means the id is taken: by this household's event from a send whose
    // record was lost (or another device's), or by one deleted by hand, which Google keeps cancelled. Either way it
    // is ours, and an update (confirmed) makes it the meal again.
    private fun put(google: GoogleCalendarApi, calendar: String, id: String, event: GoogleEvent): Change {
        try {
            google.insertEvent(calendar, id, event)
            return Change.ADDED
        } catch (e: GoogleApiException) {
            if (e.status == 404) throw CalendarGone()
            if (e.status != 409) throw e
        }
        val status = google.eventStatus(calendar, id) ?: throw GoogleApiException(409, GoogleMessages.ID_TAKEN)
        google.updateEvent(calendar, id, event)
        return if (status == "cancelled") Change.ADDED else Change.UPDATED
    }

    /**
     * One Google write and its google_event bookkeeping, finished together even if the send is cancelled part-way: a
     * write Google took but google_event never heard of would only be found again through a 409.
     */
    private suspend fun <T> bookkept(write: suspend () -> T): T = withContext(NonCancellable) { write() }
}
