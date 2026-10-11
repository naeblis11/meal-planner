package com.naeblis11.mealplanner.desktop.sync

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The sync port's routes, apart from any transport: JdkHttpsEngine adapts each HTTPS exchange to this, and tests call it directly. */
fun interface SyncHandler {
    suspend fun handle(request: SyncHttpRequest): SyncHttpResponse
}

/**
 * One request. [contentLength] is the declared Content-Length, -1 when none was given. [body] reads the body once, up to
 * the limit it is given, and throws BodyTooLargeException past it, so each route sets its own cap. [toString] never
 * shows [authorization].
 */
data class SyncHttpRequest(
    val method: String,
    val path: String,
    val authorization: String?,
    val contentLength: Long,
    val body: (limit: Int) -> ByteArray,
    val remoteAddress: String,
) {
    override fun toString(): String = "SyncHttpRequest($method $path from $remoteAddress)"
}

/** One reply; an empty [body] is sent with no body at all (204). */
class SyncHttpResponse(val status: Int, val contentType: String, val body: ByteArray) {
    override fun toString(): String = "SyncHttpResponse($status, $contentType, ${body.size} bytes)"

    companion object {
        const val JSON = "application/json; charset=utf-8"
        const val JPEG = "image/jpeg"
        val NO_CONTENT = SyncHttpResponse(204, "text/plain; charset=utf-8", ByteArray(0))

        fun json(status: Int, text: String) = SyncHttpResponse(status, JSON, text.toByteArray(Charsets.UTF_8))

        /** `{"error":"<code>"}`, the sync port's every refusal (SyncErrors). */
        fun error(status: Int, code: String) = json(status, buildJsonObject { put("error", code) }.toString())
    }
}

/** A request body over its route's cap: answered 413 `too_large`. */
class BodyTooLargeException(val limit: Int) : IOException("The request is over $limit bytes.")

/** [input] as SyncHttpRequest.body: read once, at most the limit asked for. */
internal fun boundedBody(input: InputStream): (Int) -> ByteArray {
    var read = false
    return { limit ->
        check(!read) { "The body was read already." }
        read = true
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > limit) throw BodyTooLargeException(limit)
            out.write(buffer, 0, n)
        }
        out.toByteArray()
    }
}
