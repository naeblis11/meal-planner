package com.naeblis11.mealplanner.settings

import kotlinx.coroutines.flow.StateFlow

/**
 * Another Meal Planner PC on the home network (plan 6), as the window's banner and Settings show it. [text] is the
 * one-line notice. [alexa] is set only while this PC leaves Google Calendar and Alexa to an older household's PC: what
 * Settings' Alexa section says instead, with Create a token and Make a new token switched off.
 */
data class PeerNotice(val text: String, val alexa: String? = null)

/** The desktop's look-out for other Meal Planner PCs (PeerWatch); null on Android, which has none in phase 1. */
interface PeerControls {
    /** The notice, or null while no other Meal Planner PC has been seen. */
    val notice: StateFlow<PeerNotice?>

    /** Settings opened: look again (about 5 s, in the background). Ignored while a look is under way. */
    fun browseNow()
}
