package com.naeblis11.mealplanner.recipes

import com.naeblis11.mealplanner.data.RecipeDetail
import com.naeblis11.mealplanner.domain.Amounts
import com.naeblis11.mealplanner.domain.Books
import com.naeblis11.mealplanner.domain.Fraction
import com.naeblis11.mealplanner.domain.JsonTree
import com.naeblis11.mealplanner.domain.OrfEditing
import com.naeblis11.mealplanner.domain.Py

data class SubstitutionView(val name: String, val amount: String, val unit: String)

data class IngredientView(
    val section: String?,
    val name: String,
    val amount: String,
    val unit: String,
    val notes: List<String>,
    val substitutions: List<SubstitutionView>,
)

data class StepView(val number: Int, val text: String, val notes: List<String>)

/** Everything the recipe page shows, with amounts already scaled when a serving size was requested. */
data class RecipeView(
    val id: Long,
    val name: String,
    val category: String?,
    val subcategory: String?,
    val imageFilename: String?,
    val rating: Int?,
    val author: String?,
    val sourceUrl: String?,
    val book: String?,
    val oven: String?,
    val notes: List<String>,
    val servings: String?,
    val servingsUnit: String?,
    val canScale: Boolean,
    val ingredients: List<IngredientView>,
    val steps: List<StepView>,
)

/** Builds the recipe page like app.py `recipe_detail`, including its servings scaling. */
object RecipeViews {
    fun build(detail: RecipeDetail, requestedServings: String? = null): RecipeView {
        val recipe = detail.recipe
        val firstYield = mapAt(JsonTree.decode(recipe.yieldsJson))
        val baseAmount = firstYield?.get("amount")
        val base = baseAmount?.let { Amounts.parseAmount(Py.str(it)) }
        val requested = requestedServings?.trim()?.takeIf { it.isNotEmpty() }?.let { Amounts.parseAmount(it) }

        var servings = baseAmount?.let { Py.str(it) }
        var ratio: Fraction? = null
        if (base != null && base != Fraction.ZERO && requested != null && requested > Fraction.ZERO) {
            ratio = requested / base
            servings = Amounts.formatAmount(requested)
        }
        fun scaled(amount: String?): String =
            (if (ratio == null) amount else Amounts.scaleAmountText(amount, ratio)) ?: ""

        val ingredients = detail.ingredients.map { row ->
            IngredientView(
                section = row.section,
                name = row.name,
                amount = scaled(row.amount),
                unit = row.unit ?: "",
                notes = OrfEditing.notesList(JsonTree.decode(row.notesJson)),
                substitutions = (JsonTree.decode(row.substitutionsJson) as? List<*>).orEmpty().mapNotNull { sub ->
                    @Suppress("UNCHECKED_CAST")
                    val map = sub as? Map<Any?, Any?> ?: return@mapNotNull null
                    val first = mapAt(map["amounts"])
                    SubstitutionView(
                        name = Py.str(map["name"]),
                        amount = scaled(first?.get("amount")?.let { Py.str(it) }),
                        unit = first?.get("unit")?.let { Py.str(it) } ?: "",
                    )
                },
            )
        }

        val temp = mapAt(JsonTree.decode(recipe.ovenTempJson))
        val oven = listOfNotNull(
            temp?.get("amount")?.let { "${Py.str(it)}\u00b0${temp["unit"]?.let { u -> Py.str(u) } ?: "F"}" },
            recipe.ovenTime,
            if (recipe.ovenFan == "On") "fan" else null,
        ).joinToString(" \u00b7 ").ifEmpty { null }

        return RecipeView(
            id = recipe.id,
            name = recipe.name,
            category = recipe.category,
            subcategory = recipe.subcategory,
            imageFilename = recipe.imageFilename,
            rating = recipe.rating,
            author = recipe.author,
            sourceUrl = recipe.sourceUrl,
            book = Books.name(recipe.sourceBookJson),
            oven = oven,
            notes = OrfEditing.notesList(JsonTree.decode(recipe.notesJson)),
            servings = servings,
            servingsUnit = firstYield?.get("unit")?.let { Py.str(it) },
            canScale = base != null && base > Fraction.ZERO,
            ingredients = ingredients,
            steps = detail.steps.mapIndexed { i, step ->
                StepView(i + 1, step.stepText, OrfEditing.notesList(JsonTree.decode(step.stepNotesJson)))
            },
        )
    }

    // The first element of a JSON list, if it is an object.
    @Suppress("UNCHECKED_CAST")
    private fun mapAt(value: Any?): Map<Any?, Any?>? = (value as? List<*>)?.firstOrNull() as? Map<Any?, Any?>
}
