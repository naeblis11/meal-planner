package com.naeblis11.mealplanner.recipes

import com.naeblis11.mealplanner.data.RecipeDetail
import com.naeblis11.mealplanner.data.RecipeEntity
import com.naeblis11.mealplanner.data.RecipeIngredientEntity
import com.naeblis11.mealplanner.data.RecipeStepEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeViewsTest {
    private fun recipe(
        yields: String? = """[{"amount":4,"unit":"servings"}]""",
        ovenTemp: String? = null,
        ovenTime: String? = null,
        ovenFan: String? = null,
        notes: String? = null,
        sourceBook: String? = null,
    ) = RecipeEntity(
        id = 7, recipeUuid = "u", name = "Soup", author = "Ann", sourceAuthorsJson = null, sourceUrl = null,
        sourceBookJson = sourceBook, ovenTempJson = ovenTemp, ovenFan = ovenFan, ovenTime = ovenTime, yieldsJson = yields,
        notesJson = notes, category = "Soups & Stews", subcategory = null, imageFilename = "u.jpg", rating = 4,
        rawYaml = "",
    )

    private fun ingredient(order: Int, name: String, amount: String?, unit: String?, section: String? = null,
                           notes: String? = null, subs: String? = null) = RecipeIngredientEntity(
        recipeId = 7, orderNum = order, name = name, usdaNum = null, amount = amount, unit = unit, section = section,
        amountsJson = "[]", processingJson = null, notesJson = notes, substitutionsJson = subs,
    )

    private val detail = RecipeDetail(
        recipe(notes = """["Freezes well."]"""),
        listOf(
            ingredient(0, "Stock", "1 1/2", "cup", notes = """["low salt"]""",
                subs = """[{"name":"Water","amounts":[{"amount":"1","unit":"cup"}]}]"""),
            ingredient(1, "Salt", "to taste", "", section = "Seasoning"),
        ),
        listOf(RecipeStepEntity(recipeId = 7, orderNum = 0, stepText = "Simmer.", stepNotesJson = """["gently"]""", haccpJson = null)),
    )

    @Test
    fun buildsTheRecipePage() {
        val view = RecipeViews.build(detail)
        assertEquals("Soup", view.name)
        assertEquals("4", view.servings)
        assertEquals("servings", view.servingsUnit)
        assertTrue(view.canScale)
        assertEquals(listOf("Freezes well."), view.notes)
        assertEquals("1 1/2", view.ingredients[0].amount)
        assertEquals(listOf("low salt"), view.ingredients[0].notes)
        assertEquals(listOf(SubstitutionView("Water", "1", "cup")), view.ingredients[0].substitutions)
        assertEquals("Seasoning", view.ingredients[1].section)
        assertEquals(listOf(StepView(1, "Simmer.", listOf("gently"))), view.steps)
    }

    @Test
    fun scalesAmountsAndSubstitutionsButLeavesTextAlone() {
        val view = RecipeViews.build(detail, requestedServings = " 8 ")
        assertEquals("8", view.servings)
        assertEquals("3", view.ingredients[0].amount)
        assertEquals("2", view.ingredients[0].substitutions.single().amount)
        assertEquals("to taste", view.ingredients[1].amount)
    }

    @Test
    fun ignoresAnUnusableServingsRequest() {
        assertEquals("1 1/2", RecipeViews.build(detail, "0").ingredients[0].amount)
        assertEquals("1 1/2", RecipeViews.build(detail, "lots").ingredients[0].amount)
        val noYield = RecipeViews.build(detail.copy(recipe = recipe(yields = null)), "8")
        assertFalse(noYield.canScale)
        assertNull(noYield.servings)
        assertEquals("1 1/2", noYield.ingredients[0].amount)
    }

    @Test
    fun describesTheOven() {
        val view = RecipeViews.build(detail.copy(recipe = recipe(ovenTemp = """[{"amount":350,"unit":"F"}]""", ovenTime = "45 min", ovenFan = "On")))
        assertEquals("350\u00b0F \u00b7 45 min \u00b7 fan", view.oven)
        assertNull(RecipeViews.build(detail).oven)
    }

    @Test
    fun namesTheCookbook() {
        assertNull(RecipeViews.build(detail).book)
        val fromBook = detail.copy(recipe = recipe(sourceBook = "\"Flanders Family Cookbook\""))
        assertEquals("Flanders Family Cookbook", RecipeViews.build(fromBook).book)
    }
}
