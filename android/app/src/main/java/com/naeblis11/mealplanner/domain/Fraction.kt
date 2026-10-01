package com.naeblis11.mealplanner.domain

import java.math.BigInteger

/**
 * An exact rational number, always in lowest terms with a positive
 * denominator. Cooking amounts are never floating point, as in the Python
 * app, which uses fractions.Fraction; this mirrors the parts of it the app
 * needs, including how text is parsed.
 */
class Fraction private constructor(
    val numerator: BigInteger,
    val denominator: BigInteger,
) : Comparable<Fraction> {

    operator fun plus(other: Fraction): Fraction =
        of(numerator * other.denominator + other.numerator * denominator, denominator * other.denominator)

    operator fun times(other: Fraction): Fraction =
        of(numerator * other.numerator, denominator * other.denominator)

    operator fun div(other: Fraction): Fraction =
        of(numerator * other.denominator, denominator * other.numerator)

    override fun compareTo(other: Fraction): Int =
        (numerator * other.denominator).compareTo(other.numerator * denominator)

    override fun equals(other: Any?): Boolean =
        other is Fraction && numerator == other.numerator && denominator == other.denominator

    override fun hashCode(): Int = 31 * numerator.hashCode() + denominator.hashCode()

    /**
     * "n/d" -- the parity fixtures' encoding, e.g. "2/1", "-1/2". This is the
     * fixture/debug encoding; UI text must use [Amounts.formatAmount].
     */
    override fun toString(): String = "$numerator/$denominator"

    companion object {
        val ZERO: Fraction = of(0)
        val ONE: Fraction = of(1)

        fun of(n: Long, d: Long = 1): Fraction = of(BigInteger.valueOf(n), BigInteger.valueOf(d))

        fun of(n: BigInteger, d: BigInteger): Fraction {
            require(d.signum() != 0) { "zero denominator" }
            val g = n.gcd(d) // never zero, since d is not
            val sign = if (d.signum() < 0) BigInteger.ONE.negate() else BigInteger.ONE
            return Fraction(n / g * sign, d / g * sign)
        }

        // Python's fractions._RATIONAL_FORMAT: sign, then "n/d" or a decimal
        // with an optional exponent; underscores between digits allowed.
        private val PATTERN = Regex(
            """\s*([-+]?)(?=\d|\.\d)(\d+(?:_\d+)*)?""" +
                """(?:/(\d+(?:_\d+)*)|(?:\.(\d+(?:_\d+)*)?)?(?:[eE]([-+]?\d+(?:_\d+)*))?)\s*""",
        )

        // Beyond this Python would build an enormous number; nobody's recipe
        // says 1e5000 cups, so treat it as unparseable instead.
        private const val MAX_EXPONENT = 1000

        /** Python's `Fraction(text)`, or null wherever Python raises. */
        fun parse(text: String): Fraction? {
            val match = PATTERN.matchEntire(text) ?: return null
            val (sign, numText, denText, decimalText, expText) = match.destructured
            var num = if (numText.isEmpty()) BigInteger.ZERO else BigInteger(numText.replace("_", ""))
            var den = BigInteger.ONE
            if (denText.isNotEmpty()) {
                den = BigInteger(denText.replace("_", ""))
                if (den.signum() == 0) return null
            } else {
                if (decimalText.isNotEmpty()) {
                    val digits = decimalText.replace("_", "")
                    val scale = BigInteger.TEN.pow(digits.length)
                    num = num * scale + BigInteger(digits)
                    den = scale
                }
                if (expText.isNotEmpty()) {
                    val exp = expText.replace("_", "").toIntOrNull() ?: return null
                    if (exp > MAX_EXPONENT || exp < -MAX_EXPONENT) return null
                    if (exp >= 0) num *= BigInteger.TEN.pow(exp) else den *= BigInteger.TEN.pow(-exp)
                }
            }
            if (sign == "-") num = num.negate()
            return of(num, den)
        }
    }
}
