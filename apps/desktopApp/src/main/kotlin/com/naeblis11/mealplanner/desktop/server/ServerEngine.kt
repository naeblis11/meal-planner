package com.naeblis11.mealplanner.desktop.server

import io.ktor.server.application.Application
import io.ktor.server.application.serverConfig
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.nio.channels.SocketChannel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Where the server listens, behind a seam so MealPlannerServer's rules are tested without sockets (FakeEngine). */
interface ServerEngine {
    /**
     * Listens on [host]:[port] with [module]'s routes; throws PortInUseException when the port is taken and
     * ServerStartTimeoutException when the bind didn't finish in time (both worth trying again later). Blocking.
     */
    fun start(host: String, port: Int, module: Application.() -> Unit)

    /**
     * Stops listening, bounded; does nothing when not listening. Blocking. May throw; the caller logs it and asks
     * [listening] whether the listener is still there (a stop can throw and still have stopped it).
     */
    fun stop()

    /** True while a listener started here still runs: from a start that worked until a stop that worked. */
    val listening: Boolean
}

/** Another program (the old Python server, most likely) holds [port]. */
class PortInUseException(val port: Int, cause: Throwable) : IOException("Port $port is in use", cause)

/**
 * The engine was started but hadn't bound [port] within its start timeout (CIO binds in the background). Nothing is
 * known to hold the port, but nothing listens either: the start is tried again later, as a port in use is.
 */
class ServerStartTimeoutException(val port: Int, cause: Throwable) : IOException("The server took too long to start on port $port", cause)

/**
 * Is a port free on every interface? Windows lets 127.0.0.1:port be bound beside another program's 0.0.0.0:port, so
 * listening on this PC only next to the old Python server would split the callers: the Chrome extension would reach
 * this app and Home Assistant the old one. The probe binds [address] (0.0.0.0 by default) without listening, and lets
 * go at once; a port held on 0.0.0.0 or on 127.0.0.1 fails it.
 */
object PortProbe {
    /** 0.0.0.0, which python app.py binds with MEAL_PLANNER_HOST=0.0.0.0. */
    val ANY: InetAddress = InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0))

    /** True when [port] can be bound on [address] (IPv4) just now; false when something holds it. Blocking, briefly. */
    fun isFree(port: Int, address: InetAddress = ANY): Boolean =
        try {
            // A client channel, bound and never connected: it holds the port for an instant and accepts nothing.
            SocketChannel.open(StandardProtocolFamily.INET).use { it.bind(InetSocketAddress(address, port)) }
            true
        } catch (e: BindException) {
            false
        }
}

/**
 * Ktor's CIO engine (P4-R1: no Netty). [portFree] is asked first, so a port held elsewhere on any interface is a
 * PortInUseException before CIO starts (tests pass a loopback probe; they never bind 0.0.0.0). A bind that hasn't
 * finished within [startTimeoutMillis] ([awaitBound] waits for CIO's resolved connectors; tests pass a wait of their
 * own) is a ServerStartTimeoutException, and the engine is stopped again. Stopping lets requests in flight finish for
 * [graceMillis], and takes [timeoutMillis] at most. A failure inside the running engine is [log]ged as one line, never
 * as a stack trace.
 */
class KtorEngine(
    private val graceMillis: Long = GRACE_MILLIS,
    private val timeoutMillis: Long = STOP_TIMEOUT_MILLIS,
    private val portFree: (Int) -> Boolean = { PortProbe.isFree(it) },
    private val log: (String) -> Unit = { System.err.println(it) },
    private val stopServer: (EmbeddedServer<*, *>, Long, Long) -> Unit = { server, grace, timeout -> server.stop(grace, timeout) },
    private val startTimeoutMillis: Long = START_TIMEOUT_MILLIS,
    private val awaitBound: suspend (EmbeddedServer<*, *>) -> Unit = { it.engine.resolvedConnectors() },
) : ServerEngine {
    private var server: EmbeddedServer<*, *>? = null

    @get:Synchronized
    override val listening: Boolean
        get() = server != null

    // CIO's coroutines run under this: an error that would otherwise print a stack trace (to stderr, and so to
    // .cache\meal-planner.log) is one line instead. A bind failure is start()'s to report, as PortInUseException.
    private val uncaught = CoroutineExceptionHandler { _, e ->
        if (generateSequence(e) { it.cause }.none { it is BindException }) {
            log("Meal Planner: the server failed: ${e.toString().lineSequence().first()}")
        }
    }

    @Synchronized
    override fun start(host: String, port: Int, module: Application.() -> Unit) {
        check(server == null) { "The server is already listening." }
        if (!portFree(port)) throw PortInUseException(port, BindException("Port $port is held by another program"))
        val config = serverConfig {
            parentCoroutineContext = uncaught
            module(module)
        }
        val created = embeddedServer(CIO, config) {
            connector {
                this.host = host
                this.port = port
            }
        }
        try {
            created.start(wait = false)
            // CIO binds in the background: wait for it, so a port taken since the probe is known here.
            runBlocking { withTimeout(startTimeoutMillis) { awaitBound(created) } }
        } catch (t: Throwable) {
            try {
                created.stop(0, 0)
            } catch (e: Exception) {
                // It never got going; the start's own failure is the one that matters.
            }
            if (generateSequence(t) { it.cause }.any { it is BindException }) throw PortInUseException(port, t)
            // The wait ran out, with no bind failure to show for it: worth another try, like a port in use.
            if (t is TimeoutCancellationException) throw ServerStartTimeoutException(port, t)
            throw t
        }
        server = created
    }

    /**
     * A graceful stop that throws is followed by a forced one (no grace, no wait), and the graceful stop's failure is
     * thrown. The handle is let go only once a stop has worked: one lost while the listener still ran would leave it on
     * the home network with nothing to stop it, and a later stop() tries again.
     */
    @Synchronized
    override fun stop() {
        val running = server ?: return
        var stopped = false
        try {
            stopServer(running, graceMillis, timeoutMillis)
            stopped = true
        } finally {
            if (!stopped) {
                try {
                    stopServer(running, 0, 0)
                    stopped = true
                } catch (e: Exception) {
                    // Still running: kept for the next stop; the first failure is the one thrown.
                }
            }
            if (stopped) server = null
        }
    }

    companion object {
        const val GRACE_MILLIS = 1_000L
        const val STOP_TIMEOUT_MILLIS = 2_000L
        const val START_TIMEOUT_MILLIS = 5_000L

        /** Ktor's own shutdown hook (on by default) would race the app's bounded close at sign-out; Main turns it off. */
        const val SHUTDOWN_HOOK_PROPERTY = "io.ktor.server.engine.ShutdownHook"
    }
}
