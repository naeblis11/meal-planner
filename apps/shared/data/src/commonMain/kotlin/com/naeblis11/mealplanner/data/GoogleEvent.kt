package com.naeblis11.mealplanner.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * An event "Send this week" put on a Google calendar for one planned meal (the desktop's gcal_event, P5-R2). Only
 * events recorded here, with this household's id for their day and slot, are ever updated or deleted; [contentHash] is
 * GoogleEvents.hash of what was written, so an unchanged meal is not written again. Android has the table and leaves
 * it empty in phase 1.
 */
@Entity(tableName = "google_event", primaryKeys = ["calendar_id", "date", "slot"])
data class GoogleEventEntity(
    @ColumnInfo(name = "calendar_id") val calendarId: String,
    val date: String,
    val slot: String,
    @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "content_hash") val contentHash: String,
)

@Dao
interface GoogleEventDao {
    @Query("SELECT * FROM google_event WHERE calendar_id = :calendarId AND date BETWEEN :first AND :last")
    suspend fun inRange(calendarId: String, first: String, last: String): List<GoogleEventEntity>

    /** Records (or re-records) the event for one calendar, day and slot, like gcal._record's upsert. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun record(row: GoogleEventEntity)

    @Query("DELETE FROM google_event WHERE calendar_id = :calendarId AND date = :date AND slot = :slot")
    suspend fun forget(calendarId: String, date: String, slot: String)

    @Query("SELECT * FROM google_event ORDER BY calendar_id, date, slot")
    suspend fun all(): List<GoogleEventEntity>
}
