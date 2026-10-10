package com.naeblis11.mealplanner.desktop

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Window close while a save in the UI holds RecipeRepository's write lock and a watcher sync waits
 * behind it. The save resumes on the UI thread to let go of the lock, so a close that blocks that
 * thread waiting for the sync would never return. The settings are MapSettings: the default PreferencesStore is the
 * registry (HKCU\Software\JavaSoft\Prefs), which no test may touch.
 */
class DesktopShutdownTest {
    private val dir: File = Files.createTempDirectory("mp-shutdown").toFile()

    // The save's file step waits here, holding the write lock.
    private val gate = CountDownLatch(1)
    private val trashing = CountDownLatch(1)

    // The UI thread: one thread, as Swing's.
    private val uiThread = Executors.newSingleThreadExecutor { Thread(it, "test-ui").apply { isDaemon = true } }
    private val ui = uiThread.asCoroutineDispatcher()

    private val realErr = System.err
    private val err = ByteArrayOutputStream()
    private var app: DesktopApp? = null

    init {
        System.setErr(
            PrintStream(
                object : OutputStream() {
                    override fun write(b: Int) {
                        err.write(b)
                        realErr.write(b)
                    }
                },
                true,
            ),
        )
    }

    @After
    fun tearDown() {
        gate.countDown()
        app?.close()
        uiThread.shutdownNow()
        System.setErr(realErr)
        dir.deleteRecursively()
    }

    private fun recipe(name: String) =
        "recipe_name: $name\ningredients:\n- Salt:\n    amounts:\n    - amount: 1\n      unit: tsp\nsteps:\n- step: Stir.\n"

    // A started app with a delete (launched on [saveOn]) holding the write lock, and a watcher sync queued behind it.
    private fun lockedApp(saveOn: CoroutineDispatcher, closeTimeoutMillis: Long = DesktopApp.CLOSE_TIMEOUT_MILLIS): DesktopApp {
        val started = DesktopApp(
            dir,
            settingsFactory = { MapSettings() },
            moveToTrash = { file ->
                trashing.countDown()
                gate.await()
                file.delete()
            },
            closeTimeoutMillis = closeTimeoutMillis,
        )
        app = started
        File(started.recipesDir, "soup.yaml").writeText(recipe("Soup"))
        runBlocking { started.start().join() }
        val id = runBlocking { started.container.recipes.allRecipes().single().id }
        CoroutineScope(saveOn).launch { started.container.recipes.delete(id) }
        assertTrue(trashing.await(10, TimeUnit.SECONDS))
        // A change made outside the app: after the debounce, the watcher's sync waits for the lock.
        File(started.recipesDir, "stew.yaml").writeText(recipe("Stew"))
        runBlocking { delay(1_500) }
        return started
    }

    @Test
    fun closingTheWindowNeverBlocksTheUiThreadASaveNeeds() {
        val app = lockedApp(saveOn = ui)
        // onCloseRequest runs on the UI thread.
        val closed = CompletableDeferred<Boolean>()
        CoroutineScope(ui).launch { closed.complete(app.shutdown()) }
        runBlocking { delay(300) }
        gate.countDown()
        assertTrue(runBlocking { withTimeout(10_000) { closed.await() } })
        // The queued sync ran to the end (it gave stew.yaml its id) before the database closed.
        assertTrue(File(app.recipesDir, "stew.yaml").readText().startsWith("recipe_uuid: "))
        assertFalse(err.toString(), err.toString().contains("sync failed"))
    }

    @Test
    fun aSyncThatCannotFinishBoundsTheCloseAndLeavesTheDatabaseOpen() {
        val app = lockedApp(saveOn = Dispatchers.IO, closeTimeoutMillis = 1_000)
        val closer = Executors.newSingleThreadExecutor()
        try {
            // An unbounded wait would never return while the save holds the lock.
            assertFalse(closer.submit<Boolean> { app.close() }.get(10, TimeUnit.SECONDS))
        } finally {
            closer.shutdownNow()
        }
        // Not closed under the waiting sync: once the save lets go, the sync finishes against an open database.
        gate.countDown()
        runBlocking {
            withTimeout(10_000) { while (app.container.recipes.allRecipes().none { it.name == "Stew" }) delay(50) }
        }
        assertFalse(err.toString(), err.toString().contains("sync failed"))
        // A later close finishes the job.
        assertTrue(app.close())
    }
}
