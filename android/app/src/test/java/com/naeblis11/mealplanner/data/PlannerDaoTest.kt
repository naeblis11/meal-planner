package com.naeblis11.mealplanner.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlannerDaoTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun recipe(name: String): Long =
        db.recipeDao().insertRecipe(
            RecipeEntity(
                recipeUuid = "u-$name", name = name, author = null, sourceAuthorsJson = null, sourceUrl = null,
                sourceBookJson = null, ovenTempJson = null, ovenFan = null, ovenTime = null, yieldsJson = null,
                notesJson = null, category = null, subcategory = null, imageFilename = null, rating = null, rawYaml = "",
            ),
        )

    @Test
    fun aSlotHoldsOneMealAndARecipeDeleteCascades() = runTest {
        val soup = recipe("Soup")
        val stew = recipe("Stew")
        val plan = db.mealPlanDao()
        plan.put(MealPlanEntity(date = "2026-09-28", slot = "Dinner", recipeId = soup, servings = "6"))
        plan.put(MealPlanEntity(date = "2026-09-28", slot = "Dinner", recipeId = stew, servings = null))
        plan.put(MealPlanEntity(date = "2026-10-04", slot = "Lunch", recipeId = soup, servings = null))
        plan.put(MealPlanEntity(date = "2026-10-05", slot = "Lunch", recipeId = soup, servings = null))

        val week = plan.observeRange("2026-09-28", "2026-10-04").first()
        assertEquals(listOf("2026-09-28 Dinner Stew null", "2026-10-04 Lunch Soup null"), week.map { "${it.date} ${it.slot} ${it.recipeName} ${it.servings}" })
        assertTrue(plan.recipeExists(soup))

        db.recipeDao().deleteRecipe(soup)
        assertFalse(plan.recipeExists(soup))
        assertEquals(listOf("Stew"), plan.observeRange("2026-01-01", "2026-12-31").first().map { it.recipeName })
    }

    @Test
    fun pantryNamesAreUniqueWhateverTheirCase() = runTest {
        val pantry = db.pantryDao()
        val id = pantry.insertOrIgnore(PantryItemEntity(name = "Salt", addedOn = "2026-09-01"))
        assertEquals(-1L, pantry.insertOrIgnore(PantryItemEntity(name = "SALT")))
        assertEquals(id, pantry.byName("salt")!!.id)

        pantry.markOut(id)
        pantry.putBack(id, "2026-10-01")
        assertEquals("2026-10-01", pantry.item(id)!!.addedOn)
        pantry.putBack(id, "2026-10-02") // already on hand: the date stays
        assertEquals("2026-10-01", pantry.item(id)!!.addedOn)
        pantry.toggleExactMatch(id)
        assertTrue(pantry.item(id)!!.exactMatch)
    }

    @Test
    fun aRememberedAisleIsOnePerIngredient() = runTest {
        val shopping = db.shoppingDao()
        shopping.rememberAisle(IngredientAisleEntity(name = "Flour", aisle = "Bakery"))
        shopping.rememberAisle(IngredientAisleEntity(name = "flour", aisle = "Dry Goods & Pasta"))
        assertEquals(listOf("Dry Goods & Pasta"), shopping.knownAisles().map { it.aisle })
        shopping.forgetAisle("FLOUR")
        assertEquals(null, shopping.rememberedAisle("flour"))
    }
}
