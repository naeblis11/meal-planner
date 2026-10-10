package com.naeblis11.mealplanner.desktop.server

import io.ktor.server.application.Application
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * A server engine that binds nothing: it records where it was asked to listen, and fails when told to. Like KtorEngine,
 * a stop that throws leaves it listening, and a start while it listens throws (check: already listening).
 */
class FakeEngine : ServerEngine {
    private val hosts = mutableListOf<String>()
    private val failures = ArrayDeque<Exception>()
    private var attempts = 0
    private var stops = 0
    private var listeningOn: String? = null

    @get:Synchronized
    override val listening: Boolean
        get() = listeningOn != null

    /** Called at the start of each bind, to see the server's state then. */
    @Volatile
    var onStart: (() -> Unit)? = null

    @Synchronized
    fun failNext(vararg errors: Exception) {
        failures.addAll(errors)
    }

    @Synchronized
    override fun start(host: String, port: Int, module: Application.() -> Unit) {
        onStart?.invoke()
        attempts++
        check(listeningOn == null) { "The server is already listening." }
        failures.removeFirstOrNull()?.let { throw it }
        hosts += host
        listeningOn = host
    }

    /** Thrown by every stop of a listener while set, as an engine that couldn't let go of its port: it still listens. */
    @Volatile
    var stopFailure: Exception? = null

    /** When set, each stop waits for it: a stop still under way (CIO's grace and timeout take up to about 3 s). */
    @Volatile
    var stopGate: CountDownLatch? = null

    /** Counted down when a stop starts. */
    val stopEntered = CountDownLatch(1)

    override fun stop() {
        stopEntered.countDown()
        stopGate?.await(5, TimeUnit.SECONDS)
        synchronized(this) {
            stops++
            if (listeningOn == null) return
            stopFailure?.let { throw it }
            listeningOn = null
        }
    }

    @Synchronized
    fun startedHosts(): List<String> = hosts.toList()

    @Synchronized
    fun attempts(): Int = attempts

    @Synchronized
    fun stops(): Int = stops
}

/** Waits up to [millis] for [condition], looking every 10 ms. */
fun eventually(millis: Long = 5_000, condition: () -> Boolean) {
    val end = System.currentTimeMillis() + millis
    while (!condition()) {
        check(System.currentTimeMillis() < end) { "timed out waiting" }
        Thread.sleep(10)
    }
}

/** A port on 127.0.0.1 that nothing listens on just now (never 5000). */
fun freeLoopbackPort(): Int = ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { it.localPort }

/** The real CIO engine, probing 127.0.0.1 instead of 0.0.0.0: tests never bind beyond this PC. */
fun loopbackEngine(log: (String) -> Unit = { System.err.println(it) }): KtorEngine =
    KtorEngine(portFree = { PortProbe.isFree(it, InetAddress.getLoopbackAddress()) }, log = log)

/** Runs [block] with System.err captured, and returns what it wrote. */
fun captureStderr(block: () -> Unit): String {
    val original = System.err
    val captured = java.io.ByteArrayOutputStream()
    System.setErr(java.io.PrintStream(captured, true, Charsets.UTF_8))
    try {
        block()
    } finally {
        System.setErr(original)
    }
    return captured.toString(Charsets.UTF_8)
}

/** A plain JPEG of [width] x [height]. */
fun jpegBytes(width: Int = 400, height: Int = 300): ByteArray =
    ByteArrayOutputStream().also { ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray()
