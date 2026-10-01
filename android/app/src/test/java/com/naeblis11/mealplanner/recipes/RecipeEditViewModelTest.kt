package com.naeblis11.mealplanner.recipes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.naeblis11.mealplanner.MainDispatcherRule
import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.YamlMap
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecipeEditViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private lateinit var db: AppDatabase
    private lateinit var repo: RecipeRepository
    private val created = mutableListOf<RecipeEditViewModel>()

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

    private fun newVm(id: Long?, handle: SavedStateHandle = SavedStateHandle(), repository: RecipeRepository = repo) =
        RecipeEditViewModel(id, repository, handle).also { created += it }

    @Suppress("UNCHECKED_CAST")
    private fun doc(yaml: String) = RecipeYaml.load(yaml) as YamlMap

    @Test
    fun createsANewRecipe() = runTest {
        val vm = newVm(null)
        vm.state.first { it.form != null }
        vm.edit { it.copy(name = "Toast") }
        vm.addIngredient()
        val key = vm.state.value.form!!.rows.single().key
        vm.updateRow(key) { it.copy(name = "Bread", amount = "2", unit = "slice") }
        vm.addStep()
        vm.updateStep(vm.state.value.form!!.steps.single().key, "Toast it.")

        vm.save()
        val id = vm.savedId.first { it != null }!!
        assertEquals("Toast", repo.doc(id)!!["recipe_name"])
        vm.savedHandled()
        assertNull(vm.savedId.value)
    }

    @Test
    fun aDoubleTapSavesOnce() = runTest {
        // The first save is held inside the repository (on its IO thread) until the second tap is in.
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gated = RecipeRepository(db, Files.createTempDirectory("images").toFile(), newUuid = {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            UUID.randomUUID().toString()
        })
        val vm = newVm(null, repository = gated)
        vm.state.first { it.form != null }
        vm.edit { it.copy(name = "Toast") }
        vm.addIngredient()
        vm.updateRow(vm.state.value.form!!.rows.single().key) { it.copy(name = "Bread", amount = "2", unit = "slice") }
        vm.addStep()
        vm.updateStep(vm.state.value.form!!.steps.single().key, "Toast it.")

        vm.save()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        vm.save()
        release.countDown()
        vm.savedId.first { it != null }
        // A save the second tap wrongly started would be a child of the ViewModel's scope: wait for each to end.
        vm.viewModelScope.coroutineContext.job.children.toList().joinAll()
        assertEquals(1, repo.allRecipes().size)
    }

    @Test
    fun showsThePisMessageAndKeepsTheForm() = runTest {
        repo.save(doc("recipe_name: Toast\ningredients: []\nsteps: []\n"))
        val vm = newVm(null)
        vm.state.first { it.form != null }
        vm.edit { it.copy(name = "toast") }
        vm.save()
        assertEquals("Another recipe is already called 'toast'. Pick a different title.", vm.state.first { it.error != null }.error)
        assertEquals("toast", vm.state.value.form!!.name)
        assertNull(vm.savedId.value)
    }

    @Test
    fun editsAnExistingRecipeAndConvertsMetric() = runTest {
        val id = repo.save(doc("recipe_name: Soup\ningredients:\n- Stock:\n    amounts:\n    - amount: 1\n      unit: cup\nsteps:\n- step: Simmer.\n"))
        val vm = newVm(id)
        val form = vm.state.first { it.form != null }.form!!
        vm.updateRow(form.rows.single().key) { it.copy(amount = "250", unit = "ml") }
        vm.moveStep(form.steps.single().key, 1)
        vm.save()
        assertEquals(id, vm.savedId.first { it != null })
        val row = db.recipeDao().ingredients(id).single()
        assertEquals("1 1/24" to "cup", row.amount to row.unit)
    }

    @Test
    fun aRetryAfterAnErrorUsesTheOriginalSteps() = runTest {
        val id = repo.save(
            doc(
                "recipe_name: Soup\ningredients:\n- Stock:\n    amounts:\n    - amount: 1\n      unit: cup\n" +
                    "steps:\n- step: First.\n  notes: Careful.\n- step: Second.\n",
            ),
        )
        val vm = newVm(id)
        val form = vm.state.first { it.form != null }.form!!
        val rowKey = form.rows.single().key
        vm.moveStep("e0", 1) // order is now e1, e0
        vm.removeRow(rowKey)
        vm.save()
        assertEquals("A recipe needs at least one ingredient.", vm.state.first { it.error != null }.error)

        vm.addIngredient()
        vm.updateRow(vm.state.value.form!!.rows.single().key) { it.copy(name = "Water", amount = "1", unit = "cup") }
        vm.save()
        vm.savedId.first { it != null }

        val steps = repo.doc(id)!!["steps"] as List<*>
        val byText = steps.associate { s -> (s as Map<*, *>)["step"] to s["notes"] }
        assertEquals(listOf("Second.", "First."), steps.map { (it as Map<*, *>)["step"] })
        assertEquals(mapOf("Second." to null, "First." to "Careful."), byText)
    }

    @Test
    fun aRecipeThatCantBeLoadedShowsAnErrorNotACrash() = runTest {
        val id = repo.save(doc("recipe_name: Soup\ningredients: []\nsteps: []\n"))
        val row = db.recipeDao().recipe(id)!!
        db.recipeDao().updateRecipe(row.copy(rawYaml = "a: [b"))
        val vm = newVm(id)
        val state = vm.state.first { it.error != null }
        assertTrue(state.error!!, state.error!!.startsWith("This recipe can't be edited:"))
        assertNull(state.form)
    }

    @Test
    fun aDeletedRecipeIsNotEditedAsANewOne() = runTest {
        val vm = newVm(999L)
        assertEquals("This recipe no longer exists.", vm.state.first { it.error != null }.error)
        assertNull(vm.state.value.form)
    }

    @Test
    fun anUnsavedDraftSurvivesProcessDeath() = runTest {
        val id = repo.save(doc("recipe_name: Soup\ningredients:\n- Salt:\n    amounts:\n    - amount: 1\n      unit: tsp\nsteps:\n- step: Stir.\n"))
        val handle = SavedStateHandle()
        val first = newVm(id, handle)
        first.state.first { it.form != null }
        assertFalse(first.state.value.dirty)
        first.edit { it.copy(name = "Better soup") }
        first.addStep()
        assertTrue(first.state.value.dirty)

        // A new process gets only what was saved in the handle.
        val second = newVm(id, SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }))
        val restored = second.state.first { it.form != null }
        assertEquals("Better soup", restored.form!!.name)
        assertEquals(2, restored.form!!.steps.size)
        assertTrue(restored.dirty)
        second.addStep()
        assertEquals(3, second.state.value.form!!.steps.map { it.key }.toSet().size)

        // Back to exactly what is stored: nothing to lose any more.
        second.edit { it.copy(name = "Soup", steps = it.steps.take(1)) }
        assertFalse(second.state.value.dirty)
    }

    @Test
    fun editingAfterASaveDoesNotWriteADraft() = runTest {
        val handle = SavedStateHandle()
        val vm = newVm(null, handle)
        vm.state.first { it.form != null }
        vm.edit { it.copy(name = "Toast") }
        vm.addIngredient()
        vm.updateRow(vm.state.value.form!!.rows.single().key) { it.copy(name = "Bread", amount = "2", unit = "slice") }
        vm.addStep()
        vm.updateStep(vm.state.value.form!!.steps.single().key, "Toast it.")
        vm.save()
        vm.savedId.first { it != null }

        vm.edit { it }
        assertFalse(vm.state.value.dirty)
        assertNull(handle.get<String>(RecipeEditViewModel.DRAFT_KEY))
    }

    @Test
    fun savingClearsTheDraft() = runTest {
        val handle = SavedStateHandle()
        val vm = newVm(null, handle)
        vm.state.first { it.form != null }
        vm.edit { it.copy(name = "Toast") }
        vm.addIngredient()
        vm.updateRow(vm.state.value.form!!.rows.single().key) { it.copy(name = "Bread", amount = "2", unit = "slice") }
        vm.addStep()
        vm.updateStep(vm.state.value.form!!.steps.single().key, "Toast it.")
        assertTrue(handle.contains(RecipeEditViewModel.DRAFT_KEY))

        vm.save()
        vm.savedId.first { it != null }
        assertNull(handle.get<String>(RecipeEditViewModel.DRAFT_KEY))
        assertFalse(vm.state.value.dirty)
    }
}
