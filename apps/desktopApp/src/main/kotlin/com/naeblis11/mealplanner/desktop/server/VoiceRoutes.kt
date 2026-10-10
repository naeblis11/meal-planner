package com.naeblis11.mealplanner.desktop.server

import com.naeblis11.mealplanner.domain.Py
import com.naeblis11.mealplanner.domain.Voice
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

const val VOICE_SHOPPING_PATH = "/api/voice/shopping-list"
const val VOICE_PANTRY_PATH = "/api/voice/pantry"
const val VOICE_MEAL_PATH = "/api/voice/meal"
const val VOICE_MAX_BYTES = 16 * 1024

/**
 * Home Assistant's three voice addresses (P4-R2, P4-R5), as app.py's api_voice_* routes:
 * - each needs "Authorization: Bearer <token>", compared in constant time, and a wrong one gets 401;
 * - with no token set up they answer 503 with speech, as the Python server did;
 * - the token opens nothing else.
 */
fun Route.voiceRoutes(
    actions: VoiceActions,
    token: () -> String?,
    maxBytes: Int = VOICE_MAX_BYTES,
    log: (String) -> Unit = { System.err.println(it) },
) {
    post(VOICE_SHOPPING_PATH) { answer(call, token, maxBytes, log) { actions.shopping(it) } }
    post(VOICE_PANTRY_PATH) { answer(call, token, maxBytes, log) { actions.pantry(it) } }
    post(VOICE_MEAL_PATH) { answer(call, token, maxBytes, log) { actions.meal(it) } }
}

private suspend fun answer(
    call: ApplicationCall,
    token: () -> String?,
    maxBytes: Int,
    log: (String) -> Unit,
    action: suspend (Map<Any?, Any?>) -> VoiceReply,
) {
    val expected = token()?.takeIf { it.isNotEmpty() }
    val reply = when {
        expected == null -> VoiceReply(false, Voice.SPEECH_NOT_SET_UP, 503)
        !bearerMatches(call.request.headers[HttpHeaders.Authorization], expected) -> VoiceReply(false, Voice.SPEECH_REFUSED, 401)
        else -> {
            val bytes = call.readBody(maxBytes)
            if (bytes == null) {
                VoiceReply(false, Voice.SPEECH_NOTHING_HEARD, 413)
            } else {
                try {
                    action(jsonObject(bytes))
                } catch (e: Exception) {
                    // This call's own cancellation goes on up. Any other CancellationException is a failure: Room
                    // throws one from a closed database, and Ktor would answer it with an HTML page Alexa can't say.
                    if (e is CancellationException) currentCoroutineContext().ensureActive()
                    // The class only, as ServerRoutes logs: the message may hold what was said.
                    log("Meal Planner: a voice command failed: ${e.javaClass.name}")
                    VoiceReply(false, Voice.SPEECH_FAILED, 500)
                }
            }
        }
    }
    call.reply(reply.json())
}

/** "Bearer <token>": the scheme is case-sensitive (Home Assistant sends the literal secret); the token is compared in constant time. */
internal fun bearerMatches(header: String?, token: String): Boolean {
    val value = header ?: return false
    val space = value.indexOf(' ')
    if (space < 0 || value.substring(0, space) != "Bearer") return false
    val presented = Py.strip(value.substring(space + 1))
    return MessageDigest.isEqual(presented.toByteArray(Charsets.UTF_8), token.toByteArray(Charsets.UTF_8))
}
