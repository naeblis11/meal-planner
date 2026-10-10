package com.naeblis11.mealplanner.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An event "Send this week" put on a calendar for one planned meal (the Pi's gcal_event).
 * Only events recorded here are ever updated or deleted; [contentHash] is MealEvents.hash
 * of what was written, so an unchanged meal is not written again.
 */
@Entity(tableName = "calendar_event", indices = [Index(value = ["calendar_id", "date", "slot"], unique = true)])
data class CalendarEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "calendar_id") val calendarId: Long,
    val date: String,
    val slot: String,
    @ColumnInfo(name = "event_id") val eventId: Long,
    @ColumnInfo(name = "content_hash") val contentHash: String,
)
