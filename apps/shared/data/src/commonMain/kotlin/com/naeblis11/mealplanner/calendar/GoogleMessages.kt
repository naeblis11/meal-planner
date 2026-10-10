package com.naeblis11.mealplanner.calendar

/** What the Google send says when it can't do its job (P5-R5); the counts come from CalendarMessages.summary. */
object GoogleMessages {
    const val SIGN_IN = "Sign in to Google in Settings first."
    const val SIGN_IN_AGAIN = "Sign in to Google again in Settings."
    const val CALENDAR_GONE = "The Google calendar you chose can't be found any more. Choose one again in Settings."
    const val UNREACHABLE = "Couldn't reach Google, so the week wasn't sent. Check this PC's internet connection, then send the week again."
    const val ID_TAKEN = "Google says this meal's event is there, but won't show it."
}
