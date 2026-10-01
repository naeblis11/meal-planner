package com.naeblis11.mealplanner.data

import androidx.room.withTransaction
import com.naeblis11.mealplanner.domain.Week
import java.time.LocalDate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** The meal plan: meal_calendar.py. Each write is one statement or one transaction, so no write lock is needed. */
class MealPlanRepository(
    private val db: AppDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val dao = db.mealPlanDao()

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
        withContext(dispatcher) {
            db.withTransaction {
                require(dao.recipeExists(recipeId)) { "That recipe no longer exists." }
                dao.put(MealPlanEntity(date = date.toString(), slot = slot, recipeId = recipeId, servings = servings?.trim()?.ifEmpty { null }))
            }
        }
    }

    suspend fun unassign(date: LocalDate, slot: String): Unit = withContext(dispatcher) { dao.remove(date.toString(), slot) }
}
