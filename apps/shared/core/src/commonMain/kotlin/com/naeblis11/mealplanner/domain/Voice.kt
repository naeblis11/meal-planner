package com.naeblis11.mealplanner.domain

import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.format.TextStyle
import java.util.Locale

/**
 * Voice commands for the Alexa skill: port of voice.py. Turns what Alexa heard into the app's own values (fraction
 * amounts, known aisles, meal slots, dates), matches a spoken recipe name to the library, and composes the sentence
 * Alexa says back. Pure functions; the desktop server's routes glue them to the repositories.
 */
object Voice {
    private const val S = Py.RE_SPACE

    /** Answers to "How much?" / "Which aisle?" that mean "leave it blank". */
    val SKIP_WORDS: Set<String> = setOf(
        "skip", "none", "not sure", "no", "nothing", "don't know", "dont know",
        "no amount", "no idea", "i don't know",
    )

    private val HALF: Fraction = Fraction.of(1, 2)

    private val NUMBER_WORDS: Map<String, Fraction> = listOf(
        "zero" to 0L, "one" to 1L, "two" to 2L, "three" to 3L, "four" to 4L, "five" to 5L,
        "six" to 6L, "seven" to 7L, "eight" to 8L, "nine" to 9L, "ten" to 10L, "eleven" to 11L,
        "twelve" to 12L, "thirteen" to 13L, "fourteen" to 14L, "fifteen" to 15L,
        "sixteen" to 16L, "seventeen" to 17L, "eighteen" to 18L, "nineteen" to 19L,
        "twenty" to 20L, "thirty" to 30L, "forty" to 40L, "fifty" to 50L, "sixty" to 60L,
        "seventy" to 70L, "eighty" to 80L, "ninety" to 90L,
        "a" to 1L, "an" to 1L, "a couple" to 2L, "couple" to 2L, "a pair" to 2L,
        "dozen" to 12L, "a dozen" to 12L,
    ).associate { (word, n) -> word to Fraction.of(n) } + mapOf("half" to HALF, "a half" to HALF, "one half" to HALF)

    // Sentence tails Alexa sometimes folds into the item slot ("add milk to cart" -> "milk to cart"); only at the end.
    private val DESTINATION_TAIL = Regex(
        "(?:^|$S+)(?:to|in|on|into|onto)$S+(?:the$S+|my$S+)?(?:shopping$S+)?(?:cart|list|pantry)$",
        RegexOption.IGNORE_CASE,
    )

    private val NOT_NAME = Regex("[^a-z0-9 ]+")

    const val FUZZY_MIN_RATIO = 0.6
    const val FUZZY_TIE_MARGIN = 0.05
    const val MAX_CANDIDATES = 3

    const val SPEECH_NOTHING_HEARD = "I didn't catch what to add."
    const val SPEECH_NEED_A_DAY = "I need a specific day, like Thursday."
    const val SPEECH_NOT_SET_UP = "The meal planner's voice API isn't set up yet."
    const val SPEECH_REFUSED = "The meal planner refused the request. Check the token in Home Assistant."

    /** New on the desktop: the command failed inside the app (the Python server answered with an HTML error page). */
    const val SPEECH_FAILED = "The meal planner couldn't do that just now. Try again in a moment."

    // How the app's unit abbreviations are read aloud.
    private val SPOKEN_UNITS: Map<String, String> = mapOf(
        "tsp" to "teaspoon", "tbsp" to "tablespoon", "fl oz" to "fluid ounce", "cup" to "cup",
        "pt" to "pint", "qt" to "quart", "gal" to "gallon", "oz" to "ounce", "lb" to "pound",
        "ml" to "milliliter", "l" to "liter", "g" to "gram", "kg" to "kilogram",
        "clove" to "clove", "slice" to "slice", "can" to "can", "package" to "package",
        "pinch" to "pinch", "dash" to "dash",
    )

    enum class MatchStatus { FOUND, NONE, AMBIGUOUS }

    data class Match(val status: MatchStatus, val name: String? = null, val candidates: List<String> = emptyList())

    enum class ShoppingStatus { ADDED, MERGED, DUPLICATE }

    enum class PantryStatus { ADDED, RESTORED, DUPLICATE }

    /** The trimmed text, or null when Alexa sent nothing usable: blank, or a word that means "skip this". */
    fun given(value: String?): String? {
        val text = Py.strip(value ?: "")
        if (text.isEmpty() || text.lowercase(Locale.ROOT) in SKIP_WORDS) return null
        return text
    }

    /** The item named, or null; like [given], but a trailing "to the cart" / "in the pantry" is dropped first. */
    fun itemName(value: String?): String? {
        val text = given(value) ?: return null
        return given(DESTINATION_TAIL.replace(text, ""))
    }

    /** Alexa's quantity ("2", "1.5", "two", "a dozen", "half") as the app's fraction text, or null. */
    fun normalizeQuantity(text: String?): String? {
        val heard = given(text) ?: return null
        val lowered = heard.lowercase(Locale.ROOT)
        NUMBER_WORDS[lowered]?.let { return Amounts.formatAmount(it) }
        val value = Fraction.parse(lowered) ?: return null
        return Amounts.formatAmount(value)
    }

    /** The app's spelling of a spoken unit ("pounds" -> "lb"); unknown words kept, lowercased; none without a quantity. */
    fun normalizeUnit(text: String?, quantity: String?): String? {
        val heard = given(text) ?: return null
        if (quantity == null) return null
        return Units.normalizeUnit(heard)
    }

    // Lowercase, "&" and "and" interchangeable, whitespace collapsed: "Dairy and Eggs" matches "Dairy & Eggs".
    private fun foldAisle(text: String): String = Py.split(text.lowercase(Locale.ROOT).replace("&", "and")).joinToString(" ")

    /** One of the app's aisles, matched case-insensitively with "&"/"and" folded, else null. */
    fun normalizeAisle(text: String?): String? {
        val heard = given(text) ?: return null
        val folded = foldAisle(heard)
        return GroceryCategories.AISLE_ORDER.firstOrNull { foldAisle(it) == folded }
    }

    /** One of the calendar's slots; anything unrecognised means Dinner. */
    fun normalizeMeal(text: String?): String {
        val heard = given(text) ?: return "Dinner"
        return Week.SLOTS.firstOrNull { it.lowercase(Locale.ROOT) == heard.lowercase(Locale.ROOT) } ?: "Dinner"
    }

    /** The day Alexa resolved ("2026-09-17"); blank means today. Weeks, months, seasons and the like come back null. */
    fun parseDay(text: String?, today: LocalDate): LocalDate? {
        val heard = given(text) ?: return today
        // date.fromisoformat plus the round trip: exactly YYYY-MM-DD in ASCII digits, a year Python's date can hold.
        if (!ISO_DAY.matches(heard)) return null
        val parsed = try {
            LocalDate.parse(heard)
        } catch (e: DateTimeParseException) {
            return null
        }
        return if (parsed.year in 1..9999 && parsed.toString() == heard) parsed else null
    }

    private val ISO_DAY = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")

    private fun normaliseName(text: String): String = Py.split(NOT_NAME.replace(text.lowercase(Locale.ROOT), " ")).joinToString(" ")

    // len() in Python counts code points.
    private fun length(text: String): Int = text.codePointCount(0, text.length)

    /**
     * Which library recipe the user meant. An exact (normalised) match wins; then containment either way, shortest
     * name first; then difflib similarity with a floor. Contenders too close to call come back AMBIGUOUS.
     */
    fun matchRecipe(spoken: String, names: List<String>): Match {
        val wanted = normaliseName(spoken)
        if (wanted.isEmpty() || names.isEmpty()) return Match(MatchStatus.NONE)
        val normalised = names.map { it to normaliseName(it) }

        normalised.firstOrNull { it.second == wanted }?.let { return Match(MatchStatus.FOUND, it.first) }

        val contained = normalised.filter { (_, norm) -> wanted in norm || norm in wanted }.map { it.first }
            .sortedWith(compareBy<String>({ length(it) }, { it }))
        if (contained.isNotEmpty()) {
            if (contained.size > 1 && length(contained[0]) == length(contained[1])) {
                val tied = contained.filter { length(it) == length(contained[0]) }
                return Match(MatchStatus.AMBIGUOUS, candidates = tied.take(MAX_CANDIDATES))
            }
            return Match(MatchStatus.FOUND, contained[0])
        }

        // sorted(..., reverse=True) on (ratio, name): best ratio first, ties by name, descending.
        val scored = normalised.map { (name, norm) -> PyDifflib.ratio(wanted, norm) to name }
            .sortedWith(compareByDescending<Pair<Double, String>> { it.first }.thenByDescending { it.second })
        val (bestRatio, bestName) = scored[0]
        if (bestRatio < FUZZY_MIN_RATIO) return Match(MatchStatus.NONE)
        val close = scored.filter { bestRatio - it.first <= FUZZY_TIE_MARGIN }.map { it.second }
        if (close.size > 1) return Match(MatchStatus.AMBIGUOUS, candidates = close.take(MAX_CANDIDATES))
        return Match(MatchStatus.FOUND, bestName)
    }

    /**
     * One field of a voice command's JSON body (app.py's _voice_field): text as it is; a number (Home Assistant's
     * templates turn "2" into 2) as Python's str() writes it; anything else, true/false included, "".
     */
    fun field(body: Map<*, *>, name: String): String = when (val value = body[name]) {
        is String -> value
        is Boolean -> ""
        is Long, is Int, is java.math.BigInteger, is Double -> Py.str(value)
        else -> ""
    }

    /** "2 gallons", "1 pound", "1 1/2 cups", "2 bags", "3", or "" with no amount. Only the app's own units are pluralised. */
    fun spokenAmount(amount: String?, unit: String?): String {
        if (amount.isNullOrEmpty()) return ""
        if (unit.isNullOrEmpty()) return amount
        val word = SPOKEN_UNITS[unit] ?: return "$amount $unit"
        return if (amount != "1") "$amount ${word}s" else "$amount $word"
    }

    /** "Thursday, September 17". */
    fun spokenDay(day: LocalDate): String =
        "${day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)}, ${day.month.getDisplayName(TextStyle.FULL, Locale.US)} ${day.dayOfMonth}"

    // name[:1].upper() + name[1:]
    private fun sentenceCase(name: String): String {
        if (name.isEmpty()) return name
        val first = name.offsetByCodePoints(0, 1)
        return name.substring(0, first).uppercase(Locale.ROOT) + name.substring(first)
    }

    fun shoppingSpeech(status: ShoppingStatus, name: String, amount: String?, unit: String?, aisle: String?): String = when (status) {
        ShoppingStatus.DUPLICATE -> "${sentenceCase(name)} is already on your shopping list."
        ShoppingStatus.MERGED -> "${sentenceCase(name)} was already on your shopping list; it's now ${spokenAmount(amount, unit)}."
        ShoppingStatus.ADDED -> {
            val what = if (!amount.isNullOrEmpty()) "${spokenAmount(amount, unit)} of $name" else name
            val where = if (!aisle.isNullOrEmpty()) ", under $aisle" else ""
            "Added $what to your shopping list$where."
        }
    }

    fun pantrySpeech(status: PantryStatus, name: String): String = when (status) {
        PantryStatus.DUPLICATE -> "${sentenceCase(name)} is already in your pantry."
        PantryStatus.RESTORED -> "Put $name back in your pantry."
        PantryStatus.ADDED -> "Added $name to your pantry."
    }

    fun mealSpeech(recipe: String, slot: String, day: LocalDate, previous: String?): String {
        val whenSaid = "${slot.lowercase(Locale.ROOT)} on ${spokenDay(day)}"
        if (previous == recipe) return "$recipe is already planned for $whenSaid."
        val replacing = if (!previous.isNullOrEmpty()) ", replacing $previous" else ""
        return "Added $recipe for $whenSaid$replacing."
    }

    fun noMatchSpeech(spoken: String): String = "I couldn't find a recipe like '$spoken'."

    fun ambiguousSpeech(candidates: List<String>): String {
        val listed = if (candidates.size == 2) {
            "${candidates[0]} and ${candidates[1]}"
        } else {
            candidates.dropLast(1).joinToString(", ") + ", and ${candidates.last()}"
        }
        return "I found $listed. Which one?"
    }
}
