package com.naeblis11.mealplanner.desktop.server

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
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
