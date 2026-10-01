package com.naeblis11.mealplanner.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MealPlanDao {
    @Query(
        """SELECT mp.date, mp.slot, mp.recipe_id, mp.servings, r.name AS recipe_name, r.image_filename
           FROM meal_plan mp JOIN recipe r ON r.id = mp.recipe_id
           WHERE mp.date BETWEEN :first AND :last
           ORDER BY mp.date, mp.slot""",
    )
    fun observeRange(first: String, last: String): Flow<List<PlannedMealRow>>

    /** The same rows as [observeRange], read once (CalendarSync reads a week inside one send). */
    @Query(
        """SELECT mp.date, mp.slot, mp.recipe_id, mp.servings, r.name AS recipe_name, r.image_filename
           FROM meal_plan mp JOIN recipe r ON r.id = mp.recipe_id
           WHERE mp.date BETWEEN :first AND :last
           ORDER BY mp.date, mp.slot""",
    )
    suspend fun range(first: String, last: String): List<PlannedMealRow>

    @Query(
        """SELECT mp.date, mp.slot, mp.recipe_id, mp.servings, r.name AS recipe_name, r.image_filename
           FROM meal_plan mp JOIN recipe r ON r.id = mp.recipe_id
           WHERE mp.date = :date AND mp.slot = :slot""",
    )
    suspend fun assignment(date: String, slot: String): PlannedMealRow?

    /** Puts a meal in its slot; REPLACE drops whatever the unique (date, slot) index already held, like the Pi's upsert. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(meal: MealPlanEntity): Long

    @Query("DELETE FROM meal_plan WHERE date = :date AND slot = :slot")
    suspend fun remove(date: String, slot: String)

    @Query("SELECT EXISTS(SELECT 1 FROM recipe WHERE id = :recipeId)")
    suspend fun recipeExists(recipeId: Long): Boolean
}
