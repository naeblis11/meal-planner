package com.naeblis11.mealplanner.recipes

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.naeblis11.mealplanner.MainDispatcherRule
import com.naeblis11.mealplanner.app.MemorySettings
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
    fun collapsedHeadingsAreKeptAcrossARestart() {
        // Owner, 2026-10-10: what is collapsed is the same after the app restarts (a new model on the same settings).
        val settings = MemorySettings()
        val vm = RecipeListViewModel(repo, settings).also { created += it }
        val desserts = RecipeListViewModel.categoryKey("Desserts")
        val beef = RecipeListViewModel.subcategoryKey("Main Dishes", "Beef")
        vm.toggle(desserts)
        vm.toggle(beef)
        assertEquals(setOf(desserts, beef), vm.collapsed.value)

        val restarted = RecipeListViewModel(repo, settings).also { created += it }
        assertEquals(setOf(desserts, beef), restarted.collapsed.value)

        restarted.toggle(desserts)
        assertEquals(setOf(beef), RecipeListViewModel(repo, settings).also { created += it }.collapsed.value)
    }

    @Test
    fun collapseAllFoldsEveryCategoryAndExpandAllOpensEverything() {
        val vm = newVm()
        vm.toggle(RecipeListViewModel.subcategoryKey("Main Dishes", "Beef"))
        vm.collapseAll(listOf("Desserts", "Main Dishes"))
        assertEquals(
            setOf("c:Desserts", "c:Main Dishes", RecipeListViewModel.subcategoryKey("Main Dishes", "Beef")),
            vm.collapsed.value,
        )
        vm.expandAll()
        assertEquals(emptySet<String>(), vm.collapsed.value)
    }

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

    @Test
    fun narrowsTheListToOneCookbook() = runTest {
        repo.save(doc("recipe_name: Toffee\nsource_book: Flanders Family Cookbook\ncategory: Desserts\ningredients: []\nsteps: []\n"))
        repo.save(doc("recipe_name: Fudge\nsource_book: [Flanders Family Cookbook]\ncategory: Desserts\ningredients: []\nsteps: []\n"))
        repo.save(doc("recipe_name: Brownies\ncategory: Desserts\ningredients: []\nsteps: []\n"))
        val vm = newVm()
        // The names in the list once it holds exactly [count] recipes.
        suspend fun namesWhen(count: Int) =
            vm.groups.first { it?.singleOrNull()?.recipes?.size == count }!!.single().recipes.map { it.name }

        assertEquals(listOf("Flanders Family Cookbook"), vm.books.first { it.isNotEmpty() })

        vm.setBook(BookFilter.Only("Flanders Family Cookbook"))
        assertEquals(listOf("Fudge", "Toffee"), namesWhen(2))

        vm.setQuery("toff")
        assertEquals(listOf("Toffee"), namesWhen(1))

        // Search finds the book's title, with no book picked.
        vm.setQuery("flanders")
        vm.setBook(BookFilter.All)
        assertEquals(listOf("Fudge", "Toffee"), namesWhen(2))
    }

    @Test
    fun hidesTheCookbooksRecipesAndKeepsOnlyRecipesRatedEnough() = runTest {
        // Owner, 2026-10-10: everything but the cookbook, and a rating floor; the two combine.
        repo.save(doc("recipe_name: Toffee\nsource_book: Flanders Family Cookbook\nrating: 4\ncategory: Desserts\ningredients: []\nsteps: []\n"))
        repo.save(doc("recipe_name: Fudge\nsource_book: Flanders Family Cookbook\ncategory: Desserts\ningredients: []\nsteps: []\n"))
        repo.save(doc("recipe_name: Brownies\nrating: 2\ncategory: Desserts\ningredients: []\nsteps: []\n"))
        val vm = newVm()
        // Waits for the list to hold exactly these names (an earlier list with the same count must not pass).
        suspend fun awaitNames(vararg names: String) {
            vm.groups.first { it?.singleOrNull()?.recipes?.map { r -> r.name } == names.toList() }
        }

        vm.setBook(BookFilter.NoBook)
        awaitNames("Brownies")

        vm.setBook(BookFilter.All)
        vm.setMinRating(3)
        awaitNames("Toffee")

        vm.setMinRating(2)
        awaitNames("Brownies", "Toffee")

        vm.setBook(BookFilter.Only("Flanders Family Cookbook"))
        awaitNames("Toffee")

        // 0 shows the unrated recipe again.
        vm.setMinRating(0)
        awaitNames("Fudge", "Toffee")
    }
}
