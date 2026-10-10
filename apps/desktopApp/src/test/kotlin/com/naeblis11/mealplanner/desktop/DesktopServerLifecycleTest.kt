package com.naeblis11.mealplanner.desktop

import com.naeblis11.mealplanner.desktop.server.AppServer
import com.naeblis11.mealplanner.settings.ServerState
import com.naeblis11.mealplanner.settings.ServerStatus
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P4-R1: the server starts after the startup sync and stops while the database is still open; its failure stops nothing else. */
class DesktopServerLifecycleTest {
    private val dir: File = Files.createTempDirectory("mp-server-life").toFile()
    private var recorder: RecordingServer? = null

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private class RecordingServer(private val app: DesktopApp, private val failStart: Boolean) : AppServer {
        override val status = MutableStateFlow(ServerStatus(ServerState.STARTING, 5000))

        @Volatile
        var indexedAtStart: List<String>? = null

        @Volatile
        var databaseOpenAtStop: Boolean? = null

        override fun start(scope: CoroutineScope) {
            if (failStart) throw IllegalStateException("no server today")
            indexedAtStart = runBlocking { app.container.recipes.allRecipes().map { it.name } }
        }

        override fun rebind() {}

        override fun stop() {
            databaseOpenAtStop = try {
                runBlocking { app.container.database.appMetaDao().get("anything") }
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun app(failStart: Boolean = false) = DesktopApp(
        dir,
        settingsFactory = { MapSettings() },
        serverFactory = { desktop -> RecordingServer(desktop, failStart).also { recorder = it } },
    )

    @Test
    fun theServerStartsOnceTheFolderIsIndexed() {
        File(dir, "recipes").mkdirs()
        File(dir, "recipes/soup.yaml").writeText(
            "recipe_name: Soup\ningredients:\n- Salt:\n    amounts:\n    - amount: 1\n      unit: tsp\nsteps:\n- step: Stir.\n",
        )
        val app = app()
        try {
            runBlocking { app.start().join() }
            assertEquals(listOf("Soup"), recorder!!.indexedAtStart)
        } finally {
            app.close()
        }
    }

    @Test
    fun closeStopsTheServerWhileTheDatabaseIsStillOpen() {
        val app = app()
        runBlocking { app.start().join() }
        assertTrue(app.close())
        assertEquals(true, recorder!!.databaseOpenAtStop)
    }

    @Test
    fun aServerThatWontStartLeavesTheRestOfStartupRunning() {
        val app = app(failStart = true)
        try {
            runBlocking { app.start().join() }
            assertTrue(app.isWatching)
        } finally {
            app.close()
        }
    }
}
