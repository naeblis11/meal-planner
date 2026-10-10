package com.naeblis11.mealplanner.desktop

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExitHookTest {
    private val dir: File = Files.createTempDirectory("mp-exit").toFile()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun theExitHookRunsTheBoundedClose() {
        val app = DesktopApp(dir, settingsFactory = { MapSettings() })
        File(app.recipesDir, "soup.yaml").writeText(
            "recipe_name: Soup\ningredients:\n- Salt:\n    amounts:\n    - amount: 1\n      unit: tsp\nsteps:\n- step: Stir.\n",
        )
        runBlocking { app.start().join() }
        assertTrue(app.isWatching)
        closeOnExit(app).run()
        assertFalse(app.isWatching)
        assertFalse(File(dir, "$DESKTOP_DB-wal").exists())
        // Quit's close afterwards finds it closed and does nothing.
        assertTrue(app.close())
    }

    @Test
    fun theExitHookAfterQuitDoesNothing() {
        val app = DesktopApp(dir, settingsFactory = { MapSettings() })
        runBlocking { app.start().join() }
        assertTrue(app.close())
        closeOnExit(app).run()
        assertTrue(app.close())
    }

    @Test
    fun quitTakesTheExitHookOffBeforeItExits() {
        val hook = Thread({}, "test-exit-hook")
        Runtime.getRuntime().addShutdownHook(hook)
        var exits = 0
        try {
            exitWithoutHook(hook) { exits++ }
            assertEquals(1, exits)
            // Already off: a Quit whose close timed out ends without the hook trying a second close.
            assertFalse(Runtime.getRuntime().removeShutdownHook(hook))
        } finally {
            Runtime.getRuntime().removeShutdownHook(hook)
        }
    }
}
