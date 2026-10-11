package com.naeblis11.mealplanner.desktop.sync

import com.naeblis11.mealplanner.desktop.server.MealPlannerServer
import com.naeblis11.mealplanner.desktop.server.PortInUseException
import com.naeblis11.mealplanner.settings.SyncServerState
import com.naeblis11.mealplanner.settings.SyncServerStatus
import javax.net.ssl.SSLContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The sync port (LAN sync spec, section 2), modelled on MealPlannerServer:
 * - It listens on [lanHost] (all interfaces) only while [wanted] (a device is paired, or the pairing window is open) and
 *   [lanAllowed] (no older household's PC on the network); otherwise it is closed (OFF). There is no loopback mode.
 * - [rebind] settles it to what those say now, and does nothing while that is unchanged, so a reply under way (a
 *   pairing's own) is never cut by a needless restart.
 * - A port in use is logged once, shown in [status], and tried again every [retryMillis].
 * - A stop that fails leaves it LISTENING (stuck), and the stop is tried again every [retryMillis].
 * - [ssl] is asked at each bind: the certificate is made on first need. A failure there (SyncCertificate.sslContext()
 *   throws when the certificate can't be written) or in the engine's start is FAILED, logged as its class only and never
 *   thrown to the caller; the next [rebind] tries again.
 * - All state is under one lock. Every call blocks; never call one on the UI thread.
 */
class SyncServer(
    private val engine: SyncEngine,
    private val ssl: () -> SSLContext,
    private val handler: SyncHandler,
    private val wanted: () -> Boolean,
    private val lanAllowed: () -> Boolean = { true },
    val port: Int = SYNC_PORT,
    private val lanHost: String = ALL_INTERFACES,
    private val retryMillis: Long = RETRY_MILLIS,
    private val log: (String) -> Unit = { System.err.println(it) },
) {
    private val lock = Any()
    private val _status = MutableStateFlow(SyncServerStatus(SyncServerState.OFF, port))
    val status: StateFlow<SyncServerStatus> = _status.asStateFlow()
    private var scope: CoroutineScope? = null
    private var retry: Job? = null
    private var stopped = false
    private var saidInUse = false
    private var stuck = false
    private var saidStuck = false

    fun start(scope: CoroutineScope) {
        synchronized(lock) {
            if (stopped || this.scope != null) return
            this.scope = scope
            settleLocked()
        }
    }

    /** Listens or closes as [wanted] and [lanAllowed] say now; nothing when that is already so. */
    fun rebind() {
        synchronized(lock) {
            if (stopped || scope == null) return
            settleLocked()
        }
    }

    /** Stops listening and retrying, for good. */
    fun stop() {
        synchronized(lock) {
            stopped = true
            retry?.cancel()
            retry = null
            stopEngineLocked()
            _status.value = SyncServerStatus(SyncServerState.OFF, port)
        }
    }

    private fun listenWanted(): Boolean = wanted() && lanAllowed()

    // Under the lock.
    private fun settleLocked() {
        val listen = listenWanted()
        val state = _status.value.state
        if (!stuck && listen && state == SyncServerState.LISTENING) return
        if (!stuck && !listen && state == SyncServerState.OFF && !engine.listening) return
        retry?.cancel()
        retry = null
        val wasListening = engine.listening
        if (wasListening && !stopEngineLocked()) {
            stuckLocked()
            return
        }
        stuck = false
        if (listen) bindLocked() else closedLocked(wasListening)
    }

    // Under the lock. True once nothing listens. The class only: an exception's message is not ours to vouch for.
    private fun stopEngineLocked(): Boolean {
        try {
            engine.stop()
        } catch (e: Exception) {
            log("Meal Planner: stopping the sync listener failed: ${e.javaClass.simpleName}")
        }
        return !engine.listening
    }

    private fun closedLocked(wasListening: Boolean) {
        _status.value = SyncServerStatus(SyncServerState.OFF, port)
        if (wasListening) log("Meal Planner: the sync port $port is closed.")
    }

    private fun stuckLocked() {
        stuck = true
        _status.value = SyncServerStatus(SyncServerState.LISTENING, port)
        if (!saidStuck) log("Meal Planner: the sync listener couldn't be stopped, so it still listens on port $port. Trying again every ${retryMillis / 1000} s.")
        saidStuck = true
        scheduleRetryLocked()
    }

    private fun bindLocked() {
        _status.value = SyncServerStatus(SyncServerState.STARTING, port)
        try {
            engine.start(lanHost, port, ssl(), handler)
            // Logged before LISTENING is published, so whoever sees the state also finds the lines.
            if (saidInUse) log("Meal Planner: port $port is free again.")
            saidInUse = false
            saidStuck = false
            log("Meal Planner: sync is listening on port $port on the home network.")
            _status.value = SyncServerStatus(SyncServerState.LISTENING, port)
        } catch (e: PortInUseException) {
            _status.value = SyncServerStatus(SyncServerState.PORT_IN_USE, port)
            if (!saidInUse) {
                log("Meal Planner: port $port is in use, so devices can't sync. Is another program using it? Trying again every ${retryMillis / 1000} s.")
            }
            saidInUse = true
            scheduleRetryLocked()
        } catch (e: Exception) {
            _status.value = SyncServerStatus(SyncServerState.FAILED, port)
            log("Meal Planner: the sync listener couldn't start: ${e.javaClass.simpleName}")
        }
    }

    // Under the lock. One loop at a time; it ends once the port is free or closed, the old listener has stopped, or
    // the server is stopped. A rebind cancels it and starts its own when it needs one.
    private fun scheduleRetryLocked() {
        if (retry?.isActive == true) return
        val where = scope ?: return
        retry = where.launch {
            while (true) {
                delay(retryMillis)
                val again = synchronized(lock) {
                    when {
                        stopped -> false
                        stuck -> if (stopEngineLocked()) {
                            stuck = false
                            if (listenWanted()) bindLocked() else closedLocked(wasListening = true)
                            _status.value.state == SyncServerState.PORT_IN_USE
                        } else {
                            true
                        }
                        _status.value.state != SyncServerState.PORT_IN_USE -> false
                        !listenWanted() -> {
                            closedLocked(wasListening = false)
                            false
                        }
                        else -> {
                            bindLocked()
                            _status.value.state == SyncServerState.PORT_IN_USE
                        }
                    }
                }
                if (!again) break
            }
        }
    }

    companion object {
        /** The sync port (LAN sync spec, section 2). */
        const val SYNC_PORT = 5001

        /** Set by Gradle's run (5065, or 5066 beside a preview on 5056), so the preview never takes the installed app's sync port. */
        const val PORT_PROPERTY = "mealplanner.syncPort"

        /**
         * The port in [property] (1 to 65535), else [default]. Never the built-in server's 5000, and never [SYNC_PORT]
         * unless that is the default: a preview must not take the installed app's sync port. Main reads it once, at startup.
         */
        fun portFrom(property: String? = System.getProperty(PORT_PROPERTY), default: Int = SYNC_PORT): Int =
            property?.trim()?.toIntOrNull()
                ?.takeIf { it in 1..65535 && it != MealPlannerServer.PORT && (it != SYNC_PORT || default == SYNC_PORT) }
                ?: default

        const val ALL_INTERFACES = "0.0.0.0"
        const val RETRY_MILLIS = 60_000L
    }
}
