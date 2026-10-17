package com.naeblis11.mealplanner.sync

import com.naeblis11.mealplanner.update.Sha256
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A sync failure with a plain message for the user; never a key, a code or a reply body. */
open class SyncHttpException(message: String) : IOException(message)

/** The PC answered 401 `unpaired`: this device was removed there. It keeps its data. */
class UnpairedException : SyncHttpException("This device was removed from the PC. Your data is still here.")

/** The PC's certificate isn't the pinned one, or it couldn't prove the code: never trusted, and nothing was sent after. */
class WrongPcException : SyncHttpException("This isn't the PC you paired with. Sync stopped.")

class WrongCodeException : SyncHttpException("That code didn't work. Check it on the PC and try again.")

class NotPairingException : SyncHttpException("The PC isn't pairing just now. On the PC, open Settings and press Pair a device.")

class NotMasterException : SyncHttpException("That PC leaves sync to another Meal Planner PC on your network.")

/** Stage 4b: the main PC offers no hand-over to this PC (it was cancelled, ran out, or the main PC gave up waiting). */
class HandoverRefusedException : SyncHttpException("The main PC isn't handing over to this PC any more. Nothing changed.")

/** A pairing that worked: the fingerprint to pin from now on, and the PC's reply (the device key among it). */
data class Paired(val fingerprint: String, val reply: PairReply) {
    // No fingerprint in a log line; PairReply hides its own key and proof.
    override fun toString(): String = "Paired(reply=$reply)"
}

/**
 * The paired PC's sync port, pinned (LAN sync spec, section 2), on the phone (2c) and in desktop tests. HttpsURLConnection, as
 * ReleaseHttp, because Android has no java.net.http:
 * - The trust manager accepts exactly the certificate whose SHA-256 is [pin], and throws for any other.
 * - The host name isn't checked: the pin replaces that.
 * - With no pin (the typed code's first connection), it records the certificate it was shown, proves the code over that
 *   fingerprint, and trusts it only once the PC has proved the code back.
 * - Never a redirect. Only https, to [host]:[port]. 5 s to connect; 30 s to read for pair and photos, and
 *   [EXCHANGE_READ_TIMEOUT_MILLIS] (150 s) for an exchange, whose reply starts only once the PC's hub.exchange is done (a
 *   big first Merge writes hundreds of recipe files). One connection per request (`Connection: close`), so a pinned
 *   request never rides on another's connection.
 * - Replies are capped. Failures are SyncHttpException with plain messages; nothing here logs.
 * This and update/ReleaseHttp.kt are the only phone code that goes online; the phone uses this one only on Sync and Join a PC (stage 2c)
 * (UpdateNetworkCodeTest).
 */
class SyncHttp(
    val host: String,
    val port: Int,
    val pin: String?,
    private val random: SecureRandom = SecureRandom(),
    private val connectTimeoutMillis: Int = CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = READ_TIMEOUT_MILLIS,
    private val exchangeReadTimeoutMillis: Int = EXCHANGE_READ_TIMEOUT_MILLIS,
    private val maxExchangeReplyBytes: Int = MAX_EXCHANGE_REPLY_BYTES,
    private val maxPhotoBytes: Int = SyncPhotos.MAX_BYTES,
) {
    init {
        require(port in 1..65535) { "Bad port." }
        require(HOST.matches(host)) { "Bad address." }
        require(pin == null || FINGERPRINT.matches(pin)) { "Bad fingerprint." }
    }

    /**
     * Pairs this device with the code shown on the PC: connects, proves [code] over the fingerprint it saw (the pin, when
     * there is one), and checks the PC's proof back.
     */
    suspend fun pair(code: String, deviceId: String, name: String, kind: String): Paired = withContext(Dispatchers.IO) {
        require(PairingWire.isName(name)) { "Bad device name." }
        val trust = PinTrust(pin)
        val connection = open(SyncPaths.PAIR, "POST", trust)
        try {
            guarded(trust) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", JSON)
                // A streamed body of known length: the JDK would otherwise buffer it and may silently retry the POST on a new
                // connection when the first drops, sending the proof twice. The length must be set before connecting, so it
                // is measured on placeholders of the real sizes (a 32-hex nonce, a 64-hex proof) and checked below.
                val placeholder = PairRequest(deviceId, name, kind, "0".repeat(NONCE_HEX), "0".repeat(PROOF_HEX))
                val length = PairingWire.encode(placeholder).toByteArray(Charsets.UTF_8).size
                connection.setFixedLengthStreamingMode(length)
                // The handshake first: the proof is over the certificate this connection really has.
                connection.connect()
                val fingerprint = trust.seen ?: throw SyncHttpException(UNREACHABLE)
                val nonce = Sha256.hex(ByteArray(16).also(random::nextBytes)).take(NONCE_HEX)
                val request = PairRequest(deviceId, name, kind, nonce, PairingProof.device(code, fingerprint, nonce))
                val payload = PairingWire.encode(request).toByteArray(Charsets.UTF_8)
                check(payload.size == length) { "pair body length" }
                connection.outputStream.use { it.write(payload) }
                val status = connection.responseCode
                val body = readReply(connection, status, MAX_SMALL_REPLY_BYTES)
                if (status != HttpURLConnection.HTTP_OK) throw refusal(status, body)
                val reply = try {
                    PairingWire.decodeReply(body.toString(Charsets.UTF_8))
                } catch (e: IllegalArgumentException) {
                    throw SyncHttpException(UNREADABLE)
                }
                if (!PairingProof.matches(PairingProof.hub(code, fingerprint, nonce), reply.hubProof)) throw WrongPcException()
                Paired(fingerprint, reply)
            }
        } finally {
            connection.disconnect()
        }
    }

    /** SyncClient's way to the paired PC with this device's key; needs the pin. */
    fun transport(deviceKey: String): SyncTransport {
        requireNotNull(pin) { "Sync needs the paired PC's fingerprint." }
        require(KEY.matches(deviceKey)) { "Bad device key." }
        return object : SyncTransport {
            override suspend fun exchange(request: ExchangeRequest): ExchangeReply = withContext(Dispatchers.IO) {
                val (status, body) = call("POST", SyncPaths.EXCHANGE, SyncWire.encode(request).toByteArray(Charsets.UTF_8), JSON, deviceKey, maxExchangeReplyBytes, exchangeReadTimeoutMillis)
                if (status != HttpURLConnection.HTTP_OK) throw refusal(status, body)
                try {
                    SyncWire.decodeReply(body.toString(Charsets.UTF_8))
                } catch (e: IllegalArgumentException) {
                    throw SyncHttpException(UNREADABLE)
                }
            }

            override suspend fun photo(uuid: String): ByteArray? = withContext(Dispatchers.IO) {
                val (status, body) = call("GET", photoPath(uuid), null, null, deviceKey, maxPhotoBytes)
                when (status) {
                    HttpURLConnection.HTTP_OK -> body
                    HttpURLConnection.HTTP_NOT_FOUND -> null
                    else -> throw refusal(status, body)
                }
            }

            override suspend fun sendPhoto(uuid: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
                val (status, body) = call("PUT", photoPath(uuid), bytes, JPEG, deviceKey, MAX_SMALL_REPLY_BYTES)
                // 409 `refused`: the PC no longer wants this photo (another arrived, or it changed); there is nothing to resend.
                // Any other 409 (not_master while the PC yields) is a real refusal.
                val refused = status == HttpURLConnection.HTTP_CONFLICT && errorOf(body) == SyncErrors.REFUSED
                if (status != HttpURLConnection.HTTP_NO_CONTENT && !refused) throw refusal(status, body)
            }
        }
    }

    /**
     * Tells the PC this device is leaving (`POST /sync/leave` with its key, stage 2c). The PC removes it as its own
     * Remove does. Done on 204. A key the PC no longer knows is UnpairedException. The phone's Leave goes on whatever
     * this says.
     */
    suspend fun leave(deviceKey: String): Unit = withContext(Dispatchers.IO) {
        requireNotNull(pin) { "Leaving needs the paired PC's fingerprint." }
        require(KEY.matches(deviceKey)) { "Bad device key." }
        val (status, body) = call("POST", SyncPaths.LEAVE, ByteArray(0), null, deviceKey, MAX_SMALL_REPLY_BYTES)
        if (status != HttpURLConnection.HTTP_NO_CONTENT) throw refusal(status, body)
    }

    /**
     * Stage 4b, R2: takes the main PC's role, with this PC's key. The bundle on 200; HandoverRefusedException when the
     * main PC offers none to this PC. The main PC then waits for [handoverDone].
     */
    suspend fun takeHandover(deviceKey: String, request: HandoverRequest): HandoverBundle = withContext(Dispatchers.IO) {
        requireNotNull(pin) { "A hand-over needs the paired PC's fingerprint." }
        require(KEY.matches(deviceKey)) { "Bad device key." }
        val payload = HandoverWire.encode(request).toByteArray(Charsets.UTF_8)
        val (status, body) = call("POST", SyncPaths.HANDOVER_TAKE, payload, JSON, deviceKey, HandoverWire.MAX_BUNDLE_BYTES)
        if (status != HttpURLConnection.HTTP_OK) throw refusal(status, body)
        try {
            HandoverWire.decodeBundle(body.toString(Charsets.UTF_8))
        } catch (e: IllegalArgumentException) {
            throw SyncHttpException(UNREADABLE)
        }
    }

    /** Stage 4b, R2: this PC has the bundle; the old main PC becomes its client. Done on 204. */
    suspend fun handoverDone(deviceKey: String): Unit = withContext(Dispatchers.IO) {
        requireNotNull(pin) { "A hand-over needs the paired PC's fingerprint." }
        require(KEY.matches(deviceKey)) { "Bad device key." }
        val (status, body) = call("POST", SyncPaths.HANDOVER_DONE, ByteArray(0), null, deviceKey, MAX_SMALL_REPLY_BYTES)
        if (status != HttpURLConnection.HTTP_NO_CONTENT) throw refusal(status, body)
    }

    // One request on its own connection: the status and the capped reply. A 401 `unpaired` is UnpairedException.
    private fun call(method: String, path: String, body: ByteArray?, type: String?, key: String, maxReply: Int, readTimeout: Int = readTimeoutMillis): Pair<Int, ByteArray> {
        val trust = PinTrust(pin)
        val connection = open(path, method, trust, readTimeout)
        try {
            return guarded(trust) {
                connection.setRequestProperty("Authorization", "Bearer $key")
                if (body != null) {
                    connection.doOutput = true
                    if (type != null) connection.setRequestProperty("Content-Type", type)
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { it.write(body) }
                }
                val status = connection.responseCode
                val reply = readReply(connection, status, maxReply)
                // The JDK drops a 401's body when the request body was streamed (it would want to retry with credentials),
                // so a 401 with no body is `unpaired` too: it is the sync port's only 401. Android's client keeps the body.
                if (status == HttpURLConnection.HTTP_UNAUTHORIZED && (reply.isEmpty() || errorOf(reply) == SyncErrors.UNPAIRED)) {
                    throw UnpairedException()
                }
                status to reply
            }
        } finally {
            connection.disconnect()
        }
    }

    // Android lint's BadHostnameVerifier: the pin (PinTrust) replaces the host name check, so any name is accepted here
    // (spec, "For 2c").
    @Suppress("BadHostnameVerifier")
    private fun open(path: String, method: String, trust: PinTrust, readTimeout: Int = readTimeoutMillis): HttpsURLConnection {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(trust), random)
        val connection = URI("https", null, host, port, path, null, null).toURL().openConnection() as HttpsURLConnection
        connection.sslSocketFactory = context.socketFactory
        // The pin replaces the host name check: the PC's certificate is trusted for what it is, not for a name.
        connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
        connection.instanceFollowRedirects = false
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeout
        connection.useCaches = false
        connection.requestMethod = method
        connection.setRequestProperty("Connection", "close")
        connection.setRequestProperty("User-Agent", USER_AGENT)
        return connection
    }

    // Our own failures pass as they are; a refused pin is WrongPcException; anything else is "couldn't reach".
    private inline fun <T> guarded(trust: PinTrust, block: () -> T): T = try {
        block()
    } catch (e: SyncHttpException) {
        throw e
    } catch (e: IOException) {
        if (trust.mismatched) throw WrongPcException()
        throw SyncHttpException(UNREACHABLE)
    }

    private fun refusal(status: Int, body: ByteArray): SyncHttpException = when (errorOf(body)) {
        SyncErrors.UNPAIRED -> UnpairedException()
        SyncErrors.WRONG_CODE -> WrongCodeException()
        SyncErrors.NOT_PAIRING -> NotPairingException()
        SyncErrors.NOT_MASTER -> NotMasterException()
        SyncErrors.NO_HANDOVER -> HandoverRefusedException()
        else -> SyncHttpException("Your PC answered $status.")
    }

    private fun readReply(connection: HttpURLConnection, status: Int, max: Int): ByteArray {
        if (connection.contentLengthLong > max) throw SyncHttpException(TOO_BIG)
        val input: InputStream = (if (status >= 400) connection.errorStream else connection.inputStream) ?: return ByteArray(0)
        return input.use { readAtMost(it, max) }
    }

    private fun readAtMost(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > max) throw SyncHttpException(TOO_BIG)
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun photoPath(uuid: String): String {
        if (!SyncPaths.UUID.matches(uuid)) throw SyncHttpException("A recipe id Meal Planner can't send.")
        return SyncPaths.PHOTO + uuid
    }

    // `{"error":"..."}`'s code, or null for anything else.
    private fun errorOf(body: ByteArray): String? = try {
        ((Json.parseToJsonElement(body.toString(Charsets.UTF_8)) as? JsonObject)?.get("error") as? JsonPrimitive)?.takeIf { it.isString }?.content
    } catch (e: Exception) {
        null
    }

    /** Trusts exactly the certificate with fingerprint [pin]; with none, records the one it was shown. */
    // Android lint's CustomX509TrustManager: this manager is the pin itself, and it throws for any other certificate
    // (spec, "For 2c").
    @Suppress("CustomX509TrustManager")
    private class PinTrust(private val pin: String?) : X509TrustManager {
        @Volatile
        var seen: String? = null

        @Volatile
        var mismatched = false

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?): Unit =
            throw CertificateException("This client checks servers only.")

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("The PC sent no certificate.")
            val fingerprint = PairingProof.fingerprint(leaf.encoded)
            if (pin != null && !PairingProof.matches(pin, fingerprint)) {
                mismatched = true
                throw CertificateException("Not the paired PC.")
            }
            seen = fingerprint
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        const val CONNECT_TIMEOUT_MILLIS = 5_000
        const val READ_TIMEOUT_MILLIS = 30_000

        /** An exchange's read timeout: past the PC's 120 s to send a reply, since it sends nothing until its exchange is done. */
        const val EXCHANGE_READ_TIMEOUT_MILLIS = 150_000
        const val MAX_EXCHANGE_REPLY_BYTES = 64 * 1024 * 1024
        private const val MAX_SMALL_REPLY_BYTES = 4 * 1024
        private const val BUFFER = 64 * 1024
        private const val NONCE_HEX = 32
        private const val PROOF_HEX = 64
        private const val JSON = "application/json; charset=utf-8"
        private const val JPEG = "image/jpeg"
        private const val USER_AGENT = "MealPlanner-sync"
        private val HOST = Regex("[A-Za-z0-9.-]{1,253}")
        private val FINGERPRINT = Regex("[0-9a-f]{64}")
        private val KEY = Regex("[0-9a-f]{64}")

        /** The spec's sentence for a PC that can't be reached. */
        const val UNREACHABLE = "Couldn't reach your PC \u2014 is it on and are you on the home Wi-Fi?"
        const val TOO_BIG = "Your PC sent more than Meal Planner accepts in one reply."
        const val UNREADABLE = "Your PC sent a reply Meal Planner can't read."
    }
}
