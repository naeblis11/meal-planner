package com.naeblis11.mealplanner.desktop.server

import com.naeblis11.mealplanner.settings.ServerStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * What DesktopApp needs of its server (P4-R1): started after the startup sync, stopped first when the app closes.
 * Every call blocks and none belongs on the UI thread: each may wait for a bind already under way (a retry while the
 * port is in use), which takes up to KtorEngine.START_TIMEOUT_MILLIS.
 */
interface AppServer {
    val status: StateFlow<ServerStatus>

    /** Starts listening; a port in use is tried again, in [scope], until it is free. Blocking: one bind, at most START_TIMEOUT_MILLIS. */
    fun start(scope: CoroutineScope)

    /**
     * Listens again with the token as it is now: on the home network once one is set up. Blocking: a bind under way,
     * the engine's stop (KtorEngine.STOP_TIMEOUT_MILLIS at most), then a new bind, each up to START_TIMEOUT_MILLIS.
     * Call it off the UI thread. [status] says STARTING from the stop until the new bind's outcome.
     */
    fun rebind()

    /**
     * Stops listening and retrying, for good. Blocking: a bind under way (up to START_TIMEOUT_MILLIS), then the
     * engine's stop (STOP_TIMEOUT_MILLIS at most).
     */
    fun stop()
}
