package com.naeblis11.mealplanner.desktop.server

import io.ktor.server.engine.EmbeddedServer
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The real CIO engine, on 127.0.0.1 ephemeral ports only (never 5000, never the network): every engine here probes
 * 127.0.0.1 (loopbackEngine), and the one test of the real 0.0.0.0 probe runs only against a port already held.
 */
class KtorEngineTest {
    private val loopback: InetAddress = InetAddress.getLoopbackAddress()

    // HTTP/1.1: the JDK client's default h2c upgrade on plain http would be a needless variable against CIO.
    private val client: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    private fun healthz(port: Int): HttpResponse<String> =
        client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/healthz")).GET().build(), HttpResponse.BodyHandlers.ofString())

    @Test
    fun itAnswersHealthzOverARealSocket() {
        val port = freeLoopbackPort()
        val engine = loopbackEngine()
        engine.start(MealPlannerServer.LOOPBACK, port) { ServerRoutes().install(this) }
        try {
            val response = healthz(port)
            assertEquals(200, response.statusCode())
            assertEquals("""{"ok":true}""", response.body())
        } finally {
            engine.stop()
        }
    }

    @Test
    fun aPortThatIsTakenIsAPortInUse() {
        val logs = CopyOnWriteArrayList<String>()
        val err = captureStderr {
            ServerSocket(0, 50, loopback).use { taken ->
                val engine = loopbackEngine { logs += it }
                try {
                    engine.start(MealPlannerServer.LOOPBACK, taken.localPort) {}
                    fail("started on a port that was taken")
                } catch (e: PortInUseException) {
                    assertEquals(taken.localPort, e.port)
                } finally {
                    engine.stop()
                }
            }
            // Anything CIO would print comes from its own threads: give them a moment.
            Thread.sleep(200)
        }
        assertFalse("a stack trace on stderr:\n$err", "\tat " in err)
        assertEquals(emptyList<String>(), logs.toList())
    }

    @Test
    fun aBindThatFailsInsideCioIsAPortInUseWithoutAStackTrace() {
        // The probe says free (a port taken between the probe and CIO's bind): CIO's own bind fails, quietly.
        val logs = CopyOnWriteArrayList<String>()
        val err = captureStderr {
            ServerSocket(0, 50, loopback).use { taken ->
                val engine = KtorEngine(portFree = { true }, log = { logs += it })
                try {
                    engine.start(MealPlannerServer.LOOPBACK, taken.localPort) {}
                    fail("started on a port that was taken")
                } catch (e: PortInUseException) {
                    assertEquals(taken.localPort, e.port)
                } finally {
                    engine.stop()
                }
            }
            Thread.sleep(200)
        }
        assertFalse("a stack trace on stderr:\n$err", "\tat " in err)
        // start() reports the bind failure; the engine's handler doesn't say it a second time.
        assertEquals(emptyList<String>(), logs.toList())
    }

    @Test
    fun aPortTheProbeSaysIsTakenNeverStartsCio() {
        val port = freeLoopbackPort()
        var built = false
        val probed = mutableListOf<Int>()
        val engine = KtorEngine(portFree = { probed += it; false })
        try {
            engine.start(MealPlannerServer.LOOPBACK, port) { built = true }
            fail("started although the probe said the port was taken")
        } catch (e: PortInUseException) {
            assertEquals(port, e.port)
        }
        engine.stop()
        assertEquals(listOf(port), probed)
        assertFalse("CIO built the application", built)
        // Nothing listens there: it binds at once.
        ServerSocket(port, 50, loopback).close()
    }

    @Test
    fun theRealProbeFindsAPortHeldOnThisPcOnly() {
        // The old server on 127.0.0.1 (MEAL_PLANNER_HOST unset) trips the 0.0.0.0 probe too. Held first, so the
        // probe's 0.0.0.0 bind fails and binds nothing.
        ServerSocket(0, 50, loopback).use { held ->
            assertFalse(PortProbe.isFree(held.localPort))
        }
    }

    @Test
    fun stopLetsGoOfThePort() {
        val port = freeLoopbackPort()
        val engine = loopbackEngine()
        engine.start(MealPlannerServer.LOOPBACK, port) {}
        engine.stop()
        // No connection was made, so nothing lingers on the port: it binds again at once.
        ServerSocket(port, 50, loopback).close()
    }

    // CIO's own stop, except that a graceful one (any non-zero grace or timeout) throws while [failGraceful] says so,
    // and a forced one (0, 0) while [failForced] does: an engine that can't let go, without faking the sockets.
    private class FlakyStops {
        @Volatile var failGraceful = true
        @Volatile var failForced = false
        val calls = CopyOnWriteArrayList<Pair<Long, Long>>()

        fun stop(server: EmbeddedServer<*, *>, grace: Long, timeout: Long) {
            calls += grace to timeout
            val forced = grace == 0L && timeout == 0L
            if (if (forced) failForced else failGraceful) throw IllegalStateException("stop failed")
            server.stop(grace, timeout)
        }
    }

    private fun flakyEngine(stops: FlakyStops) =
        KtorEngine(portFree = { PortProbe.isFree(it, loopback) }, stopServer = stops::stop)

    @Test
    fun aStopThatThrowsIsForcedSoNoListenerIsLeftBehind() {
        val port = freeLoopbackPort()
        val stops = FlakyStops()
        val engine = flakyEngine(stops)
        engine.start(MealPlannerServer.LOOPBACK, port) {}
        try {
            engine.stop()
            fail("the graceful stop's failure was swallowed")
        } catch (e: IllegalStateException) {
            // The caller (MealPlannerServer) logs it; the engine has already let go.
        }
        assertEquals(listOf(KtorEngine.GRACE_MILLIS to KtorEngine.STOP_TIMEOUT_MILLIS, 0L to 0L), stops.calls.toList())
        assertFalse("it says it still listens after the forced stop", engine.listening)
        assertTrue("the probe says the port is still taken", PortProbe.isFree(port, loopback))
        // The handle is gone with the listener, so a rebind starts again.
        engine.start(MealPlannerServer.LOOPBACK, port) {}
        stops.failGraceful = false
        engine.stop()
        assertTrue(PortProbe.isFree(port, loopback))
    }

    @Test
    fun aListenerThatWontStopIsKeptToStopLater() {
        val port = freeLoopbackPort()
        val stops = FlakyStops().apply { failForced = true }
        val engine = flakyEngine(stops)
        engine.start(MealPlannerServer.LOOPBACK, port) {}
        try {
            try {
                engine.stop()
                fail("the stop's failure was swallowed")
            } catch (e: IllegalStateException) {
                // Neither stop worked: the listener runs on, and the engine still holds it.
            }
            assertTrue("it says it stopped a listener that runs on", engine.listening)
            try {
                engine.start(MealPlannerServer.LOOPBACK, port) {}
                fail("a second listener started beside the first")
            } catch (e: IllegalStateException) {
                // check(server == null): the handle was kept.
            }
        } finally {
            stops.failGraceful = false
            stops.failForced = false
            engine.stop()
        }
        assertTrue("the probe says the port is still taken", PortProbe.isFree(port, loopback))
    }

    @Test
    fun aPortThatServedARequestIsFreeAgainAfterStop() {
        // A rebind (an Alexa token set up) stops and starts on the same port, after the extension has called it.
        val port = freeLoopbackPort()
        val engine = loopbackEngine()
        engine.start(MealPlannerServer.LOOPBACK, port) { ServerRoutes().install(this) }
        try {
            assertEquals(200, healthz(port).statusCode())
            engine.stop()
            assertTrue("the probe says the port is still taken", PortProbe.isFree(port, loopback))
            engine.start(MealPlannerServer.LOOPBACK, port) { ServerRoutes().install(this) }
            assertEquals(200, healthz(port).statusCode())
        } finally {
            engine.stop()
        }
    }
}
