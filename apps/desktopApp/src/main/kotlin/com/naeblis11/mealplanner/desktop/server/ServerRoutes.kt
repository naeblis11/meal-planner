package com.naeblis11.mealplanner.desktop.server

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException

/**
 * Everything the built-in server answers (P4-R2): /healthz, then each feature's routes (the Chrome extension's, the
 * voice commands'). Nothing else: no static files, no listing, no CORS headers; any other path is a plain 404.
 * A route that throws is [log]ged as one line (method, path, exception class; never its message, which may hold what
 * was sent) and answered with a JSON 500, so every feature's routes get the same treatment; Ktor's own refusals of
 * what the caller sent (a bad request, a body too large or of a type it can't read) are a JSON 400, 413 or 415
 * instead, and not logged. Ktor's own logging is SLF4J with no binding, which says nothing.
 */
class ServerRoutes(
    private val features: List<Route.() -> Unit> = emptyList(),
    private val log: (String) -> Unit = { System.err.println(it) },
) {
    fun install(application: Application) {
        application.intercept(ApplicationCallPipeline.Monitoring) {
            try {
                proceed()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // What the caller sent is answered as Ktor would (400, 413, 415) and not logged: nothing failed here.
                val reply = callerError(e) ?: run {
                    log("Meal Planner: ${call.request.httpMethod.value} ${call.request.path()} failed: ${e.javaClass.name}")
                    JsonReply.error(500, INTERNAL_ERROR)
                }
                if (!call.response.isCommitted) call.reply(reply)
            }
        }
        application.routing {
            get(HEALTHZ_PATH) { call.reply(JsonReply.ok()) }
            for (feature in features) feature()
        }
    }

    companion object {
        const val HEALTHZ_PATH = "/healthz"

        /** The error a failed route answers with (the extension shows it). */
        const val INTERNAL_ERROR = "Something went wrong in Meal Planner. Please try again."

        /** Ktor's own refusals of what a caller sent (P4-R2): 400, 413 and 415, never a 500. */
        const val BAD_REQUEST = "Meal Planner couldn't read that request."
        const val REQUEST_TOO_LARGE = "That request is too large for Meal Planner."
        const val UNSUPPORTED_MEDIA_TYPE = "Meal Planner can't read that kind of request."

        // The two ContentTransformationException subclasses first: the rest of that family, and BadRequestException
        // (MissingRequestParameterException, ParameterConversionException, ...), are a plain 400.
        private fun callerError(e: Exception): JsonReply? = when (e) {
            is PayloadTooLargeException -> JsonReply.error(413, REQUEST_TOO_LARGE)
            is UnsupportedMediaTypeException -> JsonReply.error(415, UNSUPPORTED_MEDIA_TYPE)
            is ContentTransformationException, is BadRequestException -> JsonReply.error(400, BAD_REQUEST)
            else -> null
        }
    }
}
