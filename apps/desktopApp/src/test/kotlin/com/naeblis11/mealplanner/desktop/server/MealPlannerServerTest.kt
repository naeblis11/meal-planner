package com.naeblis11.mealplanner.desktop.server

import com.naeblis11.mealplanner.settings.ServerState
import com.naeblis11.mealplanner.settings.ServerStatus
import java.io.File
import java.net.BindException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P4-R1: where the server listens, a port in use, and the token kept out of the log. No sockets: FakeEngine. */
class MealPlannerServerTest {
    private val dir: File = Files.createTempDirectory("mp-server").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine = FakeEngine()
    private val logs = CopyOnWriteArrayList<String>()

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun token(value: String? = null): ApiToken {
        val file = File(dir, ".env")
        if (value != null) file.writeText("MEAL_PLANNER_API_TOKEN=$value\n")
        return ApiToken(SecretsFile(file), log = { logs += it })
    }

    private fun server(token: ApiToken, retryMillis: Long = 200, engine: ServerEngine = this.engine) =
        MealPlannerServer(token, ServerRoutes(), engine, retryMillis = retryMillis, log = { logs += it })

    private fun inUse() = PortInUseException(MealPlannerServer.PORT, BindException("Address already in use"))

    @Test
    fun whileAnotherPcAnswersAlexaATokenListensOnThisPcOnly() {
        var allowed = false
        val server = MealPlannerServer(token("t".repeat(20)), ServerRoutes(), engine, log = { logs += it }, lanAllowed = { allowed })
        server.start(scope)
        assertEquals(listOf(MealPlannerServer.LOOPBACK), engine.startedHosts())
        assertEquals(ServerStatus(ServerState.LISTENING, MealPlannerServer.PORT, onLan = false, tokenConfigured = true), server.status.value)
        assertTrue(logs.any { it.contains("on this PC only (another Meal Planner PC on the network answers Alexa)") })

        allowed = true
        server.rebind()
        assertEquals(listOf(MealPlannerServer.LOOPBACK, MealPlannerServer.ALL_INTERFACES), engine.startedHosts())
        assertEquals(ServerStatus(ServerState.LISTENING, MealPlannerServer.PORT, onLan = true, tokenConfigured = true), server.status.value)
    }

    @Test
    fun anOlderPcSeenAfterTheFirstBindMovesATokenOntoThisPcOnly() {
        // The first bind comes before the first look, so a token starts on the home network and the yield moves it.
        var allowed = true
        val server = MealPlannerServer(token("t".repeat(20)), ServerRoutes(), engine, log = { logs += it }, lanAllowed = { allowed })
        server.start(scope)
        assertEquals(ServerStatus(ServerState.LISTENING, MealPlannerServer.PORT, onLan = true, tokenConfigured = true), server.status.value)

        allowed = false
        server.rebind()
        assertEquals(listOf(MealPlannerServer.ALL_INTERFACES, MealPlannerServer.LOOPBACK), engine.startedHosts())
        assertEquals(ServerStatus(ServerState.LISTENING, MealPlannerServer.PORT, onLan = false, tokenConfigured = true), server.status.value)
    }

    @Test
    fun aYieldWhileThePortIsInUseCancelsTheRetryAndBindsThisPcOnly() {
        var allowed = true
        engine.failNext(inUse())
        val server = MealPlannerServer(
            token("t".repeat(20)),
            ServerRoutes(),
            engine,
            retryMillis = 60_000,
            log = { logs += it },
            lanAllowed = { allowed },
        )
        server.start(scope)
        assertEquals(ServerState.PORT_IN_USE, server.status.value.state)
        val jobs = scope.coroutineContext[Job]!!
        assertEquals(1, jobs.children.count())

        allowed = false
        server.rebind()
        assertEquals(listOf(MealPlannerServer.LOOPBACK), engine.startedHosts())
        assertEquals(ServerStatus(ServerState.LISTENING, MealPlannerServer.PORT, onLan = false, tokenConfigured = true), server.status.value)
        // The pending retry is gone, so nothing binds the home network a minute later.
        eventually { jobs.children.none() }
    }

    @Test
    fun aRebindSaysStartingWhileTheOldListenerIsStillStopping() {
        // Settings must not say "on your home network" beside the yield for the whole of a slow stop.
        var allowed = true
        val server = MealPlannerServer(token("t".repeat(20)), ServerRoutes(), engine, log = { logs += it }, lanAllowed = { allowed })
        server.start(scope)
        val gate = CountDownLatch(1)
        engine.stopGate = gate
        allowed = false
        val rebinding = thread { server.rebind() }
        try {
            assertTrue("the stop never started", engine.stopEntered.await(5, TimeUnit.SECONDS))
            assertEquals(ServerState.STARTING, server.status.value.state)
        } finally {
            gate.countDown()
            rebinding.join(5_000)
        }
        assertEquals(ServerStatus(ServerState.LISTENING, MealPlannerServer.PORT, onLan = false, tokenConfigured = true), server.status.value)
    }

    @Test
    fun aStopThatFailsIsSaidByItsClassAndStillStops() {
        val secret = "t".repeat(20)
        val server = MealPlannerServer(token(secret), ServerRoutes(), engine, log = { logs += it })
        server.start(scope)
        engine.stopFailure = IllegalStateException("couldn't stop $secret")
        server.stop()
        assertEquals(ServerState.STOPPED, server.status.value.state)
        assertTrue(logs.any { it.contains("IllegalStateException") })
        assertFalse(logs.any { it.contains(secret) || it.contains("couldn't stop") })
    }

    @Test
    fun aStopThatFailsSaysTheServerIsStillOnTheHomeNetwork() {
        // The old listener runs on: binding again beside it can only fail, and FAILED is never retried. Settings says
        // where it really listens, and the move is tried again later (the next test).
        var allowed = true
        val secret = "t".repeat(20)
        val server = MealPlannerServer(token(secret), ServerRoutes(), engine, retryMillis = 60_000, log = { logs += it }, lanAllowed = { allowed })
        server.start(scope)
        engine.stopFailure = IllegalStateException("couldn't stop $secret")

        allowed = false
        server.rebind()
        assertTrue(engine.listening)
        assertEquals(listOf(MealPlannerServer.ALL_INTERFACES), engine.startedHosts())
        assertEquals(1, engine.attempts())
        assertEquals(ServerStatus(ServerState.LISTENING, MealPlannerServer.PORT, onLan = true, tokenConfigured = true), server.status.value)
        assertTrue(logs.any { it.contains("still listening on the home network") })
        // The class only: an exception's message is not ours to vouch for.
        assertTrue(logs.any { it.contains("IllegalStateException") })
        assertFalse(logs.any { it.contains(secret) || it.contains("couldn't stop") })
    }

    @Test
    fun aStopThatKeepsFailingIsTriedAgainUntilAYieldedPcIsOnThisPcOnly() {
        var allowed = true
        val server = MealPlannerServer(token("t".repeat(20)), ServerRoutes(), engine, retryMillis = 20, log = { logs += it }, lanAllowed = { allowed })
        server.start(scope)
        engine.stopFailure = IllegalStateException("stop failed")

        allowed = false
        server.rebind()
        // The rebind's stop and a retry's both fail: still on the home network, and said so once.
        eventually { engine.stops() >= 2 }
        assertEquals(ServerStatus(ServerState.LISTENING, MealPlannerServer.PORT, onLan = true, tokenConfigured = true), server.status.value)
        assertEquals(listOf(MealPlannerServer.ALL_INTERFACES), engine.startedHosts())
        assertEquals(1, logs.count { it.contains("still listening on the home network") })

        engine.stopFailure = null
        eventually { server.status.value.state == ServerState.LISTENING && !server.status.value.onLan }
        assertEquals(listOf(MealPlannerServer.ALL_INTERFACES, MealPlannerServer.LOOPBACK), engine.startedHosts())
        assertEquals(ServerStatus(ServerState.LISTENING, MealPlannerServer.PORT, onLan = false, tokenConfigured = true), server.status.value)
        // Settled: no loop left to bind anything later.
        val jobs = scope.coroutineContext[Job]!!
        eventually { jobs.children.none() }
        assertTrue(logs.any { it.contains("listening on port ${MealPlannerServer.PORT}, on this PC only (another Meal Planner PC") })
    }

    @Test
    fun withoutATokenTheYieldChangesNothing() {
        val server = MealPlannerServer(token(), ServerRoutes(), engine, log = { logs += it }, lanAllowed = { false })
        server.start(scope)
        assertEquals(listOf(MealPlannerServer.LOOPBACK), engine.startedHosts())
        assertEquals(ServerStatus(ServerState.LISTENING, MealPlannerServer.PORT), server.status.value)
        assertFalse(logs.any { it.contains("another Meal Planner PC") })
    }

    @Test
    fun thePortIsTheMealplannerPortPropertyElse5000() {
        // The preview (Gradle's run) sets 5055, so it never takes the port of the user's running app or old server.
        assertEquals("mealplanner.port", MealPlannerServer.PORT_PROPERTY)
        assertEquals(5000, MealPlannerServer.PORT)
        assertEquals(MealPlannerServer.PORT, MealPlannerServer.portFrom(null))
        assertEquals(5055, MealPlannerServer.portFrom("5055"))
        assertEquals(5055, MealPlannerServer.portFrom(" 5055 "))
        for (unusable in listOf("", " ", "abc", "0", "-1", "65536", "5055x")) {
            assertEquals(unusable, MealPlannerServer.PORT, MealPlannerServer.portFrom(unusable))
        }
    }

    @Test
    fun itListensOnThePortItIsGiven() {
        val server = MealPlannerServer(token(), ServerRoutes(), engine, port = 5055, log = { logs += it })
        server.start(scope)
        assertEquals(ServerStatus(ServerState.LISTENING, 5055), server.status.value)
    }

    @Test
    fun withoutATokenItListensOnThisPcOnly() {
        val server = server(token())
        server.start(scope)
        assertEquals(listOf(MealPlannerServer.LOOPBACK), engine.startedHosts())
        assertEquals(ServerStatus(ServerState.LISTENING, MealPlannerServer.PORT), server.status.value)
    }

    @Test
    fun withATokenItListensOnTheHomeNetwork() {
        val server = server(token(LONG_ENOUGH))
        server.start(scope)
        assertEquals(listOf(MealPlannerServer.ALL_INTERFACES), engine.startedHosts())
        assertEquals(ServerStatus(ServerState.LISTENING, MealPlannerServer.PORT, onLan = true, tokenConfigured = true), server.status.value)
    }

    @Test
    fun aQuotedTokenListensOnTheHomeNetwork() {
        val server = server(token("\"$LONG_ENOUGH\""))
        server.start(scope)
        assertEquals(listOf(MealPlannerServer.ALL_INTERFACES), engine.startedHosts())
        assertEquals(ServerStatus(ServerState.LISTENING, MealPlannerServer.PORT, onLan = true, tokenConfigured = true), server.status.value)
    }

    @Test
    fun aTokenTooShortListensOnThisPcOnly() {
        // 15 characters bare, and 15 inside the quotes: neither counts, so the home network never sees the server.
        for (value in listOf(TOO_SHORT, "'$TOO_SHORT'")) {
            val engine = FakeEngine()
            val server = server(token(value), engine = engine)
            server.start(scope)
            assertEquals(value.length.toString(), listOf(MealPlannerServer.LOOPBACK), engine.startedHosts())
            assertEquals(value.length.toString(), ServerStatus(ServerState.LISTENING, MealPlannerServer.PORT), server.status.value)
            server.stop()
        }
    }

    @Test
    fun aPortInUseIsSaidOnceAndTriedAgainUntilItIsFree() {
        engine.failNext(inUse(), inUse())
        val server = server(token())
        server.start(scope)
        assertEquals(ServerState.PORT_IN_USE, server.status.value.state)
        eventually { server.status.value.state == ServerState.LISTENING }
        assertEquals(3, engine.attempts())
        assertEquals(listOf(MealPlannerServer.LOOPBACK), engine.startedHosts())
        assertEquals(1, logs.count { "is in use" in it })
    }

    @Test
    fun aPortHeldOnAnyInterfaceIsInUseAndCioNeverStarts() {
        // The old server on 0.0.0.0:5000 next to a 127.0.0.1 bind would split the callers between the two apps; the
        // probe (faked here: tests never bind 0.0.0.0) says taken, so CIO never starts. Said once over the retries.
        val port = freeLoopbackPort()
        val probed = CopyOnWriteArrayList<Int>()
        var installed = false
        val routes = ServerRoutes(listOf({ installed = true }))
        val engine = KtorEngine(portFree = { probed += it; false })
        val server = MealPlannerServer(token(), routes, engine, port = port, retryMillis = 20, log = { logs += it })
        server.start(scope)
        assertEquals(ServerStatus(ServerState.PORT_IN_USE, port), server.status.value)
        eventually { probed.size >= 3 }
        server.stop()
        assertTrue(probed.all { it == port })
        assertFalse("CIO installed the routes", installed)
        assertEquals(1, logs.count { "is in use" in it })
        assertEquals(0, logs.count { "listening" in it })
    }

    @Test
    fun aNewTokenMovesTheServerOntoTheHomeNetwork() {
        val token = token()
        val server = server(token)
        server.start(scope)
        token.create()
        server.rebind()
        assertEquals(listOf(MealPlannerServer.LOOPBACK, MealPlannerServer.ALL_INTERFACES), engine.startedHosts())
        assertEquals(1, engine.stops())
        assertEquals(ServerStatus(ServerState.LISTENING, MealPlannerServer.PORT, onLan = true, tokenConfigured = true), server.status.value)
    }

    @Test
    fun aRebindSaysStartingWhileItBindsAgain() {
        val token = token()
        val server = server(token)
        server.start(scope)
        val seen = CopyOnWriteArrayList<ServerState>()
        engine.onStart = { seen += server.status.value.state }
        token.create()
        server.rebind()
        assertEquals(listOf(ServerState.STARTING), seen.toList())
        assertEquals(ServerState.LISTENING, server.status.value.state)
    }

    @Test
    fun stopEndsTheRetriesForGood() {
        engine.failNext(*Array(1_000) { inUse() })
        val server = server(token(), retryMillis = 20)
        server.start(scope)
        eventually { engine.attempts() >= 3 }
        server.stop()
        val after = engine.attempts()
        Thread.sleep(200)
        server.start(scope)
        assertEquals(after, engine.attempts())
        assertEquals(ServerState.STOPPED, server.status.value.state)
    }

    @Test
    fun anotherFailureIsSaidAndNotRetried() {
        engine.failNext(IllegalStateException("the engine broke"))
        val server = server(token(), retryMillis = 20)
        server.start(scope)
        Thread.sleep(200)
        assertEquals(ServerState.FAILED, server.status.value.state)
        assertEquals(1, engine.attempts())
        assertTrue(logs.any { "couldn't start" in it })
    }

    @Test
    fun theTokenIsNeverLogged() {
        engine.failNext(inUse())
        val token = token()
        val server = server(token, retryMillis = 20)
        server.start(scope)
        val made = token.create()
        server.rebind()
        server.stop()
        assertTrue(logs.isNotEmpty())
        // The message never quotes the log, so a failure can't print the token either.
        assertTrue("a log line shows the token", logs.none { made in it })
    }

    @Test
    fun aTokenTooShortIsNeverLogged() {
        val server = server(token(TOO_SHORT))
        server.start(scope)
        server.stop()
        assertTrue(logs.isNotEmpty())
        assertTrue("a log line shows the too-short token", logs.none { TOO_SHORT in it })
    }

    private companion object {
        /** 16 characters: the shortest token that counts. */
        const val LONG_ENOUGH = "abcdefghijklmnop"

        /** 15 characters: one short. */
        const val TOO_SHORT = "short-secret-15"
    }
}
