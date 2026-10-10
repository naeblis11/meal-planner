package com.naeblis11.mealplanner.desktop

import androidx.lifecycle.viewModelScope
import com.naeblis11.mealplanner.recipes.RecipeEditViewModel
import java.io.File
import java.nio.file.Files
import java.util.prefs.Preferences
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The edit form on the desktop: a hand edit indexed while the form is open is never saved over. */
class RecipeEditOverHandEditTest {
    private val dir: File = Files.createTempDirectory("mp-edit").toFile()
    private val prefsNode = "com/naeblis11/mealplanner/test-${System.nanoTime()}"
    private val app = DesktopApp(dir, prefsNode)
    private val created = mutableListOf<RecipeEditViewModel>()

    @After
    fun tearDown() {
        created.forEach { it.viewModelScope.cancel() }
        app.close()
        dir.deleteRecursively()
        Preferences.userRoot().node(prefsNode).removeNode()
    }

    private fun recipe(amount: String) =
        "recipe_uuid: u-soup\nrecipe_name: Soup\ningredients:\n- Salt:\n    amounts:\n    - amount: $amount\n      unit: tsp\nsteps:\n- step: Stir.\n"

    private fun editor(id: Long) = RecipeEditViewModel(id, app.container.recipes).also { created += it }

    @Test
    fun theEditScreenSaysTheRecipeChangedAndWritesNothing() = runBlocking {
        val file = File(app.recipesDir, "soup.yaml").apply { writeText(recipe("1")) }
        app.folder.sync()
        val id = app.container.recipes.allRecipes().single().id
        val vm = editor(id)
        withTimeout(5_000) { vm.state.first { it.form != null } }

        // A hand edit lands while the form is open, and the watcher's sync indexes it.
        file.writeText(recipe("2"))
        app.folder.sync()
        vm.save()

        val state = withTimeout(5_000) { vm.state.first { it.error != null } }
        assertEquals(
            "This recipe was changed outside the app while you were editing. Close it and open it again to see the change.",
            state.error,
        )
        assertEquals(recipe("2"), file.readText())
        assertNull(vm.savedId.value)
    }

    @Test
    fun withNoChangeOutsideTheAppTheFormSavesTwice() = runBlocking {
        File(app.recipesDir, "soup.yaml").writeText(recipe("1"))
        app.folder.sync()
        val id = app.container.recipes.allRecipes().single().id
        val vm = editor(id)
        withTimeout(5_000) { vm.state.first { it.form != null } }
        vm.save()
        assertEquals(id, withTimeout(5_000) { vm.savedId.first { it != null } })
        vm.savedHandled()
        // A second save from the same form measures against its own first save, not the file it opened.
        vm.save()
        assertEquals(id, withTimeout(5_000) { vm.savedId.first { it != null } })
        assertNull(vm.state.value.error)
    }
}
