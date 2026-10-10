package com.naeblis11.mealplanner.desktop.server

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException
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
 * - never the local network ([LocalNetwork]): the host is [resolve]d before each request, redirects included, and an
 *   address that is loopback, link-local, private (RFC 1918 or an IPv6 unique local address), multicast or unspecified,
 *   or a name that is `localhost` or ends in `.local`, is refused. A page's image_url on the home network (192.168/16, 10/8, 172.16/12) or
 *   http://127.0.0.1:5055/... would otherwise make this PC fetch from a LAN device, or from itself;
 * - [timeout] for the whole download, not just for each read;
 * - at most [maxBytes];
 * - only a JPEG, PNG, WebP or GIF, by its first bytes ([ImageSniff]).
 * Every failure is an IOException; the caller then imports the recipe without the photo. The address comes from a web
 * page, so a file:// or other scheme is never followed. Blocking.
 *
 * Build one per server and share it; never one per request. Each instance owns an HttpClient (with its own selector
 * thread and connection pool), which is costly to create and only released when the instance is garbage collected.
 * Tests pass a [resolve] of their own, so they never ask DNS; the request itself is still made by host name.
 */
class ImageFetcher(
    private val timeout: Duration = Duration.ofSeconds(10),
    private val maxBytes: Int = MAX_BYTES,
    private val maxRedirects: Int = MAX_REDIRECTS,
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
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

    // An http or https address with a host that isn't on the local network; anything else is refused before any
    // request is made. Called for the first address and again for every redirect's.
    private fun webAddress(text: String): URI {
        val uri = try {
            URI(text.trim())
        } catch (e: URISyntaxException) {
            throw IOException("The photo's address isn't a web address.")
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") throw IOException("Only http and https photo addresses are fetched.")
        val host = uri.host
        if (host.isNullOrEmpty()) throw IOException("The photo's address has no host.")
        if (LocalNetwork.isLocalName(host)) throw IOException(LocalNetwork.REFUSED)
        val addresses = try {
            resolve(LocalNetwork.bare(host))
        } catch (e: UnknownHostException) {
            throw IOException("The photo's host couldn't be found.")
        }
        if (addresses.isEmpty() || addresses.any(LocalNetwork::isLocalAddress)) throw IOException(LocalNetwork.REFUSED)
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

/**
 * What a photo address may never point at: this PC or the home network. A name is judged first (no lookup), then every
 * address the name resolves to; one local address among several (a name that answers with a public and a private
 * address) refuses the whole. Java hands an IPv4-mapped IPv6 address (`::ffff:172.16.0.1`) back as the IPv4 one, so
 * the mapped forms take the same rules.
 */
object LocalNetwork {
    const val REFUSED = "The photo's address points at the local network, which isn't fetched."

    /** `localhost` (any case, a trailing dot allowed) or a name under `.local` (mDNS: printers, this PC's own name). */
    fun isLocalName(host: String): Boolean {
        val name = bare(host).lowercase(Locale.ROOT).trimEnd('.')
        return name == "localhost" || name == "local" || name.endsWith(".local")
    }

    /** Loopback, link-local (169.254/16, fe80::/10), site-local (10/8, 172.16/12, 192.168/16, fec0::/10), a unique local IPv6 address (fc00::/7), multicast or unspecified. */
    fun isLocalAddress(address: InetAddress): Boolean =
        address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress ||
            address.isMulticastAddress || address.isAnyLocalAddress || isUniqueLocal(address)

    /** The host without the brackets an IPv6 literal carries in a URI. */
    fun bare(host: String): String = host.removePrefix("[").removeSuffix("]")

    private fun isUniqueLocal(address: InetAddress): Boolean =
        address is Inet6Address && (address.address[0].toInt() and 0xFE) == 0xFC
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
