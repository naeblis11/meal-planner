package com.naeblis11.mealplanner.desktop.server

import java.security.SecureRandom

/**
 * The Alexa voice token (MEAL_PLANNER_API_TOKEN): read once from the secrets file at startup (a hand-edited one
 * works too), or made by Settings' Create a token. Never logged; [toString] says only whether one is set up.
 */
class ApiToken(
    private val secrets: SecretsFile,
    private val random: SecureRandom = SecureRandom(),
    log: (String) -> Unit = { System.err.println(it) },
) {
    /**
     * The token in use; null when none is set up: missing or empty in the file, shorter than [MIN_LENGTH] once one
     * matching pair of surrounding quotes is taken off (as python-dotenv reads KEY="value"), or the file unreadable.
     */
    @Volatile
    var value: String? = try {
        secrets.read()[KEY]?.let(::unquote)?.takeIf { it.isNotEmpty() }?.let { read ->
            if (read.length >= MIN_LENGTH) {
                read
            } else {
                // Its length only: a short token is still someone's secret.
                log("Meal Planner: $KEY in ${secrets.file.path} is shorter than $MIN_LENGTH characters, so it is ignored.")
                null
            }
        }
    } catch (e: Exception) {
        log("Meal Planner: couldn't read the secrets file ${secrets.file.path}: ${e.javaClass.simpleName}")
        null
    }
        private set

    /** True when a token is set up: the server then listens on the home network (MealPlannerServer). */
    val configured: Boolean get() = value != null

    /**
     * A new token (secrets.token_hex(32): 64 hex digits) saved in the secrets file, then in use. Throws IOException,
     * and keeps the old one, when it can't be saved. One at a time (a double click on Create a token), so the token in
     * use is always the one in the file.
     */
    @Synchronized
    fun create(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        secrets.put(KEY, token)
        value = token
        return token
    }

    override fun toString(): String = "ApiToken(configured=$configured)"

    companion object {
        const val KEY = "MEAL_PLANNER_API_TOKEN"

        /** The shortest token that counts; anything shorter is too easy to guess to open the server to the network. */
        const val MIN_LENGTH = 16

        // One matching pair of surrounding quotes, double or single, comes off; anything else is kept as written.
        private fun unquote(raw: String): String =
            if (raw.length >= 2 && raw.first() == raw.last() && (raw.first() == '"' || raw.first() == '\'')) {
                raw.substring(1, raw.length - 1)
            } else {
                raw
            }
    }
}
