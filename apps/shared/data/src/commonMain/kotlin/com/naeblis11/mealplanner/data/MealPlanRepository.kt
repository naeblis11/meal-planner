package com.naeblis11.mealplanner.data

import com.naeblis11.mealplanner.domain.Week
import java.time.LocalDate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The meal plan: meal_calendar.py. Writes take a fair (FIFO) write lock, as the shopping list's and the pantry's do:
 * assigns and removals made in tap order are applied in that order, and the desktop waits on it ([awaitWrites]) before
 * closing the database, so an assign at the moment of Quit is saved (P6-R9). On Android the lock changes nothing.
 */
class MealPlanRepository(
    private val db: AppDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val dao = db.mealPlanDao()
    private val writeLock = Mutex()

    /** The week starting [start] (a Monday), re-emitted after every change. */
    fun observeWeek(start: LocalDate): Flow<List<PlannedMealRow>> =
        dao.observeRange(start.toString(), start.plusDays(6).toString())

    suspend fun assignment(date: LocalDate, slot: String): PlannedMealRow? =
        withContext(dispatcher) { dao.assignment(date.toString(), slot) }

    /**
     * Puts [recipeId] in [slot] on [date], replacing what was there. [servings] is
     * stored as given (blank means the recipe's own yield); callers validate it with
     * ServingsInput first. Throws IllegalArgumentException with a message for the user.
     */
    suspend fun assign(date: LocalDate, slot: String, recipeId: Long, servings: String?) {
        require(slot in Week.SLOTS) { "Invalid slot: '$slot'. Must be one of ${Week.SLOTS.joinToString(", ")}." }
        writeLock.withLock {
            withContext(dispatcher) {
                db.inTransaction {
                    require(dao.recipeExists(recipeId)) { "That recipe no longer exists." }
                    dao.put(MealPlanEntity(date = date.toString(), slot = slot, recipeId = recipeId, servings = servings?.trim()?.ifEmpty { null }))
                }
            }
        }
    }

    suspend fun unassign(date: LocalDate, slot: String): Unit = writeLock.withLock { withContext(dispatcher) { dao.remove(date.toString(), slot) } }

    /** Returns once no write that had already started is running: the desktop waits on it before closing the database. */
    suspend fun awaitWrites() {
        writeLock.withLock {}
    }
}
