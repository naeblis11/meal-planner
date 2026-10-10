package com.naeblis11.mealplanner.importing

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.naeblis11.mealplanner.MainDispatcherRule
import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.ui.theme.MealPlannerTheme
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.test.onAllNodesWithText
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/** Every Meal Master fixture goes through the real read and staging and reaches the review screen. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h3000dp")
class MealMasterImportScreenTest(private val f: File) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun files(): List<Array<Any>> = File("../../tests/fixtures/mealmaster").listFiles { x -> x.name.endsWith(".mmf") }!!.sortedBy { it.name }.map { arrayOf<Any>(it) }
    }

    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val created = mutableListOf<ImportViewModel>()
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()

    @After
    fun tearDown() {
        created.forEach { it.viewModelScope.cancel() }
        db.close()
    }

    @Test
    fun reachesTheReviewScreen() {
        val images = Files.createTempDirectory("images").toFile()
        val repo = RecipeRepository(db, images)
        run {
            val staging = Files.createTempDirectory("staging").toFile()
            val vm = ImportViewModel(repo, images, newStagingDir = { staging }).also { created += it }
            vm.start(f.name) { f.inputStream() }
            val state = runBlocking { vm.state.first { it !is ImportState.Reading && it !is ImportState.Idle } }
            if (f.name == "missing-title.mmf") {
                // Its only recipe has no title, so nothing can be staged.
                assertTrue(state is ImportState.Failed)
            } else {
                assertTrue("${f.name}: $state", state is ImportState.Reviewing)
                state as ImportState.Reviewing
                compose.setContent { MealPlannerTheme { ImportScreen(state, { _, _ -> }, {}, {}, {}) } }
                assertTrue(compose.onAllNodesWithText(state.rows.first().title, substring = true).fetchSemanticsNodes().isNotEmpty())
            }
        }
    }
}
