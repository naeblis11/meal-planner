package com.naeblis11.mealplanner.folder

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RecipeFolderWatcherTest {
    private val dir: File = Files.createTempDirectory("mp-watch").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val calls = AtomicInteger()
    private var watcher: RecipeFolderWatcher? = null

    @After
    fun tearDown() {
        watcher?.close()
        scope.cancel()
        dir.deleteRecursively()
    }

    // A wide debounce keeps the "exactly N calls" tests steady when Windows delivers events late.
    private fun watch(
        debounceMillis: Long = 300,
        onChange: suspend () -> Unit = { calls.incrementAndGet() },
    ) {
        watcher = RecipeFolderWatcher(dir.toPath(), debounceMillis = debounceMillis, onChange = onChange).start(scope)
    }

    private fun awaitCalls(n: Int) = runBlocking { withTimeout(10_000) { while (calls.get() < n) delay(20) } }

    private fun awaitUntil(condition: () -> Boolean) =
        runBlocking { withTimeout(10_000) { while (!condition()) delay(20) } }

    private fun pause(millis: Long) = runBlocking { delay(millis) }

    @Test
    fun aBurstOfChangesIsOneSync() {
        watch(debounceMillis = 700)
        repeat(5) { File(dir, "r$it.yaml").writeText("recipe_name: R$it\n") }
        awaitCalls(1)
        pause(1_500)
        assertEquals(1, calls.get())
    }

    @Test
    fun changesSeparatedByAQuietSpellAreTwoSyncs() {
        watch()
        File(dir, "a.yaml").writeText("recipe_name: A\n")
        awaitCalls(1)
        File(dir, "a.yaml").writeText("recipe_name: A2\n")
        awaitCalls(2)
    }

    @Test
    fun afterCloseChangesAreIgnored() {
        watch()
        watcher!!.close()
        File(dir, "a.yaml").writeText("recipe_name: A\n")
        pause(1_000)
        assertEquals(0, calls.get())
    }

    @Test
    fun aFailingSyncDoesNotStopTheWatcher() {
        watch { if (calls.incrementAndGet() == 1) throw IOException("disk went away") }
        File(dir, "a.yaml").writeText("recipe_name: A\n")
        awaitCalls(1)
        File(dir, "b.yaml").writeText("recipe_name: B\n")
        awaitCalls(2)
    }

    @Test
    fun aVanishedFolderIsReportedOnceAndWatchingStops() {
        watch(debounceMillis = 700)
        assertTrue(watcher!!.isWatching)
        assertEquals(true, dir.deleteRecursively())
        // The folder going away is itself a change worth one sync, so the library can report it.
        awaitCalls(1)
        // After that last round the watcher cleans itself up and says so.
        awaitUntil { !watcher!!.isWatching }
        pause(1_500)
        assertEquals(1, calls.get())
    }

    @Test
    fun cancellingItsScopeWithoutCloseStillReleasesTheWatcher() {
        val before = watcherThreads()
        watch()
        val mine = watcherThreads() - before
        assertEquals(1, mine.size)
        scope.cancel()
        // The pump thread ends only when the watch service is closed.
        awaitUntil { mine.none { it.isAlive } }
        assertFalse(watcher!!.isWatching)
    }

    private fun watcherThreads(): Set<Thread> =
        Thread.getAllStackTraces().keys.filterTo(HashSet()) { it.name == "recipe-folder-watcher" && it.isAlive }

    @Test
    fun closeIsIdempotent() {
        watch()
        watcher!!.close()
        watcher!!.close()
        assertFalse(watcher!!.isWatching)
    }

    @Test
    fun aMissingFolderFailsTheConstructor() {
        val missing = File(dir, "nope").toPath()
        try {
            RecipeFolderWatcher(missing, onChange = {})
            fail("expected NoSuchFileException")
        } catch (expected: NoSuchFileException) {
            // The watch service the constructor opened was closed again before the rethrow.
        }
    }

    @Test
    fun aSecondStartThrows() {
        watch()
        try {
            watcher!!.start(scope)
            fail("expected IllegalStateException")
        } catch (expected: IllegalStateException) {
            // Still watching normally.
        }
        assertTrue(watcher!!.isWatching)
    }

    @Test
    fun closeAndJoinWaitsForARunningSync() {
        val finished = AtomicBoolean(false)
        watch {
            calls.incrementAndGet()
            delay(800)
            finished.set(true)
        }
        File(dir, "a.yaml").writeText("recipe_name: A\n")
        awaitCalls(1)
        assertFalse(finished.get())
        runBlocking { watcher!!.closeAndJoin() }
        assertTrue(finished.get())
        assertFalse(watcher!!.isWatching)
    }

    @Test
    fun aChangeDuringASyncLeavesExactlyOneFollowUp() {
        val release = CountDownLatch(1)
        watch(debounceMillis = 700) {
            if (calls.incrementAndGet() == 1) release.await(10, TimeUnit.SECONDS)
        }
        File(dir, "a.yaml").writeText("recipe_name: A\n")
        awaitCalls(1)
        // The first sync is now blocked; two more changes arrive meanwhile.
        File(dir, "b.yaml").writeText("recipe_name: B\n")
        File(dir, "c.yaml").writeText("recipe_name: C\n")
        pause(500)
        release.countDown()
        awaitCalls(2)
        pause(1_500)
        assertEquals(2, calls.get())
    }
}
