package com.naeblis11.mealplanner.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CalendarEventDao {
    @Query("SELECT * FROM calendar_event WHERE calendar_id = :calendarId AND date BETWEEN :first AND :last")
    suspend fun inRange(calendarId: Long, first: String, last: String): List<CalendarEventEntity>

    /** Records (or re-records) the event for one calendar, day and slot; REPLACE drops the old row, like gcal._record's upsert. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun record(row: CalendarEventEntity)

    @Query("DELETE FROM calendar_event WHERE calendar_id = :calendarId AND date = :date AND slot = :slot")
    suspend fun forget(calendarId: Long, date: String, slot: String)

    @Query("SELECT * FROM calendar_event ORDER BY calendar_id, date, slot")
    suspend fun all(): List<CalendarEventEntity>
}
