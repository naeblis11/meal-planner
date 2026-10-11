package com.naeblis11.mealplanner.desktop.sync

import com.naeblis11.mealplanner.desktop.server.PortInUseException
import com.naeblis11.mealplanner.desktop.server.PortProbe
import com.naeblis11.mealplanner.sync.SyncErrors
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsParameters
import com.sun.net.httpserver.HttpsServer
import java.io.IOException
import java.io.InputStream
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import kotlinx.coroutines.runBlocking

/** Where the sync port listens, behind a seam so SyncServer's rules are tested without sockets (FakeSyncEngine). */
interface SyncEngine {
    /** Listens on [host]:[port] over TLS with [ssl], answering through [handler]; PortInUseException when taken. Blocks until bound. */
    fun start(host: String, port: Int, ssl: SSLContext, handler: SyncHandler)

    /** Stops listening, bounded; nothing when not listening. May throw; the caller then asks [listening]. */
    fun stop()

    /** True from a start that worked until a stop that worked. */
    val listening: Boolean
}

/**
 * The JDK's HttpsServer (jdk.httpserver, already in the packaged runtime), since Ktor 3.6's CIO engine can't serve HTTPS
 * (D1). TLS 1.2 and 1.3 only, nothing older.
 *
 * Bounded concurrency: the server's executor is a fixed pool of [threads] (THREADS, 4), and all of a connection's
 * blocking work runs there and nowhere else. The JDK's dispatcher thread only accepts, waits for a connection to become
 * readable and queues it; a pool thread then does the TLS handshake, reads the request line and headers, and runs the
 * handler with runBlocking until the reply is sent. The body is read only by the handler, through [boundedBody] (each
 * route's own cap). So at most [threads] handlers, and at most [threads] request bodies (each up to the exchange's
 * 50 MB), are in memory at once; a burst of devices waits in the queue.
 *
 * Stalls: since a pool thread blocks on its connection, a client that stops sending (one byte of a ClientHello, a phone
 * dropping mid-body) or stops reading would hold that thread for good, and [threads] of them would stop all sync. So
 * [StallGuard] limits each blocking phase: the handshake, request line and headers, and then each read of the body, to
 * [requestMillis]; sending the reply and closing the exchange to [responseMillis]. A thread past its limit is
 * interrupted, which closes its connection's channel and frees the thread; the handler itself (database work) is never
 * timed or interrupted. The JDK's own limits ([applyJdkLimits], 300 s each for a whole request and a whole reply) are
 * only a backstop: they are read once per process, when the JDK's server class first loads, and they time a whole body,
 * so they are kept well above what a 50 MB exchange needs on weak Wi-Fi.
 *
 * [portFree] is asked first: Windows lets 127.0.0.1 bind beside another program's 0.0.0.0 (tests pass a loopback probe
 * and never bind 0.0.0.0). A stop gives requests in flight [stopDelaySeconds], then waits up to DRAIN_SECONDS for the
 * threads to finish before interrupting them. A failure inside a request is answered 500 `failed` when nothing was sent
 * yet and logged as its method, path and exception class only: never the Authorization header, the body or a message.
 */
class JdkHttpsEngine(
    private val portFree: (Int) -> Boolean = { PortProbe.isFree(it) },
    private val threads: Int = THREADS,
    private val stopDelaySeconds: Int = STOP_DELAY_SECONDS,
    private val requestMillis: Long = REQUEST_MILLIS,
    private val responseMillis: Long = RESPONSE_MILLIS,
    private val log: (String) -> Unit = { System.err.println(it) },
) : SyncEngine {
    private var server: HttpsServer? = null
    private var pool: ExecutorService? = null
    private var guard: StallGuard? = null

    @get:Synchronized
    override val listening: Boolean
        get() = server != null

    @Synchronized
    override fun start(host: String, port: Int, ssl: SSLContext, handler: SyncHandler) {
        check(server == null) { "The sync listener is already running." }
        applyJdkLimits()
        if (!portFree(port)) throw PortInUseException(port, BindException("Port $port is held by another program"))
        val created = try {
            HttpsServer.create(InetSocketAddress(InetAddress.getByName(host), port), BACKLOG)
        } catch (e: IOException) {
            if (generateSequence<Throwable>(e) { it.cause }.any { it is BindException }) throw PortInUseException(port, e)
            throw e
        }
        val workers = Executors.newFixedThreadPool(threads) { task -> Thread(task, "meal-planner-sync").apply { isDaemon = true } }
        val stalls = StallGuard(sweepMillis = (minOf(requestMillis, responseMillis) / 4).coerceIn(10L, 1_000L))
        try {
            created.httpsConfigurator = ModernTls(ssl)
            // A connection's whole run on a pool thread (handshake, request line, headers, then serve) is one timed phase.
            created.executor = Executor { task ->
                workers.execute {
                    try {
                        stalls.phase(requestMillis) { task.run() }
                    } catch (_: StalledException) {
                        // Its channel is closed; the thread goes back to the pool.
                    }
                }
            }
            created.createContext("/") { exchange -> serve(exchange, handler, stalls) }
            stalls.start()
            created.start()
        } catch (e: Exception) {
            try {
                created.stop(0)
            } catch (stopFailure: Exception) {
                e.addSuppressed(stopFailure)
            } finally {
                workers.shutdownNow()
                stalls.stop()
            }
            throw e
        }
        server = created
        pool = workers
        guard = stalls
    }

    @Synchronized
    override fun stop() {
        val running = server ?: return
        try {
            running.stop(stopDelaySeconds)
        } finally {
            server = null
            pool?.let { workers ->
                workers.shutdown()
                if (!workers.awaitTermination(DRAIN_SECONDS, TimeUnit.SECONDS)) {
                    workers.shutdownNow()
                    if (!workers.awaitTermination(LAST_WAIT_SECONDS, TimeUnit.SECONDS)) {
                        log("Meal Planner: sync requests were still running at stop")
                    }
                }
            }
            pool = null
            guard?.stop()
            guard = null
        }
    }

    // On a pool thread, inside the connection's timed phase: the handler runs untimed; each body read and the reply are timed.
    private fun serve(exchange: HttpExchange, handler: SyncHandler, stalls: StallGuard) = stalls.phase(null) {
        var sent = false
        try {
            val request = SyncHttpRequest(
                method = exchange.requestMethod,
                path = exchange.requestURI.rawPath.orEmpty(),
                authorization = exchange.requestHeaders.getFirst("Authorization"),
                contentLength = declaredLength(exchange),
                body = boundedBody(TimedInput(exchange.requestBody, stalls, requestMillis)),
                remoteAddress = exchange.remoteAddress?.address?.hostAddress.orEmpty(),
            )
            val response = runBlocking { handler.handle(request) }
            sent = true
            stalls.phase(responseMillis) { send(exchange, response) }
        } catch (e: Exception) {
            log("Meal Planner: ${exchange.requestMethod} ${exchange.requestURI.rawPath.orEmpty()} failed: ${e.javaClass.simpleName}")
            if (!sent && e !is StalledException) {
                try {
                    stalls.phase(responseMillis) { send(exchange, SyncHttpResponse.error(500, SyncErrors.FAILED)) }
                } catch (_: Exception) {
                    // The connection is gone; nothing more to say.
                }
            }
        } finally {
            try {
                stalls.phase(responseMillis) { exchange.close() }
            } catch (_: Exception) {
                // Closed already, or stalled and closed by the guard.
            }
        }
    }

    private fun send(exchange: HttpExchange, response: SyncHttpResponse) {
        exchange.responseHeaders.set("Content-Type", response.contentType)
        if (response.body.isEmpty()) {
            exchange.sendResponseHeaders(response.status, -1)
        } else {
            exchange.sendResponseHeaders(response.status, response.body.size.toLong())
            exchange.responseBody.write(response.body)
        }
    }

    // The declared Content-Length, -1 when none (chunked) or not a length.
    private fun declaredLength(exchange: HttpExchange): Long {
        if (exchange.requestHeaders.getFirst("Transfer-Encoding") != null) return -1L
        return exchange.requestHeaders.getFirst("Content-Length")?.trim()?.toLongOrNull()?.takeIf { it >= 0 } ?: -1L
    }

    // TLS 1.2 and 1.3, nothing older: phones on Android 8 and 9 (minSdk 26) speak only 1.2 by default, and the pinned
    // certificate is what protects the connection. No client certificates (a device proves itself with its key).
    private class ModernTls(context: SSLContext) : HttpsConfigurator(context) {
        override fun configure(params: HttpsParameters) {
            val parameters = sslContext.defaultSSLParameters
            parameters.protocols = PROTOCOLS.copyOf()
            parameters.needClientAuth = false
            params.setSSLParameters(parameters)
        }
    }

    companion object {
        const val THREADS = 4
        const val STOP_DELAY_SECONDS = 1
        const val DRAIN_SECONDS = 2L
        private const val LAST_WAIT_SECONDS = 1L
        private const val BACKLOG = 16
        internal val PROTOCOLS = arrayOf("TLSv1.3", "TLSv1.2")

        /** The handshake, request line and headers, and each read of a body, at most this long: then the client has stalled. */
        const val REQUEST_MILLIS = 30_000L

        /** Sending a reply (up to the exchange's 64 MB) at most this long. */
        const val RESPONSE_MILLIS = 120_000L

        const val JDK_MAX_REQ_TIME = "sun.net.httpserver.maxReqTime"
        const val JDK_MAX_RSP_TIME = "sun.net.httpserver.maxRspTime"

        /** The JDK server's own limits, in seconds: a backstop only (see [applyJdkLimits]). */
        const val JDK_LIMIT_SECONDS = 300L

        /**
         * The JDK server's own limits, wherever nobody set them: [JDK_LIMIT_SECONDS] (300 s) each for maxReqTime (from a
         * request's first byte until its whole body is read) and maxRspTime (from then until its reply is sent). They are
         * a backstop only. They time a request's whole body, so anything near [REQUEST_MILLIS] would cut a 50 MB exchange
         * on weak Wi-Fi. [StallGuard] does the real work: [REQUEST_MILLIS] (30 s) for the handshake and headers and for
         * each single body read, [RESPONSE_MILLIS] (120 s) for the reply.
         *
         * The JDK's ServerImpl reads them once, when it first loads, for every HttpServer in the process (SelfCheck's and
         * GoogleSignIn's too, both short loopback exchanges well inside them). So Main calls this first thing, because
         * SelfCheck's HttpServer.create() (smoke runs only) would otherwise load them as -1, no limit.
         */
        fun applyJdkLimits() {
            if (System.getProperty(JDK_MAX_REQ_TIME) == null) System.setProperty(JDK_MAX_REQ_TIME, JDK_LIMIT_SECONDS.toString())
            if (System.getProperty(JDK_MAX_RSP_TIME) == null) System.setProperty(JDK_MAX_RSP_TIME, JDK_LIMIT_SECONDS.toString())
        }
    }
}

/** A connection that stayed in one blocking phase past its limit; its channel has been closed. */
internal class StalledException : IOException("The connection stalled.")

/**
 * Limits how long a thread stays in a blocking phase. [phase] runs a block with a limit (null: untimed, for the handler);
 * phases nest, and on leaving one the outer phase's limit starts again. Every [sweepMillis] a thread past its deadline is
 * interrupted, which closes the socket channel it is blocked on (an InterruptibleChannel) and frees it. The interrupt is
 * sent under the lock a phase is left under, so it can only land inside the phase that earned it; leaving that phase
 * clears it and throws [StalledException].
 */
internal class StallGuard(private val sweepMillis: Long) {
    private class Phase(val limitMillis: Long?, val deadline: Long)

    private val lock = Any()
    private val phases = HashMap<Thread, Phase>()
    private val fired = HashSet<Thread>()
    private var sweeper: ScheduledExecutorService? = null

    fun start() {
        synchronized(lock) {
            if (sweeper != null) return
            sweeper = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "meal-planner-sync-guard").apply { isDaemon = true } }
                .apply { scheduleWithFixedDelay(::sweep, sweepMillis, sweepMillis, TimeUnit.MILLISECONDS) }
        }
    }

    fun stop() {
        synchronized(lock) {
            sweeper?.shutdownNow()
            sweeper = null
        }
    }

    fun <T> phase(limitMillis: Long?, block: () -> T): T {
        val thread = Thread.currentThread()
        val outer = synchronized(lock) { phases.put(thread, Phase(limitMillis, deadline(limitMillis))) }
        var stalled = false
        try {
            return block()
        } finally {
            synchronized(lock) {
                stalled = fired.remove(thread)
                if (outer == null) phases.remove(thread) else phases[thread] = Phase(outer.limitMillis, deadline(outer.limitMillis))
            }
            if (stalled) {
                Thread.interrupted()
                throw StalledException()
            }
        }
    }

    private fun deadline(limitMillis: Long?): Long =
        if (limitMillis == null) Long.MAX_VALUE else System.nanoTime() / 1_000_000 + limitMillis

    private fun sweep() {
        synchronized(lock) {
            val now = System.nanoTime() / 1_000_000
            for ((thread, phase) in phases) {
                if (phase.limitMillis != null && now >= phase.deadline && fired.add(thread)) thread.interrupt()
            }
        }
    }
}

/** The request body with each read timed by [stalls]: a phone that stops sending mid-body frees its thread. */
private class TimedInput(private val input: InputStream, private val stalls: StallGuard, private val limitMillis: Long) : InputStream() {
    override fun read(): Int = stalls.phase(limitMillis) { input.read() }

    override fun read(b: ByteArray, off: Int, len: Int): Int = stalls.phase(limitMillis) { input.read(b, off, len) }

    override fun close() = input.close()
}
