package com.naeblis11.mealplanner.desktop

import com.naeblis11.mealplanner.desktop.peers.FakeDiscovery
import com.naeblis11.mealplanner.desktop.peers.FakeNetwork
import com.naeblis11.mealplanner.desktop.peers.PeerRecord
import com.naeblis11.mealplanner.desktop.peers.PeerWatch
import com.naeblis11.mealplanner.desktop.server.ApiToken
import com.naeblis11.mealplanner.desktop.server.FakeEngine
import com.naeblis11.mealplanner.desktop.server.MealPlannerServer
import com.naeblis11.mealplanner.desktop.server.SecretsFile
import com.naeblis11.mealplanner.desktop.server.ServerRoutes
import com.naeblis11.mealplanner.desktop.server.eventually
import com.naeblis11.mealplanner.settings.ServerState
import com.naeblis11.mealplanner.settings.ServerStatus
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** P6-R8 end to end, wired as Main wires it: an older household's PC keeps this PC's server off the home network, until it goes. */
class DesktopYieldTest {
    private val dir: File = Files.createTempDirectory("mp-yield").toFile()
    private val engine = FakeEngine()
    private val network = FakeNetwork()
    private val older = PeerRecord("DEN", "0".repeat(16), 1L, "f".repeat(32), "1.0")

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun listening(app: DesktopApp, onLan: Boolean) =
        app.server!!.status.value == ServerStatus(ServerState.LISTENING, 5055, onLan = onLan, tokenConfigured = true)

    @Test
    fun anOlderHouseholdKeepsAlexaOffTheHomeNetworkUntilItGoes() {
        val secrets = File(dir, ".env").apply { writeText("MEAL_PLANNER_API_TOKEN=${"t".repeat(32)}\n") }
        network.add(older)
        val app = DesktopApp(
            dir,
            settingsFactory = { MapSettings() },
            serverFactory = { desktop ->
                MealPlannerServer(
                    ApiToken(SecretsFile(secrets), log = {}),
                    ServerRoutes(),
                    engine,
                    port = 5055,
                    log = {},
                    lanAllowed = { desktop.peers?.yieldTo() == null },
                )
            },
            peersFactory = { desktop ->
                PeerWatch(
                    FakeDiscovery(network),
                    own = { desktop.identity.record("THIS-PC", "test") },
                    port = 5055,
                    onYieldChanged = { desktop.server?.rebind() },
                    log = {},
                )
            },
        )
        try {
            runBlocking { app.start().join() }
            // The server starts as usual, then the first look finds the older household and moves it onto this PC.
            // P6-PF2: wait on the server's status, which changes after the engine has recorded the host.
            eventually { app.peers!!.yieldTo() == "DEN" && listening(app, onLan = false) }
            assertEquals(listOf("0.0.0.0", "127.0.0.1"), engine.startedHosts())

            network.clearAdded()
            // Asked again on each poll: a request that arrives while the first look is still finishing is dropped.
            eventually {
                app.peers!!.browseNow()
                app.peers!!.yieldTo() == null && listening(app, onLan = true)
            }
            assertEquals(listOf("0.0.0.0", "127.0.0.1", "0.0.0.0"), engine.startedHosts())
        } finally {
            app.close()
        }
    }
}
