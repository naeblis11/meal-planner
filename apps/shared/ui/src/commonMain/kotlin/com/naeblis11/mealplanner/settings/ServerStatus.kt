package com.naeblis11.mealplanner.settings

/** Where the desktop's built-in server is (P4-R1). */
enum class ServerState { STARTING, LISTENING, PORT_IN_USE, FAILED, STOPPED }

/**
 * The built-in server as the window and Settings show it. [onLan]: listening on the home network (an Alexa token is
 * set up), not just this PC.
 */
data class ServerStatus(val state: ServerState, val port: Int, val onLan: Boolean = false, val tokenConfigured: Boolean = false)

/** The one-line notice in the window, the tray and Settings while the port is taken (P4-R1). */
fun portInUseNotice(port: Int): String =
    "Port $port is in use; the Chrome extension and Alexa won't reach Meal Planner. Is the old Meal Planner server still running?"
