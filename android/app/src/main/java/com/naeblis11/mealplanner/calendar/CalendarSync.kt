package com.naeblis11.mealplanner.calendar

import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.CalendarEventEntity
import com.naeblis11.mealplanner.data.PlannedMealRow
import com.naeblis11.mealplanner.domain.Week
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What one "Send this week" did. */
sealed interface SendOutcome {
    data object NotSetUp : SendOutcome

    data object PermissionDenied : SendOutcome

    data object CalendarGone : SendOutcome

    data class Sent(
        val added: Int,
        val updated: Int,
        val removed: Int,
        val unchanged: Int,
        val failures: List<SendFailure>,
    ) : SendOutcome
}

/** A meal whose event could not be written this time; its previous record is kept, so the next send retries it. */
data class SendFailure(val date: LocalDate, val slot: String, val reason: String)

/**
 * "Send this week": makes the chosen calendar match one week of the meal plan, like the
 * Pi's gcal.push_week. One way only, and only ever touching events recorded in
 * calendar_event: new meals are inserted, changed ones updated, removed ones deleted,
 * and a second send with nothing changed writes nothing. Each day and slot is written
 * on its own and recorded straight after, so one failure neither stops the rest nor
 * loses track of what was written.
 */
class CalendarSync(
    private val db: AppDatabase,
    private val gateway: CalendarGateway,
    private val choice: CalendarChoice,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val events = db.calendarEventDao()

    // Two sends must not interleave their read-modify-write of calendar_event. Not re-entrant.
    private val writeLock = Mutex()

    private enum class Change { ADDED, UPDATED, REMOVED, UNCHANGED, NONE }

    suspend fun sendWeek(start: LocalDate): SendOutcome = writeLock.withLock {
        withContext(dispatcher) {
            val calendarId = choice.chosen.value?.id ?: return@withContext SendOutcome.NotSetUp
            if (!gateway.hasPermission()) return@withContext SendOutcome.PermissionDenied
            try {
                if (gateway.calendar(calendarId) == null) SendOutcome.CalendarGone else sendLocked(calendarId, start)
            } catch (e: SecurityException) {
                // Permission withdrawn mid-send: whatever was written before is already recorded.
                SendOutcome.PermissionDenied
            }
        }
    }

    private suspend fun sendLocked(calendarId: Long, start: LocalDate): SendOutcome.Sent {
        val first = start.toString()
        val last = start.plusDays(6).toString()
        val planned = db.mealPlanDao().range(first, last).associateBy { it.date to it.slot }
        val recorded = events.inRange(calendarId, first, last).associateBy { it.date to it.slot }
        val zone = zone()
        val counts = mutableMapOf<Change, Int>()
        val failures = mutableListOf<SendFailure>()
        // Every day and slot of the week, empty ones too: a meal removed since the last
        // send shows up as an empty slot with a recorded event, and is deleted. Rows for
        // other weeks are not read, so they are left alone.
        for (date in Week.dates(start)) {
            for (slot in Week.SLOTS) {
                // A cancelled send (Back pressed) stops here, between slots; a slot already
                // started finishes its write and its bookkeeping first (see bookkept).
                currentCoroutineContext().ensureActive()
                val key = date.toString() to slot
                try {
                    val change = sendSlot(calendarId, date, slot, planned[key], recorded[key], zone)
                    counts[change] = (counts[change] ?: 0) + 1
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SecurityException) {
                    throw e
                } catch (e: Exception) {
                    failures += SendFailure(date, slot, e.message ?: e.javaClass.simpleName)
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
        calendarId: Long,
        date: LocalDate,
        slot: String,
        meal: PlannedMealRow?,
        known: CalendarEventEntity?,
        zone: ZoneId,
    ): Change {
        if (meal == null) {
            if (known == null) return Change.NONE
            // False means it was already deleted by hand: nothing to do but stop tracking it.
            return bookkept {
                gateway.deleteEvent(calendarId, known.eventId)
                events.forget(calendarId, known.date, known.slot)
                Change.REMOVED
            }
        }
        val ingredients = db.recipeDao().ingredients(meal.recipeId).map { EventIngredient(it.name, it.amount, it.unit, it.section) }
        val event = MealEvents.build(date, slot, meal.recipeName, meal.servings, ingredients, zone)
        val hash = MealEvents.hash(event)
        return when {
            known == null -> insert(calendarId, date, slot, event, hash)
            // Unchanged: a read tells whether it was deleted by hand, and then only it is put back.
            known.contentHash == hash -> if (gateway.eventExists(calendarId, known.eventId)) Change.UNCHANGED else insert(calendarId, date, slot, event, hash)
            else -> update(calendarId, date, slot, known, event, hash)
        }
    }

    private suspend fun update(
        calendarId: Long,
        date: LocalDate,
        slot: String,
        known: CalendarEventEntity,
        event: MealEvent,
        hash: String,
    ): Change {
        val updated = bookkept {
            gateway.updateEvent(calendarId, known.eventId, event).also { if (it) events.record(known.copy(id = 0, contentHash = hash)) }
        }
        // Deleted by hand and changed since: a new event, not a resurrection.
        return if (updated) Change.UPDATED else insert(calendarId, date, slot, event, hash)
    }

    private suspend fun insert(calendarId: Long, date: LocalDate, slot: String, event: MealEvent, hash: String): Change = bookkept {
        val eventId = gateway.insertEvent(calendarId, event)
        events.record(CalendarEventEntity(calendarId = calendarId, date = date.toString(), slot = slot, eventId = eventId, contentHash = hash))
        Change.ADDED
    }

    /**
     * One provider write and its calendar_event bookkeeping, finished together even if the send
     * is cancelled part-way (Back pressed on the Calendar screen): a write the provider took but
     * calendar_event never heard of would be inserted again, as a duplicate, by the next send.
     */
    private suspend fun <T> bookkept(write: suspend () -> T): T = withContext(NonCancellable) { write() }
}
