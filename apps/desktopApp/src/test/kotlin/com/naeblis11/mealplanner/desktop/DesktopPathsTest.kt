package com.naeblis11.mealplanner.desktop

import com.naeblis11.mealplanner.desktop.server.SecretsFile
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * paths.py's data_dir() for the library, the secrets folder for the app's own data (P7-R10), plus the Java property
 * the Gradle run task and tests set, which puts both in one folder.
 */
class DesktopPathsTest {
    private val docs: File = Files.createTempDirectory("mp-docs").toFile()
    private val local: File = Files.createTempDirectory("mp-local").toFile()
    private val home: File = Files.createTempDirectory("mp-home").toFile()

    @After
    fun tearDown() {
        docs.deleteRecursively()
        local.deleteRecursively()
        home.deleteRecursively()
    }

    @Test
    fun thePropertyWins() {
        assertEquals(File("C:/preview"), DesktopPaths.libraryDir(property = "C:/preview", env = "C:/env", documents = { docs }))
    }

    @Test
    fun theEnvironmentVariableIsNext() {
        assertEquals(File("C:/env"), DesktopPaths.libraryDir(property = null, env = "C:/env", documents = { docs }))
        assertEquals(File("C:/env"), DesktopPaths.libraryDir(property = "", env = "C:/env", documents = { docs }))
    }

    @Test
    fun otherwiseMealPlannerInDocuments() {
        assertEquals(File(docs, "Meal Planner"), DesktopPaths.libraryDir(property = null, env = null, documents = { docs }))
        File(docs, OLD_NAME).mkdirs()
        File(docs, "Meal Planner").mkdirs()
        assertEquals(File(docs, "Meal Planner"), DesktopPaths.libraryDir(property = null, env = "", documents = { docs }))
    }

    @Test
    fun theFolderFromBeforeTheRenameIsIgnoredEvenWhenItIsTheOnlyOne() {
        File(docs, OLD_NAME).mkdirs()
        assertEquals(File(docs, "Meal Planner"), DesktopPaths.libraryDir(property = null, env = null, documents = { docs }))
    }

    @Test
    fun theAppDataIsTheSecretsFolderUnderLocalAppData() {
        val env = mapOf("LOCALAPPDATA" to local.path)
        assertEquals(File(local, "Meal Planner"), DesktopPaths.appDataDir(property = null, env = env::get, userHome = home.path))
        assertEquals(File(local, "Meal Planner"), DesktopPaths.appDataDir(property = "", env = env::get, userHome = home.path))
    }

    @Test
    fun theAppDataFollowsMealPlannerHome() {
        val env = mapOf("LOCALAPPDATA" to local.path, "MEAL_PLANNER_HOME" to home.path)
        assertEquals(home, DesktopPaths.appDataDir(property = null, env = env::get, userHome = docs.path))
    }

    @Test
    fun theDataDirVariableMovesOnlyTheLibrary() {
        val env = mapOf("LOCALAPPDATA" to local.path, DesktopPaths.DATA_DIR_ENV to docs.path)
        assertEquals(File(local, "Meal Planner"), DesktopPaths.appDataDir(property = null, env = env::get, userHome = home.path))
        assertEquals(docs, DesktopPaths.libraryDir(property = null, env = env[DesktopPaths.DATA_DIR_ENV], documents = { home }))
    }

    @Test
    fun theAppDataIsAlwaysTheSecretsFilesFolder() {
        for (env in listOf(mapOf("LOCALAPPDATA" to local.path), mapOf("MEAL_PLANNER_HOME" to home.path), emptyMap())) {
            assertEquals(
                SecretsFile.location(env::get, home.path).parentFile,
                DesktopPaths.appDataDir(property = null, env = env::get, userHome = home.path),
            )
        }
    }

    @Test
    fun thePropertyPutsTheAppDataBesideTheLibrary() {
        val env = mapOf("LOCALAPPDATA" to local.path, "MEAL_PLANNER_HOME" to home.path)
        assertEquals(File("C:/preview"), DesktopPaths.appDataDir(property = "C:/preview", env = env::get, userHome = home.path))
        assertEquals(File("C:/preview"), DesktopPaths.libraryDir(property = "C:/preview", env = "C:/env", documents = { docs }))
    }

    @Test
    fun thePreviewHasItsOwnSettingsNode() {
        assertEquals("com/naeblis11/mealplanner/preview", DesktopPaths.prefsNode("C:/preview"))
        assertEquals("com/naeblis11/mealplanner", DesktopPaths.prefsNode(null))
    }

    private companion object {
        // The app's name before the rename (allowed here by tools/banned-terms.txt).
        const val OLD_NAME = "MAC Meal Planner"
    }
}
