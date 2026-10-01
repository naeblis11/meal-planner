package com.naeblis11.mealplanner.recipes

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.naeblis11.mealplanner.MainDispatcherRule
import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.YamlMap
import java.nio.file.Files
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecipeListViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private lateinit var db: AppDatabase
    private lateinit var repo: RecipeRepository
    private val created = mutableListOf<RecipeListViewModel>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = RecipeRepository(db, Files.createTempDirectory("images").toFile())
    }

    // Each ViewModel's scope is cancelled before the database closes and before the Main
    // rule resets Main, so nothing still running can touch either (see RecipeDetailViewModelTest).
    @After
    fun tearDown() {
        created.forEach { it.viewModelScope.cancel() }
        db.close()
    }

    private fun newVm() = RecipeListViewModel(repo).also { created += it }

    @Suppress("UNCHECKED_CAST")
    private fun doc(yaml: String) = RecipeYaml.load(yaml) as YamlMap

    @Test
    fun groupsTheLibraryAndFiltersBySearch() = runTest {
        repo.save(doc("recipe_name: Pho\ncategory: Soups & Stews\ningredients:\n- Star anise:\nsteps: []\n"))
        repo.save(doc("recipe_name: Brownies\ncategory: Desserts\ningredients: []\nsteps: []\n"))
        val vm = newVm()

        val all = vm.groups.first { it != null && it.isNotEmpty() }!!
        assertEquals(listOf("Soups & Stews", "Desserts"), all.map { it.category })

        vm.setQuery("anise")
        val found = vm.groups.first { it != null && it.size == 1 }!!
        assertEquals(listOf("Pho"), found.single().recipes.map { it.name })
    }
}
