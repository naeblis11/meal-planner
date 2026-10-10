package com.naeblis11.mealplanner.desktop

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.naeblis11.mealplanner.data.inTransaction
import com.naeblis11.mealplanner.folder.LIBRARY_BLOCKED_MESSAGE
import com.naeblis11.mealplanner.folder.LibraryBlockedException
import com.naeblis11.mealplanner.folder.missingFilesMessage
import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import java.util.prefs.Preferences
import kotlin.concurrent.thread
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopStartupTest {
    private val dir: File = Files.createTempDirectory("mp-startup").toFile()
    private val prefsNode = "com/naeblis11/mealplanner/test-${System.nanoTime()}"
    private val app = DesktopApp(dir, prefsNode)

    @After
    fun tearDown() {
        app.close()
        dir.deleteRecursively()
        Preferences.userRoot().node(prefsNode).removeNode()
    }

    private fun Thread.isParked() = state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING

    private fun awaitCondition(what: String, timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (!condition()) {
            assertTrue("Timed out waiting for $what", System.nanoTime() < deadline)
            LockSupport.parkNanos(2_000_000)
        }
    }

    private fun recipe(name: String) =
        "recipe_name: $name\ningredients:\n- Salt:\n    amounts:\n    - amount: 1\n      unit: tsp\nsteps:\n- step: Stir.\n"

    @Test
    fun startupIndexesTheFolderThenWatches() {
        File(app.recipesDir, "soup.yaml").writeText(recipe("Soup"))
        runBlocking {
            app.start().join()
            assertEquals(listOf("Soup"), app.container.recipes.allRecipes().map { it.name })

            File(app.recipesDir, "stew.yaml").writeText(recipe("Stew"))
            withTimeout(10_000) { while (app.container.recipes.allRecipes().size < 2) delay(50) }
        }
        assertTrue(File(app.recipesDir, "soup.yaml").readText().startsWith("recipe_uuid: "))
        assertTrue(File(dir, DESKTOP_DB).isFile)
    }

    @Test
    fun aRecipeFolderThatCantBeReadAtStartupIsListedAndNotWatched() {
        val other = Files.createTempDirectory("mp-startup-broken").toFile()
        val recipes = File(other, "recipes").apply { writeText("a file where the folder should be") }
        val broken = DesktopApp(other, prefsNode)
        try {
            runBlocking { broken.start().join() }
            assertEquals(listOf("Can't read the recipe folder: ${recipes.path}. Meal Planner will pick it up again when it is back."), broken.folder.problems.value.map { it.message })
            assertFalse(broken.isWatching)
        } finally {
            broken.close()
            other.deleteRecursively()
        }
    }

    @Test
    fun theOldServersDatabaseIsNeverRead() {
        // The Python server's database in the same folder: the desktop has its own and carries no import from it.
        File(app.recipesDir, "soup.yaml").writeText(recipe("Soup"))
        val oldDb = File(dir, "mealplanner.db")
        val server = BundledSQLiteDriver().open(oldDb.path)
        try {
            server.execSQL("CREATE TABLE shopping_list_item (id INTEGER PRIMARY KEY, name TEXT, checked INTEGER)")
            server.execSQL("INSERT INTO shopping_list_item (name, checked) VALUES ('Milk', 1)")
        } finally {
            server.close()
        }
        val before = oldDb.readBytes()
        runBlocking {
            app.start().join()
            assertEquals(listOf("Soup"), app.container.recipes.allRecipes().map { it.name })
            assertEquals(emptyList<String>(), app.container.database.shoppingDao().allInIdOrder().map { it.name })
            assertNull(app.container.database.appMetaDao().get("legacy_import"))
        }
        assertArrayEquals(before, oldDb.readBytes())
        assertEquals(listOf("mealplanner.db"), dir.list()!!.filter { it.startsWith("mealplanner.db") })
        assertTrue(app.isWatching)
    }

    @Test
    fun aFileSavedWhileTheStartupSyncRunsIsStillPickedUp() {
        File(app.recipesDir, "soup.yaml").writeText(recipe("Soup"))
        // Saved in an editor just after the startup sync looked at the folder, before watching would otherwise begin.
        app.afterStartupSync = { File(app.recipesDir, "stew.yaml").writeText(recipe("Stew")) }
        runBlocking {
            app.start().join()
            withTimeout(10_000) { while (app.container.recipes.allRecipes().size < 2) delay(50) }
        }
        assertTrue(app.isWatching)
    }

    @Test
    fun closeLetsASaveInProgressFinishBeforeTheDatabaseCloses() {
        val other = Files.createTempDirectory("mp-startup-drain").toFile()
        val inTrash = CountDownLatch(1)
        val release = CountDownLatch(1)
        // The Recycle Bin is slow: the delete holds the recipe lock while it waits.
        val slow = DesktopApp(other, prefsNode, moveToTrash = { file -> inTrash.countDown(); release.await(); file.delete() })
        try {
            File(slow.recipesDir, "soup.yaml").writeText(recipe("Soup"))
            runBlocking { slow.folder.sync() }
            val id = runBlocking { slow.container.recipes.allRecipes().single().id }
            var failure: Throwable? = null
            val deleting = thread { runBlocking { try { slow.container.recipes.delete(id) } catch (t: Throwable) { failure = t } } }
            assertTrue(inTrash.await(5, TimeUnit.SECONDS))
            thread { Thread.sleep(300); release.countDown() }

            assertTrue(slow.close())

            deleting.join(5_000)
            assertNull(failure)
            assertFalse(File(slow.recipesDir, "soup.yaml").exists())
        } finally {
            release.countDown()
            slow.close()
            other.deleteRecursively()
        }
    }

    @Test
    fun closeLetsAShoppingWriteInProgressFinishBeforeTheDatabaseCloses() {
        assertCloseWaitsFor(
            name = "mp-startup-drain-shopping",
            prepare = {},
            write = { app, _ -> app.container.shopping.addItem("Milk") },
            verify = { app -> assertEquals(listOf("Milk"), runBlocking { app.container.database.shoppingDao().allInIdOrder().map { it.name } }) },
        )
    }

    @Test
    fun closeLetsAMealPlanWriteInProgressFinishBeforeTheDatabaseCloses() {
        val monday = LocalDate.of(2026, 10, 5)
        assertCloseWaitsFor(
            name = "mp-startup-drain-plan",
            prepare = { app ->
                File(app.recipesDir, "soup.yaml").writeText(recipe("Soup"))
                runBlocking { app.folder.sync() }
                runBlocking { app.container.recipes.allRecipes().single().id }
            },
            write = { app, soup -> app.container.plans.assign(monday, "Dinner", soup, null) },
            verify = { app -> assertEquals("Soup", runBlocking { app.container.plans.assignment(monday, "Dinner") }?.recipeName) },
        )
    }

    /**
     * A write that has taken its repository's lock and waits for the database's one writer must be finished by
     * [DesktopApp.close], not cut off by the database closing under it. Nothing here sleeps: each step waits on a
     * condition. The held writer is released only once close() is seen parked (waiting on the write) or finished,
     * then the write must not have failed, close() must have succeeded and a second app on the same folder must
     * find the write saved.
     */
    private fun <T> assertCloseWaitsFor(
        name: String,
        prepare: (DesktopApp) -> T,
        write: suspend (DesktopApp, T) -> Unit,
        verify: (DesktopApp) -> Unit,
    ) {
        val other = Files.createTempDirectory(name).toFile()
        val inTransaction = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = DesktopApp(other, settingsFactory = { MapSettings() })
        try {
            val prepared = prepare(first)
            // Something else holds the database's one writer, so the write has taken its lock and waits.
            val holding = thread { runBlocking { first.container.database.inTransaction { inTransaction.countDown(); release.await() } } }
            assertTrue(inTransaction.await(5, TimeUnit.SECONDS))
            val failure = AtomicReference<Throwable?>(null)
            val writing = thread { runBlocking { try { write(first, prepared) } catch (t: Throwable) { failure.set(t) } } }
            awaitCondition("the write to take its lock and wait") { writing.isParked() }

            val closed = AtomicReference<Boolean?>(null)
            val closing = thread { closed.set(first.close()) }
            var parkedPolls = 0
            awaitCondition("close() to wait on the write") {
                parkedPolls = if (closing.isParked()) parkedPolls + 1 else 0
                parkedPolls >= 3 || !closing.isAlive
            }
            release.countDown()

            closing.join(10_000)
            assertEquals(true, closed.get())
            writing.join(5_000)
            holding.join(5_000)
            assertNull(failure.get())
        } finally {
            release.countDown()
            first.close()
        }
        val second = DesktopApp(other, settingsFactory = { MapSettings() })
        try {
            verify(second)
        } finally {
            second.close()
            other.deleteRecursively()
        }
    }

    @Test
    fun aRecipeFolderMissingAtStartupIsListedNotRecreatedAndNotWatched() {
        val other = Files.createTempDirectory("mp-startup-missing").toFile()
        try {
            val first = DesktopApp(other, prefsNode)
            File(first.recipesDir, "soup.yaml").writeText(recipe("Soup"))
            runBlocking { first.start().join() }
            first.close()
            // The folder goes away between runs (deleted, or an offline Documents folder).
            assertTrue(first.recipesDir.deleteRecursively())

            val second = DesktopApp(other, prefsNode)
            try {
                runBlocking {
                    second.start().join()
                    // Not unindexed: an empty folder made in its place would have taken every recipe away.
                    assertEquals(listOf("Soup"), second.container.recipes.allRecipes().map { it.name })
                }
                assertFalse(second.recipesDir.exists())
                assertFalse(second.isWatching)
                assertEquals(
                    listOf("The recipe folder is missing: ${second.recipesDir.path}. Meal Planner will pick it up again when it is back."),
                    second.folder.problems.value.map { it.message },
                )
            } finally {
                second.close()
            }
        } finally {
            other.deleteRecursively()
        }
    }

    @Test
    fun closingDuringStartupStopsEverythingAndCanBeRepeated() {
        repeat(30) { File(app.recipesDir, "r$it.yaml").writeText(recipe("R$it")) }
        val startup = app.start()
        app.close()
        assertTrue(startup.isCompleted)
        assertFalse(app.isWatching)
        app.close()
    }

    @Test
    fun aRecipeFolderThatComesBackIsWatchedAgain() {
        val other = Files.createTempDirectory("mp-startup-rearm").toFile()
        try {
            val first = DesktopApp(other, settingsFactory = { MapSettings() })
            File(first.recipesDir, "soup.yaml").writeText(recipe("Soup"))
            runBlocking { first.start().join() }
            assertTrue(first.close())
            // The folder goes away between runs; its file (with the recipe_uuid the first sync gave it) is kept aside.
            val soup = File(first.recipesDir, "soup.yaml").readBytes()
            assertTrue(first.recipesDir.deleteRecursively())

            val second = DesktopApp(other, settingsFactory = { MapSettings() }, rearmPollMillis = 50)
            try {
                runBlocking {
                    second.start().join()
                    assertFalse(second.isWatching)
                    // Back in one move (as from the Recycle Bin), with a recipe added meanwhile.
                    val staging = File(other, "recipes-back").apply { mkdirs() }
                    File(staging, "soup.yaml").writeBytes(soup)
                    File(staging, "stew.yaml").writeText(recipe("Stew"))
                    assertTrue(staging.renameTo(second.recipesDir))
                    withTimeout(10_000) { while (!second.isWatching) delay(20) }
                    withTimeout(10_000) { while (second.container.recipes.allRecipes().size < 2) delay(50) }
                    assertEquals(emptyList<String>(), second.folder.problems.value.map { it.message })
                    // Watched again: a hand edit from now on is picked up.
                    File(second.recipesDir, "pie.yaml").writeText(recipe("Pie"))
                    withTimeout(10_000) { while (second.container.recipes.allRecipes().size < 3) delay(50) }
                }
                assertEquals(listOf("Pie", "Soup", "Stew"), runBlocking { second.container.recipes.allRecipes().map { it.name } })
            } finally {
                second.close()
            }
        } finally {
            other.deleteRecursively()
        }
    }

    @Test
    fun aFolderMadeByTheStartupSyncIsWatched() {
        val other = Files.createTempDirectory("mp-startup-made").toFile()
        try {
            // A library with no recipe files yet: the index has none, so the startup sync may make the folder.
            val first = DesktopApp(other, settingsFactory = { MapSettings() })
            runBlocking { first.start().join() }
            assertTrue(first.close())
            assertTrue(first.recipesDir.delete())

            val second = DesktopApp(other, settingsFactory = { MapSettings() })
            try {
                runBlocking {
                    second.start().join()
                    assertTrue(second.recipesDir.isDirectory)
                    assertTrue(second.isWatching)
                    File(second.recipesDir, "soup.yaml").writeText(recipe("Soup"))
                    withTimeout(10_000) { while (second.container.recipes.allRecipes().isEmpty()) delay(50) }
                }
            } finally {
                second.close()
            }
        } finally {
            other.deleteRecursively()
        }
    }

    @Test
    fun aStartWithTheRecipeFilesGoneUnindexesNothing() {
        val other = Files.createTempDirectory("mp-startup-emptied").toFile()
        try {
            val first = DesktopApp(other, settingsFactory = { MapSettings() })
            File(first.recipesDir, "soup.yaml").writeText(recipe("Soup"))
            runBlocking {
                first.start().join()
                val id = first.container.recipes.allRecipes().single().id
                first.container.plans.assign(LocalDate.of(2026, 10, 5), "Dinner", id, null)
            }
            assertTrue(first.close())
            // Moved out in Explorer between runs (to back it up, say); the folder itself stays.
            assertTrue(File(first.recipesDir, "soup.yaml").delete())

            val second = DesktopApp(other, settingsFactory = { MapSettings() })
            try {
                runBlocking {
                    second.start().join()
                    assertEquals(listOf("Soup"), second.container.recipes.allRecipes().map { it.name })
                    assertEquals("Soup", second.container.database.mealPlanDao().assignment("2026-10-05", "Dinner")!!.recipeName)
                }
                assertEquals(listOf(missingFilesMessage(1)), second.folder.problems.value.map { it.message })
                // Still watched: putting the files back clears it.
                assertTrue(second.isWatching)
            } finally {
                second.close()
            }
        } finally {
            other.deleteRecursively()
        }
    }

    @Test
    fun aRecipeFolderThatComesBackEmptyUnindexesNothing() {
        val other = Files.createTempDirectory("mp-startup-back-empty").toFile()
        try {
            val first = DesktopApp(other, settingsFactory = { MapSettings() })
            File(first.recipesDir, "soup.yaml").writeText(recipe("Soup"))
            runBlocking {
                first.start().join()
                val id = first.container.recipes.allRecipes().single().id
                first.container.plans.assign(LocalDate.of(2026, 10, 5), "Dinner", id, null)
            }
            assertTrue(first.close())
            val soup = File(first.recipesDir, "soup.yaml").readBytes()
            assertTrue(first.recipesDir.deleteRecursively())

            val second = DesktopApp(other, settingsFactory = { MapSettings() }, rearmPollMillis = 50)
            try {
                runBlocking {
                    second.start().join()
                    // Made again by hand (or by OneDrive) before the files are copied in: it settles empty.
                    assertTrue(second.recipesDir.mkdirs())
                    withTimeout(10_000) { while (!second.isWatching) delay(20) }
                    assertEquals(listOf("Soup"), second.container.recipes.allRecipes().map { it.name })
                    assertEquals("Soup", second.container.database.mealPlanDao().assignment("2026-10-05", "Dinner")!!.recipeName)
                    assertEquals(listOf(missingFilesMessage(1)), second.folder.problems.value.map { it.message })
                    // The files follow: the watcher picks them up and all is well again.
                    File(second.recipesDir, "soup.yaml").writeBytes(soup)
                    withTimeout(10_000) { while (second.folder.problems.value.isNotEmpty()) delay(50) }
                    assertEquals("Soup", second.container.database.mealPlanDao().assignment("2026-10-05", "Dinner")!!.recipeName)
                }
            } finally {
                second.close()
            }
        } finally {
            other.deleteRecursively()
        }
    }

    @Test
    fun recipeFilesMovedOutWhileWatchedUnindexNothing() {
        File(app.recipesDir, "soup.yaml").writeText(recipe("Soup"))
        runBlocking {
            app.start().join()
            assertTrue(app.isWatching)
            val id = app.container.recipes.allRecipes().single().id
            app.container.plans.assign(LocalDate.of(2026, 10, 5), "Dinner", id, null)
            val soup = File(app.recipesDir, "soup.yaml").readBytes()
            // Moved out in Explorer while the app runs: the watcher's sync lists it and changes nothing.
            assertTrue(File(app.recipesDir, "soup.yaml").delete())
            withTimeout(10_000) { while (app.folder.problems.value.map { it.message } != listOf(missingFilesMessage(1))) delay(50) }
            assertEquals(listOf("Soup"), app.container.recipes.allRecipes().map { it.name })
            assertEquals("Soup", app.container.database.mealPlanDao().assignment("2026-10-05", "Dinner")!!.recipeName)
            assertTrue(app.isWatching)
            // Put back: the watcher picks it up and the planned meal is still there.
            File(app.recipesDir, "soup.yaml").writeBytes(soup)
            withTimeout(10_000) { while (app.folder.problems.value.isNotEmpty()) delay(50) }
            assertEquals("Soup", app.container.database.mealPlanDao().assignment("2026-10-05", "Dinner")!!.recipeName)
        }
    }

    @Test
    fun aFileDeletedByHandLeavesTheAppWithinSeconds() {
        File(app.recipesDir, "soup.yaml").writeText(recipe("Soup"))
        File(app.recipesDir, "stew.yaml").writeText(recipe("Stew"))
        runBlocking {
            app.start().join()
            assertTrue(app.isWatching)
            val stew = app.container.recipes.allRecipes().single { it.name == "Stew" }.id
            app.container.plans.assign(LocalDate.of(2026, 10, 5), "Dinner", stew, null)
            // Deleted in Explorer: the watcher's sync only notes it; the re-check a few seconds later removes it,
            // with no other change in the folder to wake the watcher.
            assertTrue(File(app.recipesDir, "soup.yaml").delete())
            withTimeout(15_000) { while (app.container.recipes.allRecipes().size > 1) delay(100) }
            assertEquals(listOf("Stew"), app.container.recipes.allRecipes().map { it.name })
            assertEquals("Stew", app.container.database.mealPlanDao().assignment("2026-10-05", "Dinner")!!.recipeName)
        }
        assertEquals(emptyList<String>(), app.folder.problems.value.map { it.message })
        assertFalse(app.folder.removalsPending)
    }

    @Test
    fun aWatchedRecipeFolderMovedAwayIsListedThenWatchedAgainWhenBack() {
        val other = Files.createTempDirectory("mp-startup-moved").toFile()
        try {
            val moving = DesktopApp(other, settingsFactory = { MapSettings() }, rearmPollMillis = 50)
            try {
                File(moving.recipesDir, "soup.yaml").writeText(recipe("Soup"))
                runBlocking {
                    moving.start().join()
                    assertTrue(moving.isWatching)
                    val id = moving.container.recipes.allRecipes().single().id
                    moving.container.plans.assign(LocalDate.of(2026, 10, 5), "Dinner", id, null)
                    // Renamed or moved in Explorer (or deleted to the Recycle Bin) while watched: on Windows the
                    // watch stays valid and says nothing, so only the re-arm poll can notice.
                    val away = File(other, "recipes-away")
                    Files.move(moving.recipesDir.toPath(), away.toPath())
                    val missing = "The recipe folder is missing: ${moving.recipesDir.path}. Meal Planner will pick it up again when it is back."
                    withTimeout(10_000) { while (moving.folder.problems.value.map { it.message } != listOf(missing)) delay(20) }
                    withTimeout(10_000) { while (moving.isWatching) delay(20) }
                    assertEquals(listOf("Soup"), moving.container.recipes.allRecipes().map { it.name })
                    assertEquals("Soup", moving.container.database.mealPlanDao().assignment("2026-10-05", "Dinner")!!.recipeName)

                    // Moved back, with a recipe added meanwhile: re-synced and watched again once it settles.
                    File(away, "stew.yaml").writeText(recipe("Stew"))
                    Files.move(away.toPath(), moving.recipesDir.toPath())
                    withTimeout(10_000) { while (!moving.isWatching) delay(20) }
                    withTimeout(10_000) { while (moving.container.recipes.allRecipes().size < 2) delay(50) }
                    assertEquals(emptyList<String>(), moving.folder.problems.value.map { it.message })
                    File(moving.recipesDir, "pie.yaml").writeText(recipe("Pie"))
                    withTimeout(10_000) { while (moving.container.recipes.allRecipes().size < 3) delay(50) }
                    assertEquals("Soup", moving.container.database.mealPlanDao().assignment("2026-10-05", "Dinner")!!.recipeName)
                }
            } finally {
                moving.close()
            }
        } finally {
            other.deleteRecursively()
        }
    }

    @Test
    fun aFolderBackAfterAFailedStartupSyncIsLeftToTheRearm() {
        val other = Files.createTempDirectory("mp-startup-race").toFile()
        try {
            val first = DesktopApp(other, settingsFactory = { MapSettings() })
            File(first.recipesDir, "soup.yaml").writeText(recipe("Soup"))
            File(first.recipesDir, "stew.yaml").writeText(recipe("Stew"))
            runBlocking { first.start().join() }
            assertTrue(first.close())
            val soup = File(first.recipesDir, "soup.yaml").readBytes()
            assertTrue(first.recipesDir.deleteRecursively())

            val second = DesktopApp(other, settingsFactory = { MapSettings() }, rearmPollMillis = 60_000)
            // The folder comes back, part-copied, just after the startup sync found it missing (R3).
            second.afterStartupSync = {
                assertTrue(second.recipesDir.mkdirs())
                File(second.recipesDir, "soup.yaml").writeBytes(soup)
            }
            try {
                runBlocking {
                    second.start().join()
                    // Not synced or watched at once: the re-arm loop waits for the folder to settle first.
                    assertFalse(second.isWatching)
                    assertEquals(
                        listOf("The recipe folder is missing: ${second.recipesDir.path}. Meal Planner will pick it up again when it is back."),
                        second.folder.problems.value.map { it.message },
                    )
                    assertEquals(listOf("Soup", "Stew"), second.container.recipes.allRecipes().map { it.name })
                }
            } finally {
                second.close()
            }
        } finally {
            other.deleteRecursively()
        }
    }

    // P7-R10: the library (recipes, photos) in Documents, the app's own data (database, cache) in the secrets folder.
    private fun split(name: String): Pair<File, File> {
        val root = Files.createTempDirectory(name).toFile()
        return File(root, "Documents/Meal Planner") to File(root, "Local/Meal Planner")
    }

    @Test
    fun aFirstRunMakesTheDatabaseInAppDataAndTheLibraryInDocuments() {
        val (library, appData) = split("mp-startup-first")
        try {
            val first = DesktopApp(library, settingsFactory = { MapSettings() }, appDataDir = appData)
            try {
                runBlocking { first.start().join() }
                assertTrue(File(appData, DESKTOP_DB).isFile)
                assertTrue(File(appData, ".cache").isDirectory)
                assertTrue(File(library, "recipes").isDirectory)
                assertTrue(File(library, "recipe-images").isDirectory)
                assertEquals(File(library, "recipes"), first.recipesDir)
                assertEquals(File(library, "recipe-images"), first.imagesDir)
                assertEquals(File(appData, ".cache"), first.cacheDir)
                // Nothing of the app's own is left in Documents.
                assertEquals(listOf("recipe-images", "recipes"), library.list()!!.sorted())
                assertNull(first.libraryNotice.value)
                assertTrue(first.isWatching)
            } finally {
                first.close()
            }
        } finally {
            library.parentFile.parentFile.deleteRecursively()
        }
    }

    @Test
    fun aDatabaseLeftInTheLibraryIsSaidToBeIgnoredOnce() {
        // M4: an earlier build kept mealplanner-app.db in Documents\Meal Planner; it is neither read nor moved.
        val (library, appData) = split("mp-startup-stray-db")
        try {
            library.mkdirs()
            File(library, DESKTOP_DB).writeText("an earlier build's database")
            val said = mutableListOf<String>()
            val app = DesktopApp(library, settingsFactory = { MapSettings() }, appDataDir = appData, log = { said += it })
            try {
                assertEquals(
                    listOf(
                        "Meal Planner: ${File(library, DESKTOP_DB).path} is from an earlier version and isn't used; " +
                            "the database is now ${File(appData, DESKTOP_DB).path}.",
                    ),
                    said,
                )
                assertEquals("an earlier build's database", File(library, DESKTOP_DB).readText())
            } finally {
                app.close()
            }
            // One folder for both (the preview, the tests): that is the database itself, and nothing is said.
            val same = mutableListOf<String>()
            val preview = DesktopApp(appData, settingsFactory = { MapSettings() }, log = { same += it })
            preview.close()
            assertEquals(emptyList<String>(), same)
        } finally {
            library.parentFile.parentFile.deleteRecursively()
        }
    }

    @Test
    fun aLibraryMissingBesideADatabaseInAppDataUnindexesNothing() {
        val (library, appData) = split("mp-startup-library-gone")
        try {
            val first = DesktopApp(library, settingsFactory = { MapSettings() }, appDataDir = appData)
            File(first.recipesDir, "soup.yaml").writeText(recipe("Soup"))
            runBlocking {
                first.start().join()
                val id = first.container.recipes.allRecipes().single().id
                first.container.plans.assign(LocalDate.of(2026, 10, 5), "Dinner", id, null)
            }
            assertTrue(first.close())
            // The whole Documents\Meal Planner folder goes (an offline OneDrive, a folder moved away); the database stays.
            assertTrue(library.deleteRecursively())

            val second = DesktopApp(library, settingsFactory = { MapSettings() }, appDataDir = appData)
            try {
                runBlocking {
                    second.start().join()
                    assertEquals(listOf("Soup"), second.container.recipes.allRecipes().map { it.name })
                    assertEquals("Soup", second.container.database.mealPlanDao().assignment("2026-10-05", "Dinner")!!.recipeName)
                }
                assertFalse(second.recipesDir.exists())
                assertFalse(second.isWatching)
                assertEquals(
                    listOf("The recipe folder is missing: ${second.recipesDir.path}. Meal Planner will pick it up again when it is back."),
                    second.folder.problems.value.map { it.message },
                )
            } finally {
                second.close()
            }
        } finally {
            library.parentFile.parentFile.deleteRecursively()
        }
    }

    @Test
    fun aLibraryThatCantBeReadBesideADatabaseInAppDataUnindexesNothing() {
        val (library, appData) = split("mp-startup-library-unreadable")
        try {
            val first = DesktopApp(library, settingsFactory = { MapSettings() }, appDataDir = appData)
            File(first.recipesDir, "soup.yaml").writeText(recipe("Soup"))
            runBlocking { first.start().join() }
            assertTrue(first.close())
            assertTrue(first.recipesDir.deleteRecursively())
            File(library, "recipes").writeText("a file where the folder should be")

            val second = DesktopApp(library, settingsFactory = { MapSettings() }, appDataDir = appData)
            try {
                runBlocking {
                    second.start().join()
                    assertEquals(listOf("Soup"), second.container.recipes.allRecipes().map { it.name })
                }
                assertFalse(second.isWatching)
                assertEquals(
                    listOf("Can't read the recipe folder: ${second.recipesDir.path}. Meal Planner will pick it up again when it is back."),
                    second.folder.problems.value.map { it.message },
                )
            } finally {
                second.close()
            }
        } finally {
            library.parentFile.parentFile.deleteRecursively()
        }
    }

    @Test
    fun aFirstRunWithTheLibraryBlockedStillStartsAndSaysSo() {
        val (library, appData) = split("mp-startup-blocked")
        try {
            val access = BlockedLibrary()
            val blocked = DesktopApp(library, settingsFactory = { MapSettings() }, appDataDir = appData, libraryAccess = access)
            try {
                runBlocking { blocked.start().join() }
                assertEquals(LIBRARY_BLOCKED_MESSAGE, blocked.libraryNotice.value)
                // The app's own data is unaffected; the library is never made behind the block's back.
                assertTrue(File(appData, DESKTOP_DB).isFile)
                assertFalse(library.exists())
                assertEquals(emptyList<String>(), runBlocking { blocked.container.recipes.allRecipes().map { it.name } })
                // P7-R10b: no write is made only to look; the startup only tried the folders it needed.
                assertEquals(0, access.probes)
            } finally {
                blocked.close()
            }
        } finally {
            library.parentFile.parentFile.deleteRecursively()
        }
    }

    @Test
    fun aFirstRunThatFailsOtherwiseSaysWhyNotTheBlock() {
        val (library, appData) = split("mp-startup-full")
        try {
            val access = BlockedLibrary(refusal = { FileSystemException(it.path, null, "There is not enough space on the disk") })
            val full = DesktopApp(library, settingsFactory = { MapSettings() }, appDataDir = appData, libraryAccess = access)
            try {
                runBlocking { full.start().join() }
                assertEquals("Couldn't save to Documents\\Meal Planner: There is not enough space on the disk", full.libraryNotice.value)
                assertTrue(File(appData, DESKTOP_DB).isFile)
            } finally {
                full.close()
            }
        } finally {
            library.parentFile.parentFile.deleteRecursively()
        }
    }

    @Test
    fun aLaterStartWritesNothingToLookAndOnlyARefusedSaveTurnsTheNoticeOn() {
        val (library, appData) = split("mp-startup-blocked-read")
        try {
            val first = DesktopApp(library, settingsFactory = { MapSettings() }, appDataDir = appData)
            File(first.recipesDir, "soup.yaml").writeText(recipe("Soup"))
            runBlocking { first.start().join() }
            assertTrue(first.close())
            File(first.recipesDir, "stew.yaml").writeText("recipe_uuid: 1b0c7f0e-1111-4222-8333-444455556666\n" + recipe("Stew"))

            val access = BlockedLibrary()
            val second = DesktopApp(library, settingsFactory = { MapSettings() }, appDataDir = appData, libraryAccess = access)
            try {
                runBlocking { second.start().join() }
                // P7-R10b: reading is no check, and nothing is written only to look: no notice, no Defender alert.
                assertEquals(listOf("Soup", "Stew"), runBlocking { second.container.recipes.allRecipes().map { it.name } })
                assertEquals(0, access.probes)
                assertEquals(0, access.creates)
                assertEquals(0, access.writes)
                assertNull(second.libraryNotice.value)

                // A real save refused turns it on, with the save's own message saying how to allow the app.
                val id = runBlocking { second.container.recipes.allRecipes().single { it.name == "Soup" }.id }
                val doc = runBlocking { second.container.recipes.doc(id)!! }
                doc["recipe_name"] = "Soup Two"
                val refused = assertThrows(LibraryBlockedException::class.java) { runBlocking { second.container.recipes.save(doc) } }
                assertEquals(LIBRARY_BLOCKED_MESSAGE, refused.message)
                assertEquals(LIBRARY_BLOCKED_MESSAGE, second.libraryNotice.value)

                // It stays for the session, even once Windows allows the app, until the user asks to check again.
                access.blocked = false
                doc["recipe_name"] = "Soup Three"
                runBlocking { second.container.recipes.save(doc) }
                assertEquals(LIBRARY_BLOCKED_MESSAGE, second.libraryNotice.value)
                assertNull(second.checkLibraryAgain())
                assertNull(second.libraryNotice.value)
                assertEquals(1, access.probes)
            } finally {
                second.close()
            }
        } finally {
            library.parentFile.parentFile.deleteRecursively()
        }
    }

    @Test
    fun checkAgainWritesOnceWhileItIsBusy() {
        // M5: a second Check again while the first is still writing makes no second write.
        val (library, appData) = split("mp-startup-check-busy")
        try {
            library.mkdirs()
            val access = BlockedLibrary().apply { gate = CountDownLatch(1) }
            val app = DesktopApp(library, settingsFactory = { MapSettings() }, appDataDir = appData, libraryAccess = access)
            try {
                val first = thread { app.checkLibraryAgain() }
                assertTrue(access.probing.await(5, TimeUnit.SECONDS))
                assertTrue(app.libraryChecking.value)
                app.checkLibraryAgain()
                access.gate!!.countDown()
                first.join(5_000)
                assertEquals(1, access.probes)
                assertFalse(app.libraryChecking.value)
                assertEquals(LIBRARY_BLOCKED_MESSAGE, app.libraryNotice.value)
            } finally {
                access.gate!!.countDown()
                app.close()
            }
        } finally {
            library.parentFile.parentFile.deleteRecursively()
        }
    }

    @Test
    fun aFileWhereAFirstRunFolderShouldBeIsSaidPlainly() {
        // M7: "recipes" is a file; the photo folder is still made, and the notice names what is in the way.
        val (library, appData) = split("mp-startup-file-in-way")
        try {
            library.mkdirs()
            File(library, "recipes").writeText("not a folder")
            val app = DesktopApp(library, settingsFactory = { MapSettings() }, appDataDir = appData)
            try {
                assertEquals(
                    "Couldn't make the recipes folder in Documents\\Meal Planner: a file called recipes is in the way. " +
                        "Move or rename it, then choose Check again.",
                    app.libraryNotice.value,
                )
                assertTrue(File(library, "recipe-images").isDirectory)
                assertTrue(File(library, "recipes").isFile)
            } finally {
                app.close()
            }
        } finally {
            library.parentFile.parentFile.deleteRecursively()
        }
    }

    @Test
    fun checkAgainOnAStillBlockedLibraryKeepsTheNotice() {
        val (library, appData) = split("mp-startup-check-again")
        try {
            val access = BlockedLibrary()
            val app = DesktopApp(library, settingsFactory = { MapSettings() }, appDataDir = appData, libraryAccess = access)
            try {
                runBlocking { app.start().join() }
                val tried = access.creates + access.probes
                assertEquals(LIBRARY_BLOCKED_MESSAGE, app.checkLibraryAgain())
                assertEquals(LIBRARY_BLOCKED_MESSAGE, app.libraryNotice.value)
                // One write, the user's: the library folder itself, as it isn't there yet.
                assertEquals(tried + 1, access.creates + access.probes)
            } finally {
                app.close()
            }
        } finally {
            library.parentFile.parentFile.deleteRecursively()
        }
    }

    @Test
    fun aFirstRunRefusedAsNotFoundIsTheBlockAndAllowingRecoversIt() {
        // P7-R11b, the owner's PC: Documents is there, "Meal Planner" isn't, and Controlled folder access refused making
        // it as NoSuchFileException (Defender event 1123), not access denied.
        val (library, appData) = split("mp-startup-not-found")
        val root = library.parentFile.parentFile
        try {
            assertTrue(library.parentFile.mkdirs())
            val access = BlockedLibrary(refusal = ::notFoundAtFirstMissing)
            val app = DesktopApp(library, settingsFactory = { MapSettings() }, appDataDir = appData, libraryAccess = access)
            try {
                runBlocking { app.start().join() }
                assertEquals(LIBRARY_BLOCKED_MESSAGE, app.libraryNotice.value)
                assertFalse(library.exists())
                // The run the block stopped has made the database: the next start is no true first run.
                assertTrue(File(appData, DESKTOP_DB).isFile)

                // The installed app's Allow button is offered for it; Windows (a fake runner) allows the app.
                var asked = 0
                val allow = AllowApp(
                    { _, _, _ ->
                        asked++
                        access.blocked = false
                        ElevatedOutcome.Exited(0)
                    },
                    launcher = { installedExe(root).path },
                    folders = fakeFolders(root),
                    recheck = { app.checkLibraryAgain() },
                    log = {},
                )
                val storage = DesktopStorage(app, allow = allow) {}
                assertTrue(storage.canAllowApp)
                storage.allowApp()
                assertEquals(1, asked)
                assertNull(storage.allowMessage.value)
                assertNull(app.libraryNotice.value)

                // Check again's sync makes both folders (the index has no recipe files) and indexes from then on.
                awaitCondition("the library's folders") { app.recipesDir.isDirectory && app.imagesDir.isDirectory }
                // The folder Check again's sync made is watched at once, not a re-arm poll (30 s) later.
                awaitCondition("the recipe folder to be watched") { app.isWatching }
                File(app.recipesDir, "soup.yaml").writeText(recipe("Soup"))
                runBlocking { app.folder.sync() }
                assertEquals(listOf("Soup"), runBlocking { app.container.recipes.allRecipes().map { it.name } })
                assertEquals(emptyList<String>(), app.folder.problems.value.map { it.message })
            } finally {
                app.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aBlockedFirstStartMakesExactlyOneRefusedWrite() {
        // Each refused write is a Defender notification: the first folder refused stops the first run's folders (both
        // share the refused parent), and the startup sync tries nothing more while the notice is up.
        for (refusal in listOf<(File) -> IOException>(::notFoundAtFirstMissing, { AccessDeniedException(it.path) })) {
            val (library, appData) = split("mp-startup-one-write")
            try {
                assertTrue(library.parentFile.mkdirs())
                val access = BlockedLibrary(refusal = refusal)
                val app = DesktopApp(library, settingsFactory = { MapSettings() }, appDataDir = appData, libraryAccess = access)
                try {
                    runBlocking { app.start().join() }
                    assertEquals(LIBRARY_BLOCKED_MESSAGE, app.libraryNotice.value)
                    assertEquals(1, access.creates)
                    assertEquals(0, access.probes + access.writes)
                    // Still listed missing, as when the create fails.
                    assertEquals(
                        listOf("The recipe folder is missing: ${app.recipesDir.path}. Meal Planner will pick it up again when it is back."),
                        app.folder.problems.value.map { it.message },
                    )
                } finally {
                    app.close()
                }
            } finally {
                library.parentFile.parentFile.deleteRecursively()
            }
        }
    }

    @Test
    fun checkAgainStillRefusedAsNotFoundKeepsTheBlock() {
        val (library, appData) = split("mp-startup-not-found-again")
        try {
            assertTrue(library.parentFile.mkdirs())
            val access = BlockedLibrary(refusal = ::notFoundAtFirstMissing)
            val app = DesktopApp(library, settingsFactory = { MapSettings() }, appDataDir = appData, libraryAccess = access)
            try {
                runBlocking { app.start().join() }
                assertEquals(LIBRARY_BLOCKED_MESSAGE, app.checkLibraryAgain())
                // The library is there but refuses the small file: still the block.
                assertTrue(library.mkdirs())
                assertEquals(LIBRARY_BLOCKED_MESSAGE, app.checkLibraryAgain())
                assertEquals(1, access.probes)
            } finally {
                app.close()
            }
        } finally {
            library.parentFile.parentFile.deleteRecursively()
        }
    }

    @Test
    fun aFirstRunNotFoundWithTheFolderAboveMissingIsTheGenericFailure() {
        // Documents itself isn't there (an offline drive, say): "not found" is what it says, not Controlled folder access.
        val (library, appData) = split("mp-startup-gone")
        try {
            val access = BlockedLibrary(refusal = { java.nio.file.NoSuchFileException(it.path) })
            val app = DesktopApp(library, settingsFactory = { MapSettings() }, appDataDir = appData, libraryAccess = access)
            try {
                runBlocking { app.start().join() }
                assertEquals("Couldn't save to Documents\\Meal Planner: Windows couldn't find part of the path", app.libraryNotice.value)
                val storage = DesktopStorage(app) {}
                assertFalse(storage.canAllowApp)
            } finally {
                app.close()
            }
        } finally {
            library.parentFile.parentFile.deleteRecursively()
        }
    }

    @Test
    fun aLaterStartAfterTheBlockMakesTheFoldersOnceAllowed() {
        // P7-R11b: allowed by hand between runs, the next start (no first run: the database is there) still makes the
        // library's folders, because the index has no recipe files yet.
        val (library, appData) = split("mp-startup-recover")
        try {
            assertTrue(library.parentFile.mkdirs())
            val blocked = DesktopApp(library, settingsFactory = { MapSettings() }, appDataDir = appData, libraryAccess = BlockedLibrary(refusal = ::notFoundAtFirstMissing))
            runBlocking { blocked.start().join() }
            assertEquals(LIBRARY_BLOCKED_MESSAGE, blocked.libraryNotice.value)
            assertTrue(blocked.close())
            assertFalse(library.exists())

            val allowed = DesktopApp(library, settingsFactory = { MapSettings() }, appDataDir = appData)
            try {
                runBlocking { allowed.start().join() }
                assertNull(allowed.libraryNotice.value)
                assertTrue(allowed.recipesDir.isDirectory)
                assertTrue(allowed.imagesDir.isDirectory)
                File(allowed.recipesDir, "soup.yaml").writeText(recipe("Soup"))
                runBlocking { allowed.folder.sync() }
                assertEquals(listOf("Soup"), runBlocking { allowed.container.recipes.allRecipes().map { it.name } })
            } finally {
                allowed.close()
            }
        } finally {
            library.parentFile.parentFile.deleteRecursively()
        }
    }

    @Test
    fun foldersAreNeverMadeOnceTheIndexHasRecipes() {
        // R1-R3: a recipe folder that went away stays missing; neither a start, a sync nor Check again makes it.
        val (library, appData) = split("mp-startup-no-remake")
        try {
            val first = DesktopApp(library, settingsFactory = { MapSettings() }, appDataDir = appData)
            File(first.recipesDir, "soup.yaml").writeText(recipe("Soup"))
            runBlocking { first.start().join() }
            assertTrue(first.close())
            assertTrue(File(library, "recipes").deleteRecursively())
            assertTrue(File(library, "recipe-images").deleteRecursively())

            val access = BlockedLibrary().apply { blocked = false }
            val second = DesktopApp(library, settingsFactory = { MapSettings() }, appDataDir = appData, libraryAccess = access)
            try {
                runBlocking { second.start().join() }
                // A sync of a missing folder says so (and is listed), never makes it.
                assertThrows(IOException::class.java) { runBlocking { second.folder.sync() } }
                assertNull(second.checkLibraryAgain())
                assertThrows(IOException::class.java) { runBlocking { second.folder.sync() } }
                assertEquals(0, access.creates)
                assertFalse(second.recipesDir.exists())
                assertFalse(second.imagesDir.exists())
                assertEquals(listOf("Soup"), runBlocking { second.container.recipes.allRecipes().map { it.name } })
            } finally {
                second.close()
            }
        } finally {
            library.parentFile.parentFile.deleteRecursively()
        }
    }

    // What NIO gave on the owner's PC (P7-R11b): Files.createDirectories makes each missing level in turn, so the
    // refusal is a NoSuchFileException naming the first level it couldn't make, whose parent is there.
    private fun notFoundAtFirstMissing(dir: File): IOException {
        var level = dir.absoluteFile
        while (level.parentFile != null && !level.parentFile.isDirectory) level = level.parentFile
        return java.nio.file.NoSuchFileException(level.path)
    }

    // The installed app's exe and Windows' PowerShell under [root], as AllowApp wants them (P7-R11a); never the real ones.
    private fun installedExe(root: File): File =
        File(File(File(root, "Local"), AllowApp.INSTALL_DIR_NAME).apply { mkdirs() }, AllowApp.EXE_NAME).apply { writeText("x") }

    private fun fakeFolders(root: File): WindowsFolders {
        val system = File(root, "System32")
        File(system, "WindowsPowerShell/v1.0/powershell.exe").apply { parentFile.mkdirs(); writeText("x") }
        return object : WindowsFolders {
            override fun system() = system
            override fun localAppData() = File(root, "Local")
        }
    }

    /** What Controlled folder access does to an app it doesn't allow: nothing made or written in the library. */
    private class BlockedLibrary(private val refusal: (File) -> IOException = { AccessDeniedException(it.path) }) : LibraryAccess() {
        @Volatile
        var blocked = true
        var creates = 0
        var probes = 0
        var writes = 0

        override fun createDirectories(dir: File) {
            creates++
            if (blocked) throw refusal(dir) else super.createDirectories(dir)
        }

        // Set by a test that holds Check again mid-write: probing is counted down, then the write waits on the gate.
        @Volatile
        var gate: CountDownLatch? = null
        val probing = CountDownLatch(1)

        override fun probeWrite(dir: File) {
            probes++
            probing.countDown()
            gate?.await(10, TimeUnit.SECONDS)
            if (blocked) throw refusal(dir) else super.probeWrite(dir)
        }

        override fun writeFile(target: File, bytes: ByteArray) {
            writes++
            if (blocked) throw refusal(target) else super.writeFile(target, bytes)
        }
    }
}
