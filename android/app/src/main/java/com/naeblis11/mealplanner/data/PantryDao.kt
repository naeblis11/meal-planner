package com.naeblis11.mealplanner.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** pantry.py's statements. `name` is NOCASE, so `name = :name` ignores case as on the Pi. */
@Dao
interface PantryDao {
    @Query("SELECT * FROM pantry_item ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<PantryItemEntity>>

    /** -1 when the name is already there (INSERT OR IGNORE). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(item: PantryItemEntity): Long

    @Query("SELECT * FROM pantry_item WHERE name = :name")
    suspend fun byName(name: String): PantryItemEntity?

    @Query("SELECT * FROM pantry_item WHERE id = :id")
    suspend fun item(id: Long): PantryItemEntity?

    /** Back on hand, restamped: it is going into the pantry afresh. Only when it was out. */
    @Query("UPDATE pantry_item SET active = 1, added_on = :today WHERE id = :id AND active = 0")
    suspend fun putBack(id: Long, today: String)

    @Query("UPDATE pantry_item SET active = 0 WHERE id = :id")
    suspend fun markOut(id: Long)

    @Query("UPDATE pantry_item SET added_on = :addedOn WHERE id = :id")
    suspend fun setAddedOn(id: Long, addedOn: String?)

    @Query("UPDATE pantry_item SET exact_match = 1 - exact_match WHERE id = :id")
    suspend fun toggleExactMatch(id: Long)

    @Query("UPDATE pantry_item SET aisle = :aisle WHERE id = :id")
    suspend fun setAisle(id: Long, aisle: String?)

    @Query("DELETE FROM pantry_item WHERE id = :id")
    suspend fun delete(id: Long)
}
