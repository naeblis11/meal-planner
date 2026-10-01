package com.naeblis11.mealplanner.calendar

import com.naeblis11.mealplanner.domain.Week

/** Everything the calendar feature says. "Calendar", not "Google calendar": any writable calendar can be chosen. */
object CalendarMessages {
    const val NOT_SET_UP = "Choose a calendar in Settings first."
    const val PERMISSION_DENIED =
        "Meal Planner isn't allowed to use your calendars. Allow Calendar in this app's permissions, then try again."
    const val CALENDAR_GONE = "The calendar you chose isn't on this phone any more. Choose one again in Settings."
    const val SEND_FAILED = "The week could not be sent to your calendar."
    const val CANT_READ_CALENDARS = "The phone's calendars could not be read."
    const val NO_CALENDARS =
        "No calendar on this phone can take new events. Add a Google account in the phone's Settings, then try again."

    /** gcal.PushResult.summary; null when nothing changed and something failed (the failure banner says it). */
    fun summary(sent: SendOutcome.Sent): String? {
        val bits = listOf(sent.added to "added", sent.updated to "updated", sent.removed to "removed")
            .filter { (count, _) -> count > 0 }
            .map { (count, word) -> "$count $word" }
        return when {
            bits.isNotEmpty() -> "Calendar updated: ${bits.joinToString(", ")}."
            sent.failures.isEmpty() -> "Your calendar was already up to date."
            else -> null
        }
    }

    fun failures(failures: List<SendFailure>): String {
        val where = failures.joinToString("; ") { "${it.slot} on ${Week.dayLabel(it.date)}" }
        return if (failures.size == 1) {
            "1 meal could not be sent: $where. Send the week again to try it again."
        } else {
            "${failures.size} meals could not be sent: $where. Send the week again to try them again."
        }
    }
}
