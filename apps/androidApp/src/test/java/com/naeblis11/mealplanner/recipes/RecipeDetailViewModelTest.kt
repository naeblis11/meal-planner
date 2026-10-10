package com.naeblis11.mealplanner.recipes

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.naeblis11.mealplanner.MainDispatcherRule
import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.data.ShoppingRepository
import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.ServingsInput
import com.naeblis11.mealplanner.domain.YamlMap
import android.graphics.Bitmap
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecipeDetailViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private lateinit var db: AppDatabase
    private lateinit var repo: RecipeRepository
    private var id = 0L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = RecipeRepository(db, Files.createTempDirectory("images").toFile(), dispatcher = Dispatchers.Unconfined)
        @Suppress("UNCHECKED_CAST")
        val doc = RecipeYaml.load(
            "recipe_name: Soup\nyields:\n- servings: 4\ningredients:\n- Stock:\n    amounts:\n    - amount: 2\n      unit: cup\nsteps:\n- step: Simmer.\n",
        ) as YamlMap
        id = kotlinx.coroutines.runBlocking { repo.save(doc) }
    }

    private val created = mutableListOf<RecipeDetailViewModel>()

    // Everything the model launches runs on Unconfined, never a real thread, and its scope
    // is cancelled before the Main rule resets Main: a pending emission or the 5 s
    // WhileSubscribed timer must not touch Dispatchers.Main while it is being reset.
    private fun newVm(): RecipeDetailViewModel =
        RecipeDetailViewModel(id, repo, Files.createTempDirectory("images").toFile(), Dispatchers.Unconfined, Dispatchers.Unconfined)
            .also { created += it }

    @After
    fun tearDown() {
        created.forEach { it.viewModelScope.cancel() }
        db.close()
    }

    @Test
    fun scalesRatesAndDeletes() = runTest {
        val vm = newVm()
        assertEquals("2", vm.view.first { it != null }!!.ingredients.single().amount)

        vm.scaleTo("6")
        assertEquals("3", vm.view.first { it?.servings == "6" }!!.ingredients.single().amount)

        vm.setRating(4)
        assertEquals(4, vm.view.first { it?.rating == 4 }!!.rating)
        assertEquals(4, repo.doc(id)!!["rating"])
        vm.setRating(0)
        vm.view.first { it?.rating == null }
        assertEquals("None", repo.doc(id)!!["rating"])

        assertFalse(vm.deleted.value)
        vm.delete()
        vm.deleted.first { it }
        assertEquals(null, repo.doc(id))
    }

    @Test
    fun theRecipeAndOneIngredientGoOnTheShoppingListAtThePagesServings() = runTest {
        // Owner, 2026-10-09: "Add to shopping list" and each ingredient's "+ List".
        val shopping = ShoppingRepository(db, Dispatchers.Unconfined)
        val vm = RecipeDetailViewModel(
            id, repo, Files.createTempDirectory("images").toFile(), Dispatchers.Unconfined, Dispatchers.Unconfined,
            shopping = shopping,
        ).also { created += it }
        assertTrue(vm.canShop)
        vm.scaleTo("8") // doubled
        val page = vm.view.first { it?.servings == "8" }!!

        vm.addRecipeToShoppingList()
        assertEquals("Added the ingredients for Soup to your shopping list.", vm.message.first { it != null })
        assertEquals(listOf("Stock" to "4"), db.shoppingDao().allInIdOrder().map { it.name to it.amount })
        vm.messageShown()

        vm.addIngredientToShoppingList(page.ingredients.single())
        assertEquals("Added more 'Stock' to the one already on your list.", vm.message.first { it != null })
        assertEquals(listOf("Stock" to "8"), db.shoppingDao().allInIdOrder().map { it.name to it.amount })
    }

    @Test
    fun withoutAShoppingListThePageOffersNoShoppingButtons() = runTest {
        assertFalse(newVm().canShop)
    }

    @Test
    fun aRatingThatCantBeSavedIsAMessageNotACrash() = runTest {
        val row = db.recipeDao().recipe(id)!!
        db.recipeDao().updateRecipe(row.copy(rawYaml = "a: [b"))
        val vm = newVm()
        vm.setRating(3)
        assertEquals("The rating could not be saved.", vm.message.first { it != null })
    }

    @Test
    fun aDeleteThatFailsIsAMessageAndStaysOnThePage() = runTest {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER no_delete BEFORE DELETE ON recipe BEGIN SELECT RAISE(ABORT, 'locked'); END")
        val vm = newVm()
        vm.delete()
        assertEquals("The recipe could not be deleted.", vm.message.first { it != null })
        assertFalse(vm.deleted.value)
        assertEquals("Soup", repo.doc(id)!!["recipe_name"])
    }

    @Test
    fun ratingsOutsideZeroToFiveAreRefused() = runTest {
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { repo.setRating(id, 6) } }
    }

    @Test
    fun aRecipeThatCantBeShownBecomesAMessageNotACrash() = runTest {
        val row = db.recipeDao().recipe(id)!!
        db.recipeDao().updateRecipe(row.copy(notesJson = "{not json"))
        val vm = newVm()
        val collector = backgroundScope.launch(kotlinx.coroutines.Dispatchers.Unconfined) { vm.view.collect {} }
        val text = vm.loadError.first { it != null }!!
        collector.cancel()
        assertTrue(text, text.startsWith("This recipe can't be shown:"))
        assertEquals(null, vm.view.value)
    }

    @Test
    fun aNewPhotoBumpsTheVersionAndNamesTheImageAfterTheRecipe() = runTest {
        val vm = newVm()
        val png = ByteArrayOutputStream().also { Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        assertEquals(0L, vm.photoVersion.value)
        vm.setPhoto { ByteArrayInputStream(png) }
        vm.photoVersion.first { it == 1L }
        val uuid = repo.doc(id)!!["recipe_uuid"]
        assertEquals("$uuid.jpg", repo.doc(id)!!["image"])
    }

    @Test
    fun aRecipeThatCantBeShownComesBackWhenItIsFixed() = runTest {
        val dao = db.recipeDao()
        val good = dao.recipe(id)!!
        dao.updateRecipe(good.copy(notesJson = "{not json"))
        val vm = newVm()
        val collector = backgroundScope.launch(Dispatchers.Unconfined) { vm.view.collect {} }
        val text = vm.loadError.first { it != null }!!
        assertTrue(text, text.startsWith("This recipe can't be shown:"))

        // An edit or an import fixes the row: the same page shows it again.
        dao.updateRecipe(good)
        assertEquals("Soup", vm.view.first { it != null }!!.name)
        assertEquals(null, vm.loadError.value)
        collector.cancel()
    }

    @Test
    fun unreadableServingsGetTheHintAndKeepTheScale() = runTest {
        val vm = newVm()
        vm.scaleTo("8")
        vm.view.first { it?.servings == "8" }
        vm.scaleTo("lots")
        assertEquals(ServingsInput.HINT, vm.message.value)
        assertEquals("8", vm.view.value!!.servings)
    }
}
