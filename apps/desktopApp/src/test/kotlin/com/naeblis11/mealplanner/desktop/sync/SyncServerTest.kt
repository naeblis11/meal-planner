package com.naeblis11.mealplanner.desktop.sync

import com.naeblis11.mealplanner.desktop.server.PortInUseException
import com.naeblis11.mealplanner.desktop.server.eventually
import com.naeblis11.mealplanner.settings.SyncServerState
import com.naeblis11.mealplanner.settings.SyncServerStatus
import com.naeblis11.mealplanner.sync.SyncErrors
import java.io.IOException
import java.net.BindException
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** LAN sync stage 2b (D7): the sync port opens only while wanted and not yielding; a port in use is retried. No sockets. */
class SyncServerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine = FakeSyncEngine()
    private val logs = CopyOnWriteArrayList<String>()

    @Volatile
    private var wanted = false

    @Volatile
    private var allowed = true

    private val handler = SyncHandler { SyncHttpResponse.error(404, SyncErrors.NOT_FOUND) }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun server(retryMillis: Long = 200, ssl: () -> SSLContext = { SSLContext.getDefault() }) =
        SyncServer(engine, ssl, handler, wanted = { wanted }, lanAllowed = { allowed }, retryMillis = retryMillis, log = { logs += it })

    private fun inUse() = PortInUseException(SyncServer.SYNC_PORT, BindException("Address already in use"))

    private fun status(state: SyncServerState) = SyncServerStatus(state, SyncServer.SYNC_PORT)

    @Test
    fun withNothingPairedAndNoWindowNothingListens() {
        val server = server()
        server.start(scope)
        assertEquals(status(SyncServerState.OFF), server.status.value)
        assertEquals(0, engine.attempts())
    }

    @Test
    fun wantedItListensOnTheHomeNetwork() {
        wanted = true
        val server = server()
        server.start(scope)
        assertEquals(listOf(SyncServer.ALL_INTERFACES), engine.startedHosts())
        assertEquals(status(SyncServerState.LISTENING), server.status.value)
    }

    @Test
    fun whileYieldingItStaysClosedAndOpensWhenTheYieldEnds() {
        wanted = true
        allowed = false
        val server = server()
        server.start(scope)
        assertEquals(status(SyncServerState.OFF), server.status.value)
        assertEquals(0, engine.attempts())
        allowed = true
        server.rebind()
        assertEquals(status(SyncServerState.LISTENING), server.status.value)
    }

    @Test
    fun noLongerWantedClosesIt() {
        wanted = true
        val server = server()
        server.start(scope)
        wanted = false
        server.rebind()
        assertEquals(status(SyncServerState.OFF), server.status.value)
        assertFalse(engine.listening)
        assertEquals(1, engine.stops())
    }

    @Test
    fun aRebindThatChangesNothingDoesNothing() {
        wanted = true
        val server = server()
        server.start(scope)
        server.rebind()
        server.rebind()
        assertEquals(1, engine.attempts())
        assertEquals(0, engine.stops())
    }

    @Test
    fun aPortInUseIsSaidOnceAndTriedAgainUntilFree() {
        wanted = true
        engine.failNext(inUse(), inUse())
        val server = server()
        server.start(scope)
        assertEquals(status(SyncServerState.PORT_IN_USE), server.status.value)
        eventually { server.status.value.state == SyncServerState.LISTENING }
        assertEquals(3, engine.attempts())
        assertEquals(1, logs.count { it.contains("is in use") })
        assertTrue(logs.toString(), logs.any { it.contains("free again") })
    }

    @Test
    fun noLongerWantedWhileThePortIsInUseStopsTheRetries() {
        wanted = true
        engine.failNext(inUse(), inUse(), inUse(), inUse(), inUse())
        val server = server(retryMillis = 100)
        server.start(scope)
        wanted = false
        server.rebind()
        assertEquals(status(SyncServerState.OFF), server.status.value)
        val attempts = engine.attempts()
        Thread.sleep(400)
        assertEquals(attempts, engine.attempts())
    }

    @Test
    fun stopClosesItForGood() {
        wanted = true
        val server = server()
        server.start(scope)
        server.stop()
        assertEquals(status(SyncServerState.OFF), server.status.value)
        assertFalse(engine.listening)
        server.rebind()
        assertEquals(1, engine.attempts())
    }

    @Test
    fun aCertificateThatCantBeHadFailsAndOnlyItsClassIsLogged() {
        wanted = true
        val server = server(ssl = { throw IOException("secret-detail") })
        server.start(scope)
        assertEquals(status(SyncServerState.FAILED), server.status.value)
        assertTrue(logs.toString(), logs.any { it.contains("IOException") })
        assertFalse(logs.toString(), logs.any { it.contains("secret-detail") })
    }

    @Test
    fun aStopThatFailsKeepsItListeningAndIsTriedAgain() {
        wanted = true
        val server = server(retryMillis = 100)
        server.start(scope)
        engine.stopFailure = IllegalStateException("held")
        wanted = false
        server.rebind()
        assertEquals(status(SyncServerState.LISTENING), server.status.value)
        engine.stopFailure = null
        eventually { server.status.value.state == SyncServerState.OFF }
        assertFalse(engine.listening)
    }

    @Test
    fun thePortComesFromItsPropertyOr5001() {
        assertEquals("mealplanner.syncPort", SyncServer.PORT_PROPERTY)
        assertEquals(5001, SyncServer.portFrom(null))
        assertEquals(5056, SyncServer.portFrom(" 5056 "))
        assertEquals(5001, SyncServer.portFrom("0"))
        assertEquals(5001, SyncServer.portFrom("70000"))
        assertEquals(5001, SyncServer.portFrom("x"))
    }

    @Test
    fun theSyncPortIsNeverTheServersAndNever5001UnlessThatIsTheDefault() {
        // 5000 is the built-in server's (the extension's and Alexa's): never the sync port.
        assertEquals(5001, SyncServer.portFrom("5000"))
        assertEquals(5001, SyncServer.portFrom("5001"))
        // With another default (a preview's), 5001 is the installed app's sync port and is refused too.
        assertEquals(5065, SyncServer.portFrom("5000", default = 5065))
        assertEquals(5065, SyncServer.portFrom("5001", default = 5065))
        assertEquals(5066, SyncServer.portFrom("5066", default = 5065))
    }
}
