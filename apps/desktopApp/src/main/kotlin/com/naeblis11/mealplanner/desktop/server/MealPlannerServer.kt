package com.naeblis11.mealplanner.desktop.server

import com.naeblis11.mealplanner.settings.ServerState
import com.naeblis11.mealplanner.settings.ServerStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The built-in server (P4-R1) on [port]:
 * - It listens on 127.0.0.1 only, unless an Alexa token is set up (ApiToken.configured: 16 characters or more); then
 *   on [lanHost] (all interfaces), so Home Assistant on the home network reaches /api/voice/... (written so: a slash-star would open a nested comment).
 * - While an older household's Meal Planner PC on the network answers Alexa ([lanAllowed] false, plan 6), it listens
 *   on 127.0.0.1 only, as if no token were set up. The token is kept; a rebind follows the yield.
 * - A port already taken (the old python app.py) is logged once, shown in [status], and tried again every
 *   [retryMillis] until it is free. The rest of the app carries on meanwhile.
 * - A rebind whose stop fails leaves the old listener running: [status] still says where it listens, and the stop and
 *   the new bind are tried again every [retryMillis] until the stop works.
 * - Never logs the token: only whether one is set up.
 */
class MealPlannerServer(
    private val token: ApiToken,
    private val routes: ServerRoutes,
    private val engine: ServerEngine = KtorEngine(),
    private val port: Int = PORT,
    private val lanHost: String = ALL_INTERFACES,
    private val retryMillis: Long = RETRY_MILLIS,
    private val log: (String) -> Unit = { System.err.println(it) },
    private val lanAllowed: () -> Boolean = { true },
) : AppServer {
    private val lock = Any()
    private val _status = MutableStateFlow(ServerStatus(ServerState.STARTING, port, tokenConfigured = token.configured))
    override val status: StateFlow<ServerStatus> = _status.asStateFlow()
    private var scope: CoroutineScope? = null
    private var retry: Job? = null
    private var stopped = false
    private var saidInUse = false

    // The old listener outlived a stop: binding beside it can only fail, so the retry loop stops it first.
    private var stuck = false
    private var saidStuck = false

    // Where the engine's listener is: set by each bind that works, so a stuck listener's status tells the truth.
    private var boundOnLan = false

    override fun start(scope: CoroutineScope) {
        synchronized(lock) {
            if (stopped || this.scope != null) return
            this.scope = scope
            bindLocked()
        }
    }

    override fun rebind() {
        synchronized(lock) {
            if (stopped || scope == null) {
                _status.value = _status.value.copy(tokenConfigured = token.configured)
                return
            }
            retry?.cancel()
            retry = null
            val before = _status.value
            // Not listening where it said from the moment the stop begins (CIO's grace and timeout take up to about
            // 3 s), until the new bind says otherwise (up to START_TIMEOUT_MILLIS): Settings says Starting... rather
            // than "on your home network" beside a yield.
            _status.value = before.copy(state = ServerState.STARTING)
            if (stopEngineLocked("stopping the server to listen again failed")) {
                stuck = false
                bindLocked()
            } else {
                // The old listener runs on (on the home network, maybe, beside a yield: P6-R8). A bind beside it can
                // only fail, so say where it really listens and try the stop and the bind again later.
                stuckLocked()
            }
        }
    }

    override fun stop() {
        synchronized(lock) {
            stopped = true
            retry?.cancel()
            retry = null
            stopEngineLocked("stopping the server failed")
            _status.value = _status.value.copy(state = ServerState.STOPPED)
        }
    }

    // Under the lock. True once nothing listens (a stop can throw and still have stopped it). The class only: an
    // exception's message is not ours to vouch for, and must never carry the token.
    private fun stopEngineLocked(what: String): Boolean {
        try {
            engine.stop()
        } catch (e: Exception) {
            log("Meal Planner: $what: ${e.javaClass.simpleName}")
        }
        return !engine.listening
    }

    // Under the lock: the old listener outlived its stop. Still listening where it was, said once, and retried.
    private fun stuckLocked() {
        stuck = true
        _status.value = ServerStatus(ServerState.LISTENING, port, onLan = boundOnLan, tokenConfigured = token.configured)
        if (!saidStuck) {
            val where = if (boundOnLan) "on the home network" else "on this PC only"
            log("Meal Planner: the server is still listening $where, because it couldn't be stopped. Trying again every ${retryMillis / 1000} s.")
        }
        saidStuck = true
        scheduleRetryLocked()
    }

    // Under the lock: this PC only, or the home network once a token is set up and no older PC answers Alexa (P6-R8).
    private fun bindLocked() {
        val configured = token.configured
        val lan = configured && lanAllowed()
        val host = if (lan) lanHost else LOOPBACK
        try {
            engine.start(host, port) { routes.install(this) }
            boundOnLan = lan
            _status.value = ServerStatus(ServerState.LISTENING, port, onLan = lan, tokenConfigured = configured)
            if (saidInUse) log("Meal Planner: port $port is free again.")
            saidInUse = false
            saidStuck = false
            val where = when {
                lan -> "on the home network (an Alexa token is set up)"
                configured -> "on this PC only (another Meal Planner PC on the network answers Alexa)"
                else -> "on this PC only"
            }
            log("Meal Planner: listening on port $port, $where.")
        } catch (e: PortInUseException) {
            _status.value = ServerStatus(ServerState.PORT_IN_USE, port, tokenConfigured = configured)
            if (!saidInUse) {
                log("Meal Planner: port $port is in use, so the Chrome extension and Alexa can't reach the app. Is the old Meal Planner server still running? Trying again every ${retryMillis / 1000} s.")
            }
            saidInUse = true
            scheduleRetryLocked()
        } catch (e: Exception) {
            _status.value = ServerStatus(ServerState.FAILED, port, tokenConfigured = configured)
            log("Meal Planner: the server couldn't start: $e")
        }
    }

    // Under the lock. One loop at a time; it ends once the old listener has stopped and the port is free, the server is
    // stopped, or a rebind took over (it cancels the loop, and starts its own if it needs one).
    private fun scheduleRetryLocked() {
        if (retry?.isActive == true) return
        val where = scope ?: return
        retry = where.launch {
            while (true) {
                delay(retryMillis)
                val again = synchronized(lock) {
                    when {
                        stopped -> false
                        stuck -> {
                            if (stopEngineLocked("stopping the server to listen again failed")) {
                                stuck = false
                                bindLocked()
                                _status.value.state == ServerState.PORT_IN_USE
                            } else {
                                true
                            }
                        }
                        _status.value.state != ServerState.PORT_IN_USE -> false
                        else -> {
                            bindLocked()
                            _status.value.state == ServerState.PORT_IN_USE
                        }
                    }
                }
                if (!again) break
            }
        }
    }

    companion object {
        /** The Python server's port, which the Chrome extension and Home Assistant's config already use. */
        const val PORT = 5000

        /** Set by Gradle's run (5055), so the preview never takes the port of the running app or the old server. */
        const val PORT_PROPERTY = "mealplanner.port"

        /** The port in [property] (1 to 65535), else [PORT]. Main reads it once, at startup. */
        fun portFrom(property: String? = System.getProperty(PORT_PROPERTY)): Int =
            property?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: PORT

        const val LOOPBACK = "127.0.0.1"
        const val ALL_INTERFACES = "0.0.0.0"
        const val RETRY_MILLIS = 60_000L
    }
}
