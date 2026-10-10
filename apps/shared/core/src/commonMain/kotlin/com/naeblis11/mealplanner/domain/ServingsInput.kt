package com.naeblis11.mealplanner.domain

/** Servings the user types: Scale on the recipe page, and a planned meal's servings. */
object ServingsInput {
    /** The Pi's "Enter a valid serving amount.", with examples. */
    const val HINT = "Enter a valid serving amount, like 4, 1/2 or 1.5."

    sealed interface Result {
        data object Blank : Result

        /** A positive amount, normalised as the Pi formats amounts ("1.5" -> "1 1/2"). */
        data class Valid(val servings: String) : Result

        data object Invalid : Result
    }

    fun read(text: String?): Result {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) return Result.Blank
        val value = Amounts.parseAmount(trimmed) ?: return Result.Invalid
        return if (value > Fraction.ZERO) Result.Valid(Amounts.formatAmount(value)) else Result.Invalid
    }
}
