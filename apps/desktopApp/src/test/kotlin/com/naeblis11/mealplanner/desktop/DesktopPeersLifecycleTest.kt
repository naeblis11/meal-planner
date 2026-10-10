package com.naeblis11.mealplanner.desktop

import com.naeblis11.mealplanner.data.AppMetaEntity
import com.naeblis11.mealplanner.data.Household
import com.naeblis11.mealplanner.desktop.peers.FakeDiscovery
import com.naeblis11.mealplanner.desktop.peers.FakeNetwork
import com.naeblis11.mealplanner.desktop.peers.PeerIdentity
import com.naeblis11.mealplanner.desktop.peers.PeerTxt
import com.naeblis11.mealplanner.desktop.peers.PeerWatch
import com.naeblis11.mealplanner.desktop.server.AppServer
import com.naeblis11.mealplanner.desktop.server.eventually
import com.naeblis11.mealplanner.settings.ServerState
import com.naeblis11.mealplanner.settings.ServerStatus
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P6-R6: discovery starts beside the server after the startup sync, never holds startup up, and closes after the server while the database is open. */
class DesktopPeersLifecycleTest {
    private val dir: File = Files.createTempDirectory("mp-peers-life").toFile()
    private val events = CopyOnWriteArrayList<String>()
    private val discovery = FakeDiscovery(FakeNetwork(), events = events)

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private inner class RecordingServer : AppServer {
        override val status = MutableStateFlow(ServerStatus(ServerState.STARTING, 5055))

        override fun start(scope: CoroutineScope) {
            events += "server started"
        }

        override fun rebind() {}

        override fun stop() {
            events += "server stopped"
        }
    }

    private fun app(closeTimeoutMillis: Long = DesktopApp.CLOSE_TIMEOUT_MILLIS) = DesktopApp(
        dir,
        closeTimeoutMillis = closeTimeoutMillis,
        settingsFactory = { MapSettings() },
        serverFactory = { RecordingServer() },
        peersFactory = { desktop -> PeerWatch(discovery, own = { desktop.identity.record("THIS-PC", "test") }, port = 5055, log = {}) },
    )

    @Test
    fun discoveryStartsAfterTheServerAndNeverHoldsStartupUp() {
        discovery.gate = CountDownLatch(1)
        val app = app()
        try {
            // Startup is done while the look is still under way.
            runBlocking { withTimeout(5_000) { app.start().join() } }
            assertTrue(discovery.browsing.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("server started", "announce", "browse"), events)
            val (record, port) = discovery.announced!!
            assertEquals(5055, port)
            assertEquals(PeerTxt.householdHash(runBlocking { app.identity.household.id() }), record.householdHash)
        } finally {
            discovery.gate?.countDown()
            app.close()
        }
    }

    @Test
    fun closeStopsDiscoveryAfterTheServerWhileTheDatabaseIsOpen() {
        val app = app()
        var databaseOpen: Boolean? = null
        discovery.onClose = {
            databaseOpen = try {
                runBlocking { app.container.database.appMetaDao().get("anything") }
                true
            } catch (e: Exception) {
                false
            }
        }
        runBlocking { app.start().join() }
        eventually { discovery.browses.get() == 1 }
        assertTrue(app.close())
        assertEquals(listOf("server started", "announce", "browse", "server stopped", "close"), events)
        assertEquals(true, databaseOpen)
    }

    @Test
    fun closeNeverWaitsOnALookUnderWay() {
        // The look's gate is never released by close and holds up to 5 s; close's own bound here is 2 s.
        val gate = CountDownLatch(1)
        discovery.gate = gate
        discovery.releaseOnClose = false
        val app = app(closeTimeoutMillis = 2_000)
        try {
            runBlocking { app.start().join() }
            assertTrue(discovery.browsing.await(5, TimeUnit.SECONDS))
            assertTrue(app.close())
            assertEquals(1L, gate.count)
        } finally {
            gate.countDown()
            app.close()
        }
    }

    @Test
    fun startupDatesAnOldHouseholdFromItsDatabaseFileBeforeAnythingSends() {
        // Carried from Task 1: GoogleCalendarSync's own Household(db) would date it "now", so start() settles it first,
        // with discovery or without it.
        val app = DesktopApp(dir, settingsFactory = { MapSettings() })
        try {
            val meta = app.container.database.appMetaDao()
            runBlocking { meta.put(AppMetaEntity(Household.KEY, "000102030405060708090a0b0c0d0e0f")) }
            runBlocking { app.start().join() }
            val made = PeerIdentity.fileCreated(File(dir, DESKTOP_DB))!!
            assertEquals(made.toString(), runBlocking { meta.get(Household.CREATED_KEY) })
        } finally {
            app.close()
        }
    }

    @Test
    fun onlyTheInstalledAppOrAPreviewAskedToLooksForOtherPcs() {
        // P6-PF7: a preview's household predates any install, so a preview that always announced would make the
        // installed app step back from Google Calendar and Alexa.
        assertTrue(lookForPeers(installed = "true", peers = null))
        assertTrue(lookForPeers(installed = null, peers = "on"))
        assertFalse(lookForPeers(installed = null, peers = null))
        assertFalse(lookForPeers(installed = "false", peers = "off"))
        assertFalse(lookForPeers(installed = null, peers = "yes"))
    }

    @Test
    fun peersOffBeatsTheInstalledLauncher() {
        // P7-R6: the smoke run's throwaway household never announces itself. JAVA_TOOL_OPTIONS can add
        // -Dmealplanner.peers=off but can't override the launcher's -Dmealplanner.installed=true.
        assertFalse(lookForPeers(installed = "true", peers = "off"))
        assertFalse(lookForPeers(installed = "true", peers = "yes"))
        assertFalse(lookForPeers(installed = "true", peers = ""))
        assertTrue(lookForPeers(installed = "true", peers = "on"))
    }
}
