package com.naeblis11.mealplanner.desktop.server

import com.naeblis11.mealplanner.importing.ImportInbox
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.Route
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse as JdkResponse
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P4-R2, P4-R4, P4-R7: the extension's two addresses, for this PC and the extension only, in the Python server's shapes. */
class ExtensionRoutesTest {
    private val dir: File = Files.createTempDirectory("mp-extension-routes").toFile()
    private val inbox = ImportInbox()
    private var shown = 0
    private val importer = ExtensionImport(
        newStagingDir = { Files.createTempDirectory(dir.toPath(), "staging").toFile() },
        fetchImage = { throw IOException("no network in tests") },
        inbox = inbox,
        onReceived = {},
        log = {},
    )

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun ApplicationTestBuilder.serve(remote: String?, maxBytes: Int = EXTENSION_MAX_BYTES) = application {
        ServerRoutes(
            listOf<Route.() -> Unit>({
                extensionRoutes(importer, onShowReview = { shown++ }, maxBytes = maxBytes, remoteAddress = { remote }, log = {})
            }),
        ).install(this)
    }

    private suspend fun ApplicationTestBuilder.send(body: String, origin: String? = null): HttpResponse = client.post(EXTENSION_PATH) {
        contentType(ContentType.Application.Json)
        if (origin != null) header(HttpHeaders.Origin, origin)
        setBody(body)
    }

    @Test
    fun aRecipeFromThisPcIsStaged() = testApplication {
        serve("127.0.0.1")
        val response = send(PAYLOAD)
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"ok":true}""", response.bodyAsText())
        assertEquals(1, inbox.waiting.value)
    }

    @Test
    fun anyoneElseIsRefused() = testApplication {
        serve("203.0.113.20")
        val response = send(PAYLOAD)
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("""{"ok":false,"error":"$NOT_LOOPBACK"}""", response.bodyAsText())
        assertEquals(0, inbox.waiting.value)
        // The refusal names the address the extension should be set to.
        assertEquals("Send recipes from a browser on this PC, with the extension set to http://127.0.0.1:5000.", NOT_LOOPBACK)
    }

    @Test
    fun aBodyThatDoesNotSayItIsJsonIsRefused() = testApplication {
        // Defence in depth: the extension always says application/json; a plain form post never reaches the import.
        serve("127.0.0.1")
        for (type in listOf(ContentType.Text.Plain, ContentType.Application.FormUrlEncoded)) {
            val response = client.post(EXTENSION_PATH) {
                contentType(type)
                setBody(PAYLOAD)
            }
            assertEquals("$type", HttpStatusCode.UnsupportedMediaType, response.status)
            assertEquals("""{"ok":false,"error":"$NOT_JSON"}""", response.bodyAsText())
        }
        assertEquals(0, inbox.waiting.value)
        val withCharset = client.post(EXTENSION_PATH) {
            header(HttpHeaders.ContentType, "application/json; charset=utf-8")
            setBody(PAYLOAD)
        }
        assertEquals(HttpStatusCode.OK, withCharset.status)
        assertEquals(1, inbox.waiting.value)
    }

    @Test
    fun onlyAJsonContentTypeIsJson() {
        for (type in listOf("application/json", "application/json; charset=utf-8", "Application/JSON", " application/json ")) {
            assertTrue(type, isJsonContentType(type))
        }
        for (type in listOf(null, "", "text/plain", "application/jsonx", "application/x-www-form-urlencoded", "multipart/form-data; boundary=x", "json", ";;")) {
            assertFalse("$type", isJsonContentType(type))
        }
    }

    @Test
    fun aRealCallerWithNoContentTypeIsRefused() {
        // The JDK client sends no Content-Type unless told to: a body that doesn't say what it is.
        val port = freeLoopbackPort()
        val engine = KtorEngine()
        engine.start(MealPlannerServer.LOOPBACK, port) {
            ServerRoutes(listOf<Route.() -> Unit>({ extensionRoutes(importer, onShowReview = {}, log = {}) })).install(this)
        }
        try {
            val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$EXTENSION_PATH"))
                .POST(HttpRequest.BodyPublishers.ofString(PAYLOAD))
                .build()
            val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
            val response = client.send(request, JdkResponse.BodyHandlers.ofString())
            assertEquals(415, response.statusCode())
            assertEquals("""{"ok":false,"error":"$NOT_JSON"}""", response.body())
        } finally {
            engine.stop()
        }
        assertEquals(0, inbox.waiting.value)
    }

    @Test
    fun aBodyOverTheCapIsRefused() = testApplication {
        serve("127.0.0.1", maxBytes = 100)
        val response = send(PAYLOAD)
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertEquals("""{"ok":false,"error":"$TOO_LARGE"}""", response.bodyAsText())
        assertEquals(0, inbox.waiting.value)
    }

    @Test
    fun brokenJsonIsARecipeWithNothingInIt() = testApplication {
        serve("127.0.0.1")
        for (body in listOf("not json", "[1, 2]", "")) {
            val response = send(body)
            assertEquals(body, HttpStatusCode.BadRequest, response.status)
            assertEquals("""{"ok":false,"error":"Missing name, ingredients, or steps."}""", response.bodyAsText())
        }
        assertEquals(0, inbox.waiting.value)
    }

    @Test
    fun theReviewPageSaysTheRecipeIsInTheAppAndBringsItForward() = testApplication {
        serve("127.0.0.1")
        val page = client.get(REVIEW_PATH)
        assertEquals(HttpStatusCode.OK, page.status)
        assertEquals(ContentType.Text.Html, page.contentType()?.withoutParameters())
        val text = page.bodyAsText()
        assertTrue(text.contains("Your recipe is in Meal Planner"))
        // It may wait behind an edit or another review, so the page doesn't promise it is open now.
        assertTrue(text, text.contains("Meal Planner will open it when you've finished editing or reviewing."))
        assertEquals(1, shown)
    }

    @Test
    fun theReviewPageIsForThisPcOnly() = testApplication {
        serve("203.0.113.20")
        val page = client.get(REVIEW_PATH)
        assertEquals(HttpStatusCode.Forbidden, page.status)
        assertEquals("""{"ok":false,"error":"$NOT_LOOPBACK"}""", page.bodyAsText())
        assertEquals(0, shown)
    }

    @Test
    fun aWebPageIsRefusedEvenFromThisPc() = testApplication {
        // A page open in the user's own browser, or a name rebound to 127.0.0.1, sends its own Origin (P4-R7).
        serve("127.0.0.1")
        for (origin in listOf("https://evil.example", "http://127.0.0.1:5000", "null")) {
            val response = send(PAYLOAD, origin = origin)
            assertEquals(origin, HttpStatusCode.Forbidden, response.status)
            assertEquals("""{"ok":false,"error":"$NOT_EXTENSION"}""", response.bodyAsText())
        }
        assertEquals(0, inbox.waiting.value)
    }

    @Test
    fun anotherBrowsersExtensionIsRefused() = testApplication {
        // Only Chrome's extension scheme is the extension; Firefox's moz-extension:// (any add-on there) isn't.
        serve("127.0.0.1")
        for (origin in listOf("moz-extension://3b0a1c2d-4e5f-6a7b-8c9d-0e1f2a3b4c5d", "safari-web-extension://ABCDEF", "chrome-extension-evil://x", "chrome://extensions")) {
            val response = send(PAYLOAD, origin = origin)
            assertEquals(origin, HttpStatusCode.Forbidden, response.status)
            assertEquals("""{"ok":false,"error":"$NOT_EXTENSION"}""", response.bodyAsText())
        }
        assertEquals(0, inbox.waiting.value)
    }

    @Test
    fun theChromeExtensionsOriginIsAccepted() = testApplication {
        serve("127.0.0.1")
        val response = send(PAYLOAD, origin = "chrome-extension://abcdefghijklmnopabcdefghijklmnop")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"ok":true}""", response.bodyAsText())
        assertEquals(1, inbox.waiting.value)
    }

    @Test
    fun noOriginIsAcceptedAsCurlSendsNone() = testApplication {
        serve("127.0.0.1")
        val response = send(PAYLOAD, origin = null)
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, inbox.waiting.value)
    }

    @Test
    fun theReviewPageRefusesAWebPage() = testApplication {
        // The review address raises the window, so it takes the same Origin rule.
        serve("127.0.0.1")
        val page = client.get(REVIEW_PATH) { header(HttpHeaders.Origin, "https://evil.example") }
        assertEquals(HttpStatusCode.Forbidden, page.status)
        assertEquals("""{"ok":false,"error":"$NOT_EXTENSION"}""", page.bodyAsText())
        assertEquals(0, shown)
    }

    @Test
    fun noCorsHeadersAreSent() = testApplication {
        // The extension calls with its host permission, which needs none (P4-R7).
        serve("127.0.0.1")
        val response = send(PAYLOAD, origin = "chrome-extension://abcdefghijklmnop")
        assertEquals(HttpStatusCode.OK, response.status)
        assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun aRealCallerOnThisPcIsAccepted() {
        // The real engine's remote address (not the test host's), on a 127.0.0.1 ephemeral port.
        val port = freeLoopbackPort()
        val engine = KtorEngine()
        engine.start(MealPlannerServer.LOOPBACK, port) {
            ServerRoutes(listOf<Route.() -> Unit>({ extensionRoutes(importer, onShowReview = {}, log = {}) })).install(this)
        }
        try {
            val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$EXTENSION_PATH"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(PAYLOAD))
                .build()
            // HTTP/1.1: the JDK client's default h2c upgrade on plain http would be a needless variable against CIO.
            val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
            val response = client.send(request, JdkResponse.BodyHandlers.ofString())
            assertEquals(200, response.statusCode())
            assertEquals("""{"ok":true}""", response.body())
        } finally {
            engine.stop()
        }
        assertEquals(1, inbox.waiting.value)
    }

    @Test
    fun aFailedImportIsAJson500LoggedByItsClassOnly() = testApplication {
        val logs = CopyOnWriteArrayList<String>()
        val failing = ExtensionImport(
            newStagingDir = { throw IOException("C:\\Users\\someone\\secret") },
            fetchImage = { throw IOException("no network in tests") },
            inbox = inbox,
            onReceived = {},
            log = {},
        )
        application {
            ServerRoutes(listOf<Route.() -> Unit>({ extensionRoutes(failing, onShowReview = {}, remoteAddress = { "127.0.0.1" }, log = { logs += it }) })).install(this)
        }
        val response = send(PAYLOAD)
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals("""{"ok":false,"error":"$EXTENSION_FAILED"}""", response.bodyAsText())
        assertEquals(1, logs.size)
        assertTrue(logs.single(), logs.single().contains("java.io.IOException"))
        assertTrue(logs.single(), logs.none { "secret" in it })
    }

    @Test
    fun anOverCapBodyWithNoLengthIsRefusedAsItStreams() {
        // Chunked, so no Content-Length to judge it by: the cap is enforced while reading, on the real engine.
        val port = freeLoopbackPort()
        val engine = KtorEngine()
        engine.start(MealPlannerServer.LOOPBACK, port) {
            ServerRoutes(listOf<Route.() -> Unit>({ extensionRoutes(importer, onShowReview = {}, maxBytes = 1024, log = {}) })).install(this)
        }
        try {
            val big = PAYLOAD.replace("Boil water.", "a".repeat(64 * 1024))
            val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$EXTENSION_PATH"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream { big.byteInputStream() })
                .build()
            val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
            val response = client.send(request, JdkResponse.BodyHandlers.ofString())
            assertEquals(413, response.statusCode())
            assertEquals("""{"ok":false,"error":"$TOO_LARGE"}""", response.body())
        } finally {
            engine.stop()
        }
        assertEquals(0, inbox.waiting.value)
    }

    @Test
    fun aNumberLongerThanPythonReadsIsBrokenJson() = testApplication {
        // Python's json refuses an int of more than 4300 digits, and get_json(silent=True) then gives {}.
        serve("127.0.0.1")
        // In a field the import never reads, so only the JSON's own reading can refuse it.
        fun withExtra(digits: Int) = PAYLOAD.replaceFirst("{", "{\"extra\": 1${"0".repeat(digits - 1)}, ")
        val response = send(withExtra(4301))
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("""{"ok":false,"error":"Missing name, ingredients, or steps."}""", response.bodyAsText())
        assertEquals(0, inbox.waiting.value)
        assertEquals(HttpStatusCode.OK, send(withExtra(4300)).status)
        assertEquals(1, inbox.waiting.value)
    }

    companion object {
        const val PAYLOAD =
            """{"name": "Extracted Soup", "ingredients": ["2 cups flour, sifted", "Kosher salt, to taste"], "steps": ["Boil water.", "Add flour."], "yield_text": "4 servings", "source_url": "https://www.example.com/soup"}"""
    }
}
