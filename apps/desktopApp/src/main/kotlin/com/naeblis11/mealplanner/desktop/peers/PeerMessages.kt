package com.naeblis11.mealplanner.desktop.peers

/** What the window, Settings and the Calendar say about another Meal Planner PC on the network (P6-R8). */
object PeerMessages {
    /** This PC yields: the window's banner and the top of Settings. */
    fun yielding(name: String): String =
        "Another PC on this network ($name) runs Meal Planner. Until they can sync, only that PC sends to Google Calendar and answers Alexa."

    /** This PC is the older household: it keeps both. */
    fun keeping(name: String): String =
        "Another PC on this network ($name) runs Meal Planner as a separate household. This PC keeps Google Calendar and Alexa."

    /** A copy of this household (restored from a backup, say): a notice only. */
    fun sameHousehold(name: String): String = "Another copy of this household runs on $name."

    /** YIELD_GOOGLE: the Calendar's send banner while this PC yields. */
    fun yieldGoogle(name: String): String =
        "Another PC on this network ($name) runs Meal Planner. Until they can sync, only that PC sends to Google Calendar."

    /** YIELD_ALEXA: Settings' Alexa section while this PC yields. */
    fun yieldAlexa(name: String): String = "Another PC on this network ($name) runs Meal Planner, so Alexa is answered there."
}
