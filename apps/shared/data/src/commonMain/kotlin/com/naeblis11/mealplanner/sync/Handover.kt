package com.naeblis11.mealplanner.sync

import java.util.Base64
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * What a client PC asks for when it takes over as the main PC (LAN sync stage 4b, R2): its name, its home-network
 * [addresses] and sync [port] (where the old main PC will find it as a client), its own [hubId], and [masterKey], the
 * device key it made for the old main PC. [toString] never shows the key.
 */
data class HandoverRequest(
    val pcName: String,
    val addresses: List<String>,
    val port: Int,
    val hubId: String,
    val masterKey: String,
) {
    override fun toString(): String = "HandoverRequest(pcName=$pcName, addresses=$addresses, port=$port, hubId=$hubId)"
}

/** One paired device as the old main PC hands it over: only its key's hash, never the key. */
data class HandedDevice(
    val deviceId: String,
    val name: String,
    val kind: String,
    val keyHash: String,
    val pairedAt: Long,
    val lastSyncAt: Long?,
)

/**
 * The main PC's role as it is handed over (stage 4b, R3): its paired devices except the taker, its sync certificate (the
 * PKCS#12 file's bytes and its password), its Alexa token or null, and its own device id and name, so the taker lists it
 * as a PC. Sent only over the pinned TLS connection; [toString] shows none of the secrets.
 */
class HandoverBundle(
    val devices: List<HandedDevice>,
    val certificate: ByteArray,
    val certificatePassword: String,
    val alexaToken: String?,
    val masterDeviceId: String,
    val masterName: String,
) {
    override fun toString(): String = "HandoverBundle(devices=${devices.size}, masterName=$masterName, token=${alexaToken != null})"

    override fun equals(other: Any?): Boolean =
        other is HandoverBundle && devices == other.devices && certificate.contentEquals(other.certificate) &&
            certificatePassword == other.certificatePassword && alexaToken == other.alexaToken &&
            masterDeviceId == other.masterDeviceId && masterName == other.masterName

    override fun hashCode(): Int = masterDeviceId.hashCode()
}

/** The hand-over's two messages as JSON. Decoding trusts nothing: anything malformed or out of bounds throws IllegalArgumentException. */
object HandoverWire {
    const val MAX_DEVICES = 256
    const val MAX_CERTIFICATE_BYTES = 64 * 1024
    const val MAX_PASSWORD = 128
    const val MAX_TOKEN = 256

    /** The bundle's largest JSON, with room to spare: what the taker reads at most. */
    const val MAX_BUNDLE_BYTES = 256 * 1024
    const val MAX_REQUEST_BYTES = 4 * 1024

    private val HEX32 = Regex("[0-9a-f]{32}")
    private val HEX64 = Regex("[0-9a-f]{64}")

    fun encode(request: HandoverRequest): String = buildJsonObject {
        put("pcName", request.pcName)
        put("addresses", buildJsonArray { request.addresses.forEach { add(JsonPrimitive(it)) } })
        put("port", request.port)
        put("hubId", request.hubId)
        put("masterKey", request.masterKey)
    }.toString()

    fun decodeRequest(text: String): HandoverRequest {
        val o = parse(text)
        val addresses = (o["addresses"] as? JsonArray ?: bad("addresses is not a list")).map { e ->
            (e as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(PairingQr::isIpv4) ?: bad("bad address")
        }
        if (addresses.isEmpty() || addresses.size > PairingQr.MAX_ADDRESSES) bad("bad addresses")
        return HandoverRequest(
            pcName = text(o, "pcName").takeIf(PairingWire::isName) ?: bad("bad pcName"),
            addresses = addresses,
            port = long(o, "port").takeIf { it in 1..65535 }?.toInt() ?: bad("bad port"),
            hubId = text(o, "hubId").takeIf(::isId) ?: bad("bad hubId"),
            masterKey = text(o, "masterKey").takeIf { HEX64.matches(it) } ?: bad("bad masterKey"),
        )
    }

    fun encode(bundle: HandoverBundle): String = buildJsonObject {
        put(
            "devices",
            buildJsonArray {
                bundle.devices.forEach { d ->
                    add(
                        buildJsonObject {
                            put("deviceId", d.deviceId)
                            put("name", d.name)
                            put("kind", d.kind)
                            put("keyHash", d.keyHash)
                            put("pairedAt", d.pairedAt)
                            d.lastSyncAt?.let { put("lastSyncAt", it) }
                        },
                    )
                }
            },
        )
        put("certificate", Base64.getEncoder().encodeToString(bundle.certificate))
        put("certificatePassword", bundle.certificatePassword)
        bundle.alexaToken?.let { put("alexaToken", it) }
        put("masterDeviceId", bundle.masterDeviceId)
        put("masterName", bundle.masterName)
    }.toString()

    fun decodeBundle(text: String): HandoverBundle {
        val o = parse(text)
        val list = o["devices"] as? JsonArray ?: bad("devices is not a list")
        if (list.size > MAX_DEVICES) bad("too many devices")
        val devices = list.map { e ->
            val d = e as? JsonObject ?: bad("a device is not an object")
            HandedDevice(
                deviceId = text(d, "deviceId").takeIf { HEX32.matches(it) } ?: bad("bad deviceId"),
                name = text(d, "name").takeIf(PairingWire::isName) ?: bad("bad name"),
                kind = text(d, "kind").takeIf { it == PairingWire.KIND_PHONE || it == PairingWire.KIND_PC } ?: bad("bad kind"),
                keyHash = text(d, "keyHash").takeIf { HEX64.matches(it) } ?: bad("bad keyHash"),
                pairedAt = long(d, "pairedAt").takeIf { it >= 0 } ?: bad("bad pairedAt"),
                lastSyncAt = if (d["lastSyncAt"] == null || d["lastSyncAt"] is JsonNull) null else long(d, "lastSyncAt"),
            )
        }
        if (devices.map { it.deviceId }.toSet().size != devices.size) bad("a device is listed twice")
        val certificate = try {
            Base64.getDecoder().decode(text(o, "certificate"))
        } catch (e: IllegalArgumentException) {
            bad("certificate is not base64")
        }
        if (certificate.isEmpty() || certificate.size > MAX_CERTIFICATE_BYTES) bad("bad certificate")
        val token = (o["alexaToken"] as? JsonPrimitive)?.let { p ->
            p.takeIf { it.isString }?.content?.takeIf { it.length in 16..MAX_TOKEN && it.all { c -> c in '!'..'~' } } ?: bad("bad alexaToken")
        }
        return HandoverBundle(
            devices = devices,
            certificate = certificate,
            certificatePassword = text(o, "certificatePassword").takeIf { it.length in 1..MAX_PASSWORD && it.all { c -> c in '!'..'~' } }
                ?: bad("bad certificatePassword"),
            alexaToken = token,
            masterDeviceId = text(o, "masterDeviceId").takeIf { HEX32.matches(it) } ?: bad("bad masterDeviceId"),
            masterName = text(o, "masterName").takeIf(PairingWire::isName) ?: bad("bad masterName"),
        )
    }

    private fun isId(id: String): Boolean = id.isNotEmpty() && id.length <= 64 && id.all { it in '!'..'~' }

    private fun text(o: JsonObject, name: String): String =
        (o[name] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: bad("$name is missing or not text")

    private fun long(o: JsonObject, name: String): Long =
        (o[name] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: bad("$name is not a number")

    private fun parse(text: String): JsonObject = try {
        Json.parseToJsonElement(text) as? JsonObject ?: bad("not an object")
    } catch (e: SerializationException) {
        bad("not JSON")
    }

    private fun bad(why: String): Nothing = throw IllegalArgumentException("Bad hand-over message: $why.")
}
