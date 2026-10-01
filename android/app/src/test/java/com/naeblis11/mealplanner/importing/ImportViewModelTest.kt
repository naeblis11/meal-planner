package com.naeblis11.mealplanner.importing

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.naeblis11.mealplanner.MainDispatcherRule
import com.naeblis11.mealplanner.backup.ImportAction
import com.naeblis11.mealplanner.backup.StagedKind
import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.YamlMap
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImportViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private lateinit var db: AppDatabase
    private lateinit var repo: RecipeRepository
    private lateinit var staging: File
    private val dirs = mutableListOf<File>()
    private lateinit var vm: ImportViewModel
    private val created = mutableListOf<ImportViewModel>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val images = Files.createTempDirectory("images").toFile()
        repo = RecipeRepository(db, images)
        staging = Files.createTempDirectory("staging").toFile()
        vm = ImportViewModel(repo, images, newStagingDir = { File(staging, "s${dirs.size}").apply { mkdirs(); dirs += this } }).also { created += it }
    }

    // Each ViewModel's scope is cancelled before the database closes and before the Main
    // rule resets Main, so nothing still running can touch either (see RecipeDetailViewModelTest).
    @After
    fun tearDown() {
        created.forEach { it.viewModelScope.cancel() }
        db.close()
    }

    private val soupYaml = "recipe_name: Soup\ncategory: Soups & Stews\nyields:\n- servings: 4\ningredients:\n- Salt:\n    amounts:\n    - amount: a pinch\n      unit: ''\nsteps:\n- step: Stir.\n"

    @Test
    fun reviewsAndImportsAFile() = runTest {
        vm.start("soup.yaml") { soupYaml.byteInputStream() }
        val review = vm.state.first { it is ImportState.Reviewing } as ImportState.Reviewing
        val row = review.rows.single()
        assertEquals(StagedKind.NEW, row.kind)
        assertEquals("Soups & Stews", row.category)
        assertEquals("4" to "servings", row.servingsAmount to row.servingsUnit)
        assertEquals(ImportAction.IMPORT, row.action)
        assertEquals(1, row.amountIssues)
        assertTrue(row.rows.single().needsInput)

        vm.update(row.tempId) { r -> r.copy(rows = r.rows.map { it.copy(amount = "1/4", unit = "tsp") }) }
        vm.confirm()
        val finished = vm.state.first { it is ImportState.Finished } as ImportState.Finished
        assertEquals("Imported 1 recipe(s) from soup.yaml", finished.message)
        assertEquals("1/4", db.recipeDao().ingredients(repo.allRecipes().single().id).single().amount)
        assertFalse(dirs.single().exists())
    }

    @Test
    fun aNameClashKeepsTheReviewOpenWithThePisMessage() = runTest {
        @Suppress("UNCHECKED_CAST")
        repo.save(RecipeYaml.load("recipe_name: Stew\ningredients: []\nsteps: []\n") as YamlMap)
        vm.start("soup.yaml") { soupYaml.byteInputStream() }
        val review = vm.state.first { it is ImportState.Reviewing } as ImportState.Reviewing
        vm.update(review.rows.single().tempId) { it.copy(title = "stew") }
        vm.confirm()
        val again = vm.state.first { it is ImportState.Reviewing && it.error != null } as ImportState.Reviewing
        assertEquals("Can't import 'Soup' as 'stew': that name is already in your library. Pick another name.", again.error)
        assertTrue(dirs.single().exists())
    }

    @Test
    fun aFileWithNothingToImportFailsAndCleansUp() = runTest {
        vm.start("photos.zip") { "not a zip".byteInputStream() }
        val failed = vm.state.first { it is ImportState.Failed } as ImportState.Failed
        assertEquals("Could not read photos.zip as a zip file.", failed.message)
        assertFalse(dirs.single().exists())

        vm.start("empty.mmf") { "no recipes here".byteInputStream() }
        assertEquals("No recipes found in empty.mmf.", (vm.state.first { it is ImportState.Failed && it.message.startsWith("No") } as ImportState.Failed).message)
    }

    @Test
    fun cancellingDeletesTheStagedFiles() = runTest {
        vm.start("soup.yaml") { soupYaml.byteInputStream() }
        vm.state.first { it is ImportState.Reviewing }
        vm.cancel()
        assertEquals(ImportState.Idle, vm.state.value)
        assertFalse(dirs.single().exists())
    }

    @Test
    fun aDoubleConfirmImportsOnce() = runTest {
        vm.start("soup.yaml") { soupYaml.byteInputStream() }
        val review = vm.state.first { it is ImportState.Reviewing } as ImportState.Reviewing
        vm.update(review.rows.single().tempId) { r -> r.copy(rows = r.rows.map { it.copy(amount = "1/4", unit = "tsp") }) }
        vm.confirm()
        vm.confirm()
        vm.state.first { it is ImportState.Finished }
        assertEquals(1, repo.allRecipes().size)
        assertTrue(vm.state.value is ImportState.Finished)
    }

    @Test
    fun cancelDuringReadStopsTheRead() = runTest {
        val release = java.util.concurrent.CountDownLatch(1)
        val opened = java.util.concurrent.CountDownLatch(1)
        vm.start("soup.yaml") { opened.countDown(); release.await(); soupYaml.byteInputStream() }
        assertTrue(opened.await(5, java.util.concurrent.TimeUnit.SECONDS))
        vm.cancel()
        release.countDown()
        vm.awaitJob()
        assertEquals(ImportState.Idle, vm.state.value)
        assertFalse(dirs.single().exists())
    }

    @Test
    fun aSecurityExceptionOpeningTheFileFails() = runTest {
        vm.start("soup.yaml") { throw SecurityException("no permission") }
        val failed = vm.state.first { it is ImportState.Failed } as ImportState.Failed
        assertEquals("Could not read soup.yaml: no permission", failed.message)
        assertFalse(dirs.single().exists())
    }

    @Test
    fun theFileNameIsLookedUpOffTheMainThreadAndItsFailureIsCaught() = runTest {
        val mainThread = Thread.currentThread()
        var lookedUpOn: Thread? = null
        vm.start(name = { lookedUpOn = Thread.currentThread(); "soup.yaml" }) { soupYaml.byteInputStream() }
        assertEquals("From soup.yaml", "From " + (vm.state.first { it is ImportState.Reviewing } as ImportState.Reviewing).sourceName)
        assertTrue(lookedUpOn != null && lookedUpOn !== mainThread)

        vm.start(name = { throw SecurityException("no permission") }) { error("not opened") }
        val failed = vm.state.first { it is ImportState.Failed } as ImportState.Failed
        assertEquals("Could not read the file: no permission", failed.message)
        assertFalse(dirs.last().exists())
    }

    @Test
    fun aSupersededReadThatFailsDoesNotDisturbTheNewImport() = runTest {
        val release = java.util.concurrent.CountDownLatch(1)
        val opened = java.util.concurrent.CountDownLatch(1)
        vm.start("a.yaml") { opened.countDown(); release.await(); throw java.io.IOException("disk gone") }
        assertTrue(opened.await(5, java.util.concurrent.TimeUnit.SECONDS))
        vm.start("soup.yaml") { soupYaml.byteInputStream() }
        vm.state.first { it is ImportState.Reviewing }
        release.countDown()
        val deadline = System.currentTimeMillis() + 5000
        while (dirs[0].exists() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertFalse(dirs[0].exists())
        assertTrue(vm.state.value is ImportState.Reviewing)
        assertTrue(dirs[1].exists())
    }
}
