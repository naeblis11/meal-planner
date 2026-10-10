package com.naeblis11.mealplanner.desktop.server

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P4-R6: the secrets file (the retired Python server's place and format), never the real LOCALAPPDATA in tests. */
class SecretsFileTest {
    private val dir: File = Files.createTempDirectory("mp-secrets").toFile()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun env(vararg values: Pair<String, String>): (String) -> String? = mapOf(*values)::get

    @Test
    fun itReadsLinesAsReadEnvDoes() {
        val file = File(dir, ".env").apply {
            writeText("# a comment\n\n MEAL_PLANNER_SECRET_KEY = abc \nnot a pair\nOTHER=a=b\nMEAL_PLANNER_API_TOKEN=first\nMEAL_PLANNER_API_TOKEN=last\n")
        }
        assertEquals(
            mapOf("MEAL_PLANNER_SECRET_KEY" to "abc", "OTHER" to "a=b", "MEAL_PLANNER_API_TOKEN" to "last"),
            SecretsFile(file).read(),
        )
    }

    @Test
    fun aMissingFileReadsAsEmpty() {
        assertEquals(emptyMap<String, String>(), SecretsFile(File(dir, ".env")).read())
    }

    @Test
    fun putKeepsEveryOtherLineAndReplacesTheKey() {
        val file = File(dir, ".env").apply {
            writeText("# Meal Planner secrets.\nMEAL_PLANNER_SECRET_KEY=abc\nMEAL_PLANNER_API_TOKEN=old\nMEAL_PLANNER_PASSWORD_HASH=scrypt:32768:8:1-salt-hash\n")
        }
        SecretsFile(file).put("MEAL_PLANNER_API_TOKEN", "new")
        assertEquals(
            "# Meal Planner secrets.\nMEAL_PLANNER_SECRET_KEY=abc\nMEAL_PLANNER_PASSWORD_HASH=scrypt:32768:8:1-salt-hash\nMEAL_PLANNER_API_TOKEN=new\n",
            file.readText(),
        )
    }

    @Test
    fun putMakesTheFolderAndLeavesNoTemporaryFile() {
        val folder = File(dir, "Meal Planner")
        SecretsFile(File(folder, ".env")).put("MEAL_PLANNER_API_TOKEN", "t")
        assertEquals(listOf(".env"), folder.list()!!.toList())
        assertEquals("MEAL_PLANNER_API_TOKEN=t\n", File(folder, ".env").readText())
    }

    @Test
    fun twoPutsAtOnceBothLandAndLeaveNoTemporaryFile() {
        // The token's create and the Google client's save (two puts) can overlap: with one shared .env.tmp and no lock,
        // one put's move installed the other's content, so the token in memory wasn't in the file.
        val folder = File(dir, "Meal Planner")
        val file = File(folder, ".env")
        File(folder.apply { mkdirs() }, ".env").writeText("# kept\nOTHER=stays\n")
        val rounds = 200
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val failures = AtomicReference<Throwable?>(null)
        val writers = listOf("MEAL_PLANNER_API_TOKEN", "MEAL_PLANNER_GCAL_CLIENT_ID").map { key ->
            thread(name = "put-$key") {
                ready.countDown()
                go.await()
                try {
                    for (i in 1..rounds) SecretsFile(file).put(key, "$key-$i")
                } catch (t: Throwable) {
                    failures.compareAndSet(null, t)
                }
            }
        }
        ready.await()
        go.countDown()
        writers.forEach { it.join(60_000) }
        assertNull(failures.get())
        assertEquals(
            mapOf("OTHER" to "stays", "MEAL_PLANNER_API_TOKEN" to "MEAL_PLANNER_API_TOKEN-$rounds", "MEAL_PLANNER_GCAL_CLIENT_ID" to "MEAL_PLANNER_GCAL_CLIENT_ID-$rounds"),
            SecretsFile(file).read(),
        )
        assertTrue(file.readText().startsWith("# kept\n"))
        assertEquals(listOf(".env"), folder.list()!!.toList())
    }

    @Test
    fun aFileWithWindowsLineEndingsReadsAndKeepsItsLines() {
        val file = File(dir, ".env").apply {
            writeText("# a comment\r\nMEAL_PLANNER_SECRET_KEY=abc\r\nMEAL_PLANNER_API_TOKEN=old\r\n")
        }
        assertEquals(mapOf("MEAL_PLANNER_SECRET_KEY" to "abc", "MEAL_PLANNER_API_TOKEN" to "old"), SecretsFile(file).read())
        SecretsFile(file).put("MEAL_PLANNER_API_TOKEN", "new")
        assertEquals(mapOf("MEAL_PLANNER_SECRET_KEY" to "abc", "MEAL_PLANNER_API_TOKEN" to "new"), SecretsFile(file).read())
        // No \r is left inside a kept line or a value.
        assertEquals("# a comment\nMEAL_PLANNER_SECRET_KEY=abc\nMEAL_PLANNER_API_TOKEN=new\n", file.readText())
    }

    @Test
    fun aFileSavedWithAByteOrderMarkReadsAndKeepsItsLines() {
        // Notepad's UTF-8 with BOM: the first key must not read as "\uFEFFMEAL_PLANNER_SECRET_KEY".
        val file = File(dir, ".env").apply {
            writeText("\uFEFFMEAL_PLANNER_SECRET_KEY=abc\nMEAL_PLANNER_API_TOKEN=old\n")
        }
        assertEquals(mapOf("MEAL_PLANNER_SECRET_KEY" to "abc", "MEAL_PLANNER_API_TOKEN" to "old"), SecretsFile(file).read())
        SecretsFile(file).put("MEAL_PLANNER_API_TOKEN", "new")
        assertEquals(mapOf("MEAL_PLANNER_SECRET_KEY" to "abc", "MEAL_PLANNER_API_TOKEN" to "new"), SecretsFile(file).read())
        assertEquals("MEAL_PLANNER_SECRET_KEY=abc\nMEAL_PLANNER_API_TOKEN=new\n", file.readText())
    }

    @Test
    fun theHomeOverrideWins() {
        val home = File(dir, "home")
        assertEquals(
            File(home, ".env"),
            SecretsFile.location(env("MEAL_PLANNER_HOME" to home.path, "LOCALAPPDATA" to File(dir, "local").path), dir.path),
        )
    }

    @Test
    fun withoutAnOverrideItIsUnderLocalAppData() {
        val local = File(dir, "local")
        assertEquals(File(File(local, "Meal Planner"), ".env"), SecretsFile.location(env("LOCALAPPDATA" to local.path), dir.path))
    }

    @Test
    fun theFolderFromBeforeTheRenameIsIgnoredEvenWhenItIsTheOnlyOne() {
        val local = File(dir, "local")
        // The app's name before the rename (allowed here by tools/banned-terms.txt).
        File(local, "MAC Meal Planner").mkdirs()
        assertEquals(File(File(local, "Meal Planner"), ".env"), SecretsFile.location(env("LOCALAPPDATA" to local.path), dir.path))
    }

    @Test
    fun withoutLocalAppDataItIsUnderTheHomeFolder() {
        assertEquals(File(dir, "AppData\\Local\\Meal Planner\\.env"), SecretsFile.location(env(), dir.path))
    }

    @Test
    fun aTildeMeansTheHomeFolder() {
        assertEquals(File(File(dir, "mp"), ".env"), SecretsFile.location(env("MEAL_PLANNER_HOME" to "~/mp"), dir.path))
    }

    @Test
    fun thePreviewKeepsItsSecretsInItsDataFolder() {
        val installed = File(dir, "installed.env")
        assertEquals(File(dir, ".env"), SecretsFile.forApp(dir, property = "C:\\preview", installed = { installed }))
        assertEquals(installed, SecretsFile.forApp(dir, property = null, installed = { installed }))
        assertTrue(SecretsFile.forApp(dir, property = " ", installed = { installed }) == installed)
    }
}
