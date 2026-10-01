package com.naeblis11.mealplanner.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One planned meal: a recipe in one day's Breakfast, Lunch or Dinner. [date] is ISO
 * (`2026-10-01`), so a week is a BETWEEN on text. Deleting the recipe deletes its
 * plan rows (RecipeRepository does it explicitly; the cascade is a backstop).
 */
@Entity(
    tableName = "meal_plan",
    foreignKeys = [ForeignKey(RecipeEntity::class, ["id"], ["recipe_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["date", "slot"], unique = true), Index(value = ["recipe_id"])],
)
data class MealPlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val slot: String,
    @ColumnInfo(name = "recipe_id") val recipeId: Long,
    /** Servings this meal is planned at; null means the recipe's own yield. Never rewrites the recipe. */
    val servings: String?,
)

/** Something on hand. A crossed-out ([active] false) item has run out and needs buying. */
@Entity(tableName = "pantry_item", indices = [Index(value = ["name"], unique = true)])
data class PantryItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    @ColumnInfo(name = "exact_match") val exactMatch: Boolean = false,
    val aisle: String? = null,
    val active: Boolean = true,
    @ColumnInfo(name = "added_on") val addedOn: String? = null,
)

/** One line of the shopping list. */
@Entity(tableName = "shopping_list_item")
data class ShoppingItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val amount: String? = null,
    val unit: String? = null,
    val aisle: String? = null,
    @ColumnInfo(name = "in_pantry") val inPantry: Boolean = false,
    val checked: Boolean = false,
)

/** The aisle the user chose for an ingredient; it beats the keyword guess from then on. */
@Entity(tableName = "ingredient_aisle", indices = [Index(value = ["name"], unique = true)])
data class IngredientAisleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val aisle: String,
)

/** A planned meal with its recipe's name and photo, for the week view. */
data class PlannedMealRow(
    val date: String,
    val slot: String,
    @ColumnInfo(name = "recipe_id") val recipeId: Long,
    val servings: String?,
    @ColumnInfo(name = "recipe_name") val recipeName: String,
    @ColumnInfo(name = "image_filename") val imageFilename: String?,
)

/** What "Add this week" needs from one planned meal. */
data class PlannedForShopping(
    @ColumnInfo(name = "recipe_id") val recipeId: Long,
    val servings: String?,
    @ColumnInfo(name = "yields_json") val yieldsJson: String?,
)

data class IngredientLineRow(val name: String, val amount: String?, val unit: String?)

data class PantryRuleRow(val name: String, @ColumnInfo(name = "exact_match") val exactMatch: Boolean)
