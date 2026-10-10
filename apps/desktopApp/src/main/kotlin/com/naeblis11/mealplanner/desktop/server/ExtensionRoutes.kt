package com.naeblis11.mealplanner.desktop.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val EXTENSION_PATH = "/recipes/import/extension"
const val REVIEW_PATH = "/recipes/import/review"
const val EXTENSION_MAX_BYTES = 5 * 1024 * 1024
const val NOT_LOOPBACK = "Send recipes from a browser on this PC, with the extension set to http://127.0.0.1:5000."
const val NOT_EXTENSION = "Only the Meal Planner Chrome extension can send recipes here."
const val NOT_JSON = "Send the recipe as JSON, with Content-Type: application/json."
const val TOO_LARGE = "That recipe is too large to send."
const val EXTENSION_FAILED = "Meal Planner couldn't read that recipe."

/** What the extension's "opening review" tab shows: the review is in the app, not in the browser. */
const val REVIEW_PAGE = """<!doctype html>
<html lang="en">
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Meal Planner</title></head>
<body style="font-family: system-ui, sans-serif; max-width: 34rem; margin: 3rem auto; padding: 0 1rem; color: #2b2b2b; background: #fbf8f3;">
<h1 style="font-size: 1.4rem;">Your recipe is in Meal Planner</h1>
<p>Check it on the review in Meal Planner on this PC, then choose Confirm.</p>
<p>If you were editing a recipe or reviewing another import, Meal Planner will open it when you've finished editing or reviewing.</p>
<p>You can close this tab.</p>
</body>
</html>
"""

/**
 * Whether a request's `Origin` header may reach the extension's addresses (P4-R7). A web page open in the user's own
 * browser, or a name rebound to 127.0.0.1, is on this PC too, and the browser sends its Origin. The extension's own
 * POST sends `chrome-extension://<id>`; curl and the tab the extension opens send none.
 */
internal fun isAllowedOrigin(origin: String?): Boolean = origin == null || origin.startsWith("chrome-extension://")

/**
 * Whether a `Content-Type` header says JSON (any parameters, any case). The extension's POST always does (popup.js);
 * a plain HTML form can only send a form or text type, so this refuses it before the body is read, whatever its Origin.
 */
internal fun isJsonContentType(header: String?): Boolean =
    header?.substringBefore(';')?.trim()?.equals("application/json", ignoreCase = true) == true

/**
 * The Chrome extension's two addresses (P4-R2, P4-R4), for callers on this PC only, and with no Origin or the
 * extension's ([isAllowedOrigin]):
 * - POST [EXTENSION_PATH] stages the recipe and brings the window up on its review. Its body must say it is JSON
 *   ([isJsonContentType]), as the extension's does; anything else is a 415.
 * - The extension then opens [REVIEW_PATH] in a tab, as it did for the Python server's review page. It gets a page
 *   saying the recipe is in the app, and the window comes forward again over the browser.
 * No CORS headers (P4-R7): the extension's host permission lets it call without them.
 */
fun Route.extensionRoutes(
    importer: ExtensionImport,
    onShowReview: () -> Unit,
    maxBytes: Int = EXTENSION_MAX_BYTES,
    remoteAddress: (ApplicationCall) -> String? = { it.request.local.remoteAddress },
    log: (String) -> Unit = { System.err.println(it) },
) {
    post(EXTENSION_PATH) {
        if (!isLoopback(remoteAddress(call))) return@post call.reply(JsonReply.error(403, NOT_LOOPBACK))
        if (!isAllowedOrigin(call.request.headers[HttpHeaders.Origin])) return@post call.reply(JsonReply.error(403, NOT_EXTENSION))
        if (!isJsonContentType(call.request.headers[HttpHeaders.ContentType])) return@post call.reply(JsonReply.error(415, NOT_JSON))
        val bytes = call.readBody(maxBytes) ?: return@post call.reply(JsonReply.error(413, TOO_LARGE))
        val reply = try {
            withContext(Dispatchers.IO) { importer.receive(jsonObject(bytes)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The class only, as ServerRoutes logs: the message may hold what was sent.
            log("Meal Planner: a recipe from the Chrome extension failed: ${e.javaClass.name}")
            JsonReply.error(500, EXTENSION_FAILED)
        }
        call.reply(reply)
    }
    get(REVIEW_PATH) {
        if (!isLoopback(remoteAddress(call))) return@get call.reply(JsonReply.error(403, NOT_LOOPBACK))
        // It raises the window, so it takes the same rule; a page's <img> sends no Origin, but can't stage anything.
        if (!isAllowedOrigin(call.request.headers[HttpHeaders.Origin])) return@get call.reply(JsonReply.error(403, NOT_EXTENSION))
        onShowReview()
        call.respondText(REVIEW_PAGE, ContentType.Text.Html)
    }
}
