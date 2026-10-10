package com.naeblis11.mealplanner.domain

/** One ingredient line from a web page, split as recipe_extraction.parse_ingredient_line splits it. */
data class WebIngredient(val name: String, val amount: String, val unit: String, val notes: List<String>?)

/** What WebExtraction.checkPayload made of the extension's recipe: fine, or which rule it broke. */
enum class WebPayloadCheck { COMPLETE, MISSING, ADDRESS_TOO_LONG, IMAGE_ADDRESS_TOO_LONG, FIELD_TOO_LONG }

/**
 * The Chrome extension's recipe (Schema.org JSON-LD fields, already plain strings) as Open Recipe Format: port of
 * recipe_extraction.py, without the network (the desktop fetches the photo), plus app.py's check of the payload.
 * Pure functions.
 */
object WebExtraction {
    private val UNICODE_FRACTIONS: List<Pair<Char, String>> = listOf(
        '\u00BC' to "1/4", '\u00BD' to "1/2", '\u00BE' to "3/4",
        '\u2153' to "1/3", '\u2154' to "2/3",
        '\u2155' to "1/5", '\u2156' to "2/5", '\u2157' to "3/5", '\u2158' to "4/5",
        '\u2159' to "1/6", '\u215A' to "5/6",
        '\u215B' to "1/8", '\u215C' to "3/8", '\u215D' to "5/8", '\u215E' to "7/8",
    )
    private val FRACTION_CHARS: String = UNICODE_FRACTIONS.joinToString("") { it.first.toString() }

    private const val D = Py.RE_DIGIT
    private const val S = Py.RE_SPACE

    // (?d): Python's "." stops only at "\n", and its "$" matches only at the end or before a final "\n"; UNIX_LINES
    // gives Java's the same meaning.
    private val AMOUNT = Regex("(?d)^((?:$D+$S+)?$D+(?:[/.]$D+)?)(?:$S*(?:-|to)$S*$D+(?:[/.]$D+)?)?$S*(.*)$")
    private val PAREN = Regex("(?d)^\\(([^)]*)\\)$S*(.*)$")
    private val SPACED_MIXED_FRACTION = Regex("(?d)^($D+)$S+([$FRACTION_CHARS])(.*)$")
    private val SCHEME = Regex("[A-Za-z][A-Za-z0-9+.-]*")

    // Hostile input must not hang the regexes: recipes are well under these. A line past the cap is not split (a
    // deliberate difference from Python, which has no cap), and isComplete rejects the payload before it gets here.
    private const val MAX_TEXT_CHARS = 2000
    private const val MAX_LINES = 500

    // "tbsp." or "cups," still match the known-unit lookup; says whether a comma was stripped, for the note split.
    private fun unitCandidate(phrase: String): Pair<String, Boolean> {
        val stripped = phrase.trimEnd { it == '.' || it == ',' || it == ';' }
        return stripped to (',' in phrase.substring(stripped.length))
    }

    private fun rebuildRemainder(rest: String, hadComma: Boolean): String =
        if (!hadComma) rest else if (rest.isNotEmpty()) ", $rest" else ","

    // "1/2 cup" from a leading vulgar fraction; "1 1/2 cups" from the spaced "1 <fraction> cups" (the glued form is not handled, as in Python).
    private fun normalizeLeadingFraction(text: String): String {
        for ((char, fraction) in UNICODE_FRACTIONS) {
            if (text.startsWith(char)) return fraction + text.substring(1)
        }
        val mixed = SPACED_MIXED_FRACTION.find(text) ?: return text
        val (whole, char, rest) = mixed.destructured
        val fraction = UNICODE_FRACTIONS.first { it.first == char[0] }.second
        return "$whole $fraction$rest"
    }

    fun parseIngredientLine(line: String): WebIngredient {
        if (line.length > MAX_TEXT_CHARS) return WebIngredient(Py.strip(line), "", "", null)
        val text = normalizeLeadingFraction(Py.strip(line))
        if (text.isEmpty()) return WebIngredient("", "", "", null)

        val match = AMOUNT.find(text) ?: return WebIngredient(text, "", "", null)
        val amount = match.groupValues[1]
        var remainder = Py.strip(match.groupValues[2])

        val notes = mutableListOf<String>()
        PAREN.find(remainder)?.let {
            notes += Py.strip(it.groupValues[1])
            remainder = Py.strip(it.groupValues[2])
        }

        var unit = ""
        val tokens = Py.split(remainder)
        if (tokens.size >= 2) {
            val (candidate, hadComma) = unitCandidate("${tokens[0]} ${tokens[1]}")
            if (Units.isKnownUnitWord(candidate)) {
                unit = candidate
                remainder = rebuildRemainder(tokens.drop(2).joinToString(" "), hadComma)
            }
        }
        if (unit.isEmpty() && tokens.isNotEmpty()) {
            val (candidate, hadComma) = unitCandidate(tokens[0])
            if (Units.isKnownUnitWord(candidate)) {
                unit = candidate
                remainder = rebuildRemainder(tokens.drop(1).joinToString(" "), hadComma)
            }
        }

        // A parenthetical can also follow the unit: "1 can (15 oz) black beans".
        PAREN.find(remainder)?.let {
            notes += Py.strip(it.groupValues[1])
            remainder = Py.strip(it.groupValues[2])
        }

        val comma = remainder.indexOf(',')
        val name = if (comma >= 0) {
            val trailing = Py.strip(remainder.substring(comma + 1))
            if (trailing.isNotEmpty()) notes += trailing
            remainder.substring(0, comma)
        } else {
            remainder
        }
        return WebIngredient(Py.strip(name), amount, unit, notes.ifEmpty { null })
    }

    /** urlparse(url).netloc: "www.example.com" from "https://www.example.com/soup"; "" for no address or no "//". */
    fun domainFromUrl(url: Any?): String {
        if (url !is String || url.isEmpty()) return ""
        // urlsplit drops leading control characters and spaces, and every tab and line break.
        var rest = url.trimStart { it <= ' ' }.filterNot { it == '\t' || it == '\r' || it == '\n' }
        val colon = rest.indexOf(':')
        if (colon > 0 && SCHEME.matches(rest.substring(0, colon))) rest = rest.substring(colon + 1)
        if (!rest.startsWith("//")) return ""
        val host = rest.substring(2)
        val end = host.indexOfFirst { it == '/' || it == '?' || it == '#' }
        return if (end < 0) host else host.substring(0, end)
    }

    // str(value or "").strip()
    private fun text(value: Any?): String = if (Py.truthy(value)) Py.strip(Py.str(value)) else ""

    /** [checkPayload] found nothing wrong. */
    fun isComplete(payload: Map<*, *>): Boolean = checkPayload(payload) == WebPayloadCheck.COMPLETE

    /**
     * app.py's check before anything is staged: a name, and non-empty lists of ingredient lines and steps. The lines
     * and steps must be text (the Python server failed with a 500 on anything else). Also refuses a payload over the
     * size caps, which Python does not: 2000 characters in the title, any line or step, the yield or the author; 8192
     * in the page's address and in the photo's; 500 lines or steps. The yield, the author and the two addresses may be
     * missing or null, but not a list or a map (the extension sends text or null; Python would str() one). Reported
     * in this order: [WebPayloadCheck.MISSING] (an over-cap title, line or step, or a list or map, is one too), then
     * [WebPayloadCheck.ADDRESS_TOO_LONG], then [WebPayloadCheck.IMAGE_ADDRESS_TOO_LONG], then
     * [WebPayloadCheck.FIELD_TOO_LONG].
     */
    fun checkPayload(payload: Map<*, *>): WebPayloadCheck {
        val name = payload["name"]
        if (text(name).isEmpty() || Py.str(name).length > MAX_TEXT_CHARS) return WebPayloadCheck.MISSING
        if (!isTextList(payload["ingredients"]) || !isTextList(payload["steps"])) return WebPayloadCheck.MISSING
        val (yieldLength, authorLength, addressLength, imageAddressLength) =
            OPTIONAL_TEXT.map { scalarLength(payload[it]) ?: return WebPayloadCheck.MISSING }
        return when {
            addressLength > MAX_ADDRESS_CHARS -> WebPayloadCheck.ADDRESS_TOO_LONG
            imageAddressLength > MAX_ADDRESS_CHARS -> WebPayloadCheck.IMAGE_ADDRESS_TOO_LONG
            yieldLength > MAX_TEXT_CHARS || authorLength > MAX_TEXT_CHARS -> WebPayloadCheck.FIELD_TOO_LONG
            else -> WebPayloadCheck.COMPLETE
        }
    }

    // parseYieldText reads every leading digit as one number, and a nested value's text has no bound short of walking
    // all of it: so text, a number or a flag within the cap, or nothing. A page's address, tracking and all, may pass
    // 2000 characters; 8 KB is far past any real one, and the photo's address (which the desktop then fetches) gets
    // the same cap.
    private val OPTIONAL_TEXT = listOf("yield_text", "author", "source_url", "image_url")
    private const val MAX_ADDRESS_CHARS = 8192

    // The length of a scalar's text (0 for nothing or a flag); null for a list, a map or anything else.
    private fun scalarLength(value: Any?): Int? = when (value) {
        null, is Boolean -> 0
        is String -> value.length
        is Number -> Py.str(value).length
        else -> null
    }

    private fun isTextList(value: Any?): Boolean =
        value is List<*> && value.isNotEmpty() && value.size <= MAX_LINES &&
            value.all { it is String && it.length <= MAX_TEXT_CHARS }

    /**
     * build_recipe_data: the ORF map, in the Python key order, for a payload [isComplete] accepted. Every amount is
     * converted to US units as it comes in (OrfEditing.buildNewIngredient); one that won't parse is kept as written.
     * Never sets `image`: the caller adds it once the photo is processed.
     */
    fun buildRecipeData(payload: Map<*, *>, recipeUuid: String): YamlMap {
        val ingredients = mutableListOf<Any?>()
        for (line in payload["ingredients"] as List<*>) {
            val parsed = parseIngredientLine(line as String)
            val preserved: Map<Any?, Any?>? = parsed.notes?.let { linkedMapOf<Any?, Any?>("notes" to it.toMutableList<Any?>()) }
            ingredients += OrfEditing.buildNewIngredient(parsed.name, parsed.amount, parsed.unit, null, preserved)
        }
        val data: YamlMap = linkedMapOf<Any?, Any?>(
            "recipe_uuid" to recipeUuid,
            "recipe_name" to Py.strip(Py.str(payload["name"])),
            "category" to "None",
            "subcategory" to "None",
            "ingredients" to ingredients,
            "steps" to (payload["steps"] as List<*>).mapTo(mutableListOf<Any?>()) { linkedMapOf<Any?, Any?>("step" to it) },
        )
        OrfEditing.parseYieldText(payload["yield_text"])?.let { data["yields"] = mutableListOf<Any?>(LinkedHashMap<Any?, Any?>(it)) }
        text(payload["author"]).takeIf { it.isNotEmpty() }?.let { data["author"] = it }
        text(payload["source_url"]).takeIf { it.isNotEmpty() }?.let { data["source_url"] = it }
        return data
    }
}
