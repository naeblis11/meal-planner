package com.naeblis11.mealplanner.shopping

/** Shopping-list wording shared by the meal plan and the shopping list. */
object ShoppingMessages {
    fun addedWeek(plannedMeals: Int): String =
        if (plannedMeals == 0) "Nothing is planned for that week yet." else "Added the week's meals to your shopping list."

    const val UPDATE_FAILED = "The shopping list could not be updated."
}
