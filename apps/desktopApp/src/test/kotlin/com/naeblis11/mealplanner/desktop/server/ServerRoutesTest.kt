package com.naeblis11.mealplanner.desktop.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.CannotTransformContentToTypeException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.testing.testApplication
import io.ktor.server.util.getOrFail
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.typeOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** P4-R2, P4-R7: /healthz as the Python server answers it, and nothing else (no listing, no static files). */
class ServerRoutesTest {
    @Test
    fun healthzSaysOkInJson() = testApplication {
        application { ServerRoutes().install(this) }
        val response = client.get(ServerRoutes.HEALTHZ_PATH)
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
        assertEquals("""{"ok":true}""", response.bodyAsText())
    }

    @Test
    fun anythingElseIsNotFound() = testApplication {
        application { ServerRoutes().install(this) }
        for (path in listOf("/", "/recipes", "/recipe-images/a.jpg", "/static/style.css", "/login")) {
            assertEquals(path, HttpStatusCode.NotFound, client.get(path).status)
        }
    }

    @Test
    fun aRouteThatThrowsIsOneLogLineAndAJson500() = testApplication {
        val logs = CopyOnWriteArrayList<String>()
        val failing: Route.() -> Unit = { get("/boom") { throw IllegalStateException("what the caller sent") } }
        application { ServerRoutes(listOf(failing), log = { logs += it }).install(this) }
        val response = client.get("/boom")
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
        assertEquals("""{"ok":false,"error":"${ServerRoutes.INTERNAL_ERROR}"}""", response.bodyAsText())
        assertEquals(listOf("Meal Planner: GET /boom failed: java.lang.IllegalStateException"), logs.toList())
        // The message may hold what was sent (a recipe, a token): never logged.
        assertFalse(logs.any { "what the caller sent" in it })
    }

    @Test
    fun aRequestKtorCannotReadIsAJsonClientErrorNotA500() = testApplication {
        // Ktor's own "the caller sent something wrong" exceptions are the caller's fault: 400, 413 or 415, never a 500.
        val logs = CopyOnWriteArrayList<String>()
        val cases = listOf(
            Triple("/bad", HttpStatusCode.BadRequest, ServerRoutes.BAD_REQUEST),
            Triple("/missing", HttpStatusCode.BadRequest, ServerRoutes.BAD_REQUEST),
            Triple("/untransformable", HttpStatusCode.BadRequest, ServerRoutes.BAD_REQUEST),
            Triple("/large", HttpStatusCode.PayloadTooLarge, ServerRoutes.REQUEST_TOO_LARGE),
            Triple("/media", HttpStatusCode.UnsupportedMediaType, ServerRoutes.UNSUPPORTED_MEDIA_TYPE),
        )
        val routes: Route.() -> Unit = {
            get("/bad") { throw BadRequestException("what the caller sent") }
            // A real one: a missing parameter is Ktor's MissingRequestParameterException, a BadRequestException.
            get("/missing") { call.parameters.getOrFail("id") }
            get("/untransformable") { throw CannotTransformContentToTypeException(typeOf<Int>()) }
            get("/large") { throw PayloadTooLargeException(100) }
            get("/media") { throw UnsupportedMediaTypeException(ContentType.Text.Plain) }
        }
        application { ServerRoutes(listOf(routes), log = { logs += it }).install(this) }
        for ((path, status, message) in cases) {
            val response = client.get(path)
            assertEquals(path, status, response.status)
            assertEquals(path, ContentType.Application.Json, response.contentType()?.withoutParameters())
            assertEquals(path, """{"ok":false,"error":"$message"}""", response.bodyAsText())
        }
        assertFalse(logs.any { "what the caller sent" in it })
    }
}
