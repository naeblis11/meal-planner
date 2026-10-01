package com.naeblis11.mealplanner.domain

import java.math.BigInteger

/** Written cooking amounts: port of the amount half of unit_conversion.py. */
object Amounts {
    private val WHITESPACE = Regex("${Py.RE_SPACE}+")

    /** "2", "1/2", "1 1/2", "1.5" -> exact value; null for anything else. */
    fun parseAmount(text: String?): Fraction? {
        if (text == null) return null
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split(WHITESPACE)
        return when {
            parts.size == 1 -> Fraction.parse(parts[0])
            parts.size == 2 && "/" in parts[1] -> {
                val whole = Fraction.parse(parts[0]) ?: return null
                val fraction = Fraction.parse(parts[1]) ?: return null
                whole + fraction
            }
            else -> null
        }
    }

    /** 3/2 -> "1 1/2". Uses floor division like Python's divmod, so -1/2 -> "-1 1/2". */
    fun formatAmount(value: Fraction): String {
        var whole = value.numerator.divide(value.denominator)
        var remainder = value.numerator - whole * value.denominator
        if (remainder.signum() < 0) {
            whole -= BigInteger.ONE
            remainder += value.denominator
        }
        if (remainder.signum() == 0) return whole.toString()
        val fraction = "$remainder/${value.denominator}"
        return if (whole.signum() != 0) "$whole $fraction" else fraction
    }

    /** Multiply a written amount by [ratio]; text that isn't a number comes back untouched. */
    fun scaleAmountText(amountText: String?, ratio: Fraction): String? {
        val parsed = parseAmount(amountText) ?: return amountText
        return formatAmount(parsed * ratio)
    }

    /** Multiplier from [base] servings to [planned]; 1 when either is missing, unparseable or not positive. */
    fun servingsRatio(planned: String?, base: String?): Fraction {
        val plannedValue = parseAmount(planned) ?: return Fraction.ONE
        val baseValue = parseAmount(base) ?: return Fraction.ONE
        if (plannedValue <= Fraction.ZERO || baseValue <= Fraction.ZERO) return Fraction.ONE
        return plannedValue / baseValue
    }
}
