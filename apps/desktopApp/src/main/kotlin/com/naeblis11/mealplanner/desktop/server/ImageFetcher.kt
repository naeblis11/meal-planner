package com.naeblis11.mealplanner.desktop.server

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URISyntaxException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Downloads a recipe's photo for the Chrome extension's import (P4-R3; recipe_extraction.fetch_image_bytes):
 * - http and https only, checked again on every redirect, and at most [maxRedirects] of them;
 * - [timeout] for the whole download, not just for each read;
 * - at most [maxBytes];
 * - only a JPEG, PNG, WebP or GIF, by its first bytes ([ImageSniff]).
 * Every failure is an IOException; the caller then imports the recipe without the photo. The address comes from a web
 * page, so a file:// or other scheme is never followed. Blocking.
 *
 * Build one per server and share it; never one per request. Each instance owns an HttpClient (with its own selector
 * thread and connection pool), which is costly to create and only released when the instance is garbage collected.
 */
class ImageFetcher(
    private val timeout: Duration = Duration.ofSeconds(10),
    private val maxBytes: Int = MAX_BYTES,
    private val maxRedirects: Int = MAX_REDIRECTS,
) {
    private val client: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(timeout)
        .build()

    fun fetch(url: String): ByteArray {
        val task = DOWNLOADS.submit(Callable { download(url) })
        try {
            return task.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            // Interrupting the download ends a read that is waiting on a stalled server.
            task.cancel(true)
            throw IOException("The photo took longer than ${timeout.toMillis()} ms.")
        } catch (e: ExecutionException) {
            val cause = e.cause
            throw if (cause is IOException) cause else IOException("The photo couldn't be downloaded: $cause", cause)
        } catch (e: InterruptedException) {
            task.cancel(true)
            Thread.currentThread().interrupt()
            throw IOException("The photo's download was interrupted.")
        }
    }

    private fun download(url: String): ByteArray {
        var target = webAddress(url)
        var redirects = 0
        while (true) {
            val request = HttpRequest.newBuilder(target).timeout(timeout).header("User-Agent", "Mozilla/5.0").GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            val status = response.statusCode()
            val body = response.body()
            try {
                if (status !in 300..399) {
                    if (status !in 200..299) throw IOException("The photo's address answered $status.")
                    val bytes = readCapped(body)
                    if (ImageSniff.kind(bytes) == null) throw IOException("That address is not a JPEG, PNG, WebP or GIF image.")
                    return bytes
                }
                val location = response.headers().firstValue("Location").orElse(null)
                    ?: throw IOException("A redirect ($status) with no Location.")
                redirects++
                if (redirects > maxRedirects) throw IOException("More than $maxRedirects redirects.")
                target = webAddress(target.resolve(location.trim()).toString())
            } finally {
                body.close()
            }
        }
    }

    // An http or https address with a host; anything else is refused before any request is made.
    private fun webAddress(text: String): URI {
        val uri = try {
            URI(text.trim())
        } catch (e: URISyntaxException) {
            throw IOException("The photo's address isn't a web address.")
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") throw IOException("Only http and https photo addresses are fetched.")
        if (uri.host.isNullOrEmpty()) throw IOException("The photo's address has no host.")
        return uri
    }

    private fun readCapped(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return out.toByteArray()
            if (out.size() + read > maxBytes) throw IOException("The photo is larger than $maxBytes bytes.")
            out.write(buffer, 0, read)
        }
    }

    companion object {
        const val MAX_BYTES = 10 * 1024 * 1024
        const val MAX_REDIRECTS = 3

        // Daemon threads: a download still stalled when the app quits never holds it open.
        private val DOWNLOADS: ExecutorService = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "photo-download").apply { isDaemon = true }
        }
    }
}

/** What a photo's first bytes say it is (P4-R3): "jpeg", "png", "gif", "webp", or null for anything else. */
object ImageSniff {
    fun kind(bytes: ByteArray): String? = when {
        starts(bytes, 0xFF, 0xD8, 0xFF) -> "jpeg"
        starts(bytes, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "png"
        (starts(bytes, 0x47, 0x49, 0x46, 0x38, 0x37, 0x61) || starts(bytes, 0x47, 0x49, 0x46, 0x38, 0x39, 0x61)) -> "gif"
        starts(bytes, 0x52, 0x49, 0x46, 0x46) && bytes.size >= 12 && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
        else -> null
    }

    private fun starts(bytes: ByteArray, vararg prefix: Int): Boolean =
        bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it].toByte() }
}
