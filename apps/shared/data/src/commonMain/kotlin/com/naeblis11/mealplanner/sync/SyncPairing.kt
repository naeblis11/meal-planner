package com.naeblis11.mealplanner.sync

import com.naeblis11.mealplanner.update.Sha256
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The master PC's sync port (LAN sync spec, section 2): its paths, shared by the routes and the client. */
object SyncPaths {
    const val PAIR = "/sync/pair"
    const val EXCHANGE = "/sync/exchange"

    /** A paired device leaving by itself (stage 2c): removed as the PC's own Remove removes it. */
    const val LEAVE = "/sync/leave"

    /** Stage 4b: the offered client PC takes the main PC's role, then says it is done. */
    const val HANDOVER_TAKE = "/sync/handover/take"
    const val HANDOVER_DONE = "/sync/handover/done"

    /** Followed by a recipe uuid that matches [UUID]. */
    const val PHOTO = "/sync/photo/"

    /** A recipe uuid as it may appear in a path: anything else is no route. */
    val UUID = Regex("[A-Za-z0-9-]{1,64}")
}

/** Every refusal on the sync port is `{"error":"<one of these>"}`. */
object SyncErrors {
    const val NOT_PAIRING = "not_pairing"
    const val WRONG_CODE = "wrong_code"
    const val NOT_MASTER = "not_master"
    const val UNPAIRED = "unpaired"
    const val BAD_REQUEST = "bad_request"
    const val TOO_LARGE = "too_large"
    const val REFUSED = "refused"
    const val NOT_FOUND = "not_found"
    const val FAILED = "failed"

    /** Stage 4b: no hand-over is offered to this device (none, expired, cancelled, or already given up). */
    const val NO_HANDOVER = "no_handover"
}

/**
 * The typed code's proofs (LAN sync spec, section 2). Each side proves it knows the 6-digit code over the certificate
 * fingerprint it saw: a machine in the middle presents another certificate, so the master's check of the device's proof
 * fails, and the device checks the master's answer before it trusts the fingerprint. Lower-case hex HMAC-SHA256, keyed
 * by the code's UTF-8 bytes.
 */
object PairingProof {
    private const val DEVICE = "meal-planner-pair|device|"
    private const val HUB = "meal-planner-pair|hub|"

    fun device(code: String, fingerprint: String, nonce: String): String = hmac(code, "$DEVICE$fingerprint|$nonce")

    fun hub(code: String, fingerprint: String, nonce: String): String = hmac(code, "$HUB$fingerprint|$nonce")

    /** Whole and in constant time. */
    fun matches(expected: String, presented: String): Boolean =
        MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), presented.toByteArray(Charsets.UTF_8))

    /** A certificate's fingerprint: the lower-case hex SHA-256 of its DER encoding. */
    fun fingerprint(der: ByteArray): String = Sha256.of(der)

    private fun hmac(code: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(code.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Sha256.hex(mac.doFinal(data.toByteArray(Charsets.UTF_8)))
    }
}

/** A device asking to pair: who it is, and its proof of the code ([PairingProof.device]) over [nonce]. */
data class PairRequest(val deviceId: String, val name: String, val kind: String, val nonce: String, val proof: String) {
    override fun toString(): String = "PairRequest(deviceId=$deviceId, name=$name, kind=$kind)"
}

/** The master's answer: its proof of the code, the device's new key, and the hub, household and PC it joined. */
data class PairReply(val hubProof: String, val deviceKey: String, val hubId: String, val householdId: String, val pcName: String) {
    override fun toString(): String = "PairReply(hubId=$hubId, householdId=$householdId, pcName=$pcName)"
}

/** The two pairing messages as JSON, in SyncWire's style: decoding trusts nothing, and any fault is IllegalArgumentException. */
object PairingWire {
    const val KIND_PHONE = "phone"
    const val KIND_PC = "pc"
    const val MAX_NAME = 64
    private const val MAX_ID = 64
    private val HEX32 = Regex("[0-9a-f]{32}")
    private val HEX64 = Regex("[0-9a-f]{64}")

    fun encode(request: PairRequest): String = buildJsonObject {
        put("deviceId", request.deviceId)
        put("name", request.name)
        put("kind", request.kind)
        put("nonce", request.nonce)
        put("proof", request.proof)
    }.toString()

    fun encode(reply: PairReply): String = buildJsonObject {
        put("hubProof", reply.hubProof)
        put("deviceKey", reply.deviceKey)
        put("hubId", reply.hubId)
        put("householdId", reply.householdId)
        put("pcName", reply.pcName)
    }.toString()

    fun decodeRequest(text: String): PairRequest {
        val o = parse(text)
        return PairRequest(
            deviceId = text(o, "deviceId").takeIf { HEX32.matches(it) } ?: bad("bad deviceId"),
            name = text(o, "name").takeIf(::isName) ?: bad("bad name"),
            kind = text(o, "kind").takeIf { it == KIND_PHONE || it == KIND_PC } ?: bad("bad kind"),
            nonce = text(o, "nonce").takeIf { HEX32.matches(it) } ?: bad("bad nonce"),
            proof = text(o, "proof").takeIf { HEX64.matches(it) } ?: bad("bad proof"),
        )
    }

    fun decodeReply(text: String): PairReply {
        val o = parse(text)
        return PairReply(
            hubProof = text(o, "hubProof").takeIf { HEX64.matches(it) } ?: bad("bad hubProof"),
            deviceKey = text(o, "deviceKey").takeIf { HEX64.matches(it) } ?: bad("bad deviceKey"),
            hubId = text(o, "hubId").takeIf(::isId) ?: bad("bad hubId"),
            householdId = text(o, "householdId").takeIf(::isId) ?: bad("bad householdId"),
            pcName = text(o, "pcName").takeIf(::isName) ?: bad("bad pcName"),
        )
    }

    /** A device's or PC's name: 1 to MAX_NAME characters, not blank, with no control or format (bidi, zero-width) characters. */
    fun isName(name: String): Boolean =
        name.isNotBlank() && name.length <= MAX_NAME &&
            name.none { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() }

    private fun isId(id: String): Boolean = id.isNotEmpty() && id.length <= MAX_ID && id.all { it in '!'..'~' }

    private fun text(o: JsonObject, name: String): String =
        (o[name] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: bad("$name is missing or not text")

    private fun parse(text: String): JsonObject = try {
        Json.parseToJsonElement(text) as? JsonObject ?: bad("not an object")
    } catch (e: SerializationException) {
        bad("not JSON")
    }

    private fun bad(why: String): Nothing = throw IllegalArgumentException("Bad pairing message: $why.")
}

/** What the pairing QR code holds: where the PC is, its sync port, the code and the certificate's fingerprint. */
data class PairingInvite(val addresses: List<String>, val port: Int, val code: String, val fingerprint: String) {
    override fun toString(): String = "PairingInvite(addresses=$addresses, port=$port)"
}

/**
 * The pairing QR code's text (LAN sync spec, section 2):
 * `mealplanner://pair?v=1&a=<ipv4>,<ipv4>&p=<port>&c=<6 digits>&f=<64 hex>`. Decoding is strict: exactly the five
 * parts, once each, version 1, one to four IPv4 literals, a port in range, a 6-digit code and a 64-hex fingerprint;
 * anything else is IllegalArgumentException. Shared, so the phone (2c) decodes what the PC (2b) encodes.
 */
object PairingQr {
    const val PREFIX = "mealplanner://pair?"
    const val MAX_ADDRESSES = 4
    private const val OCTET = "(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])"
    private val IPV4 = Regex("$OCTET\\.$OCTET\\.$OCTET\\.$OCTET")
    private val CODE = Regex("[0-9]{6}")
    private val FINGERPRINT = Regex("[0-9a-f]{64}")
    private val PORT = Regex("[0-9]{1,5}")
    private val PARTS = setOf("v", "a", "p", "c", "f")

    /** Whether [text] is an IPv4 literal, as the QR code carries: the only kind of address the phone gives SyncHttp. */
    fun isIpv4(text: String): Boolean = IPV4.matches(text)

    fun encode(addresses: List<String>, port: Int, code: String, fingerprint: String): String {
        validate(PairingInvite(addresses, port, code, fingerprint))
        return "${PREFIX}v=1&a=${addresses.joinToString(",")}&p=$port&c=$code&f=$fingerprint"
    }

    fun decode(text: String): PairingInvite {
        if (!text.startsWith(PREFIX)) bad("not a Meal Planner pairing code")
        val parts = text.substring(PREFIX.length).split('&').map { part ->
            val at = part.indexOf('=')
            if (at <= 0) bad("a part has no name or value")
            part.substring(0, at) to part.substring(at + 1)
        }
        if (parts.size != PARTS.size || parts.map { it.first }.toSet() != PARTS) bad("the parts aren't v, a, p, c and f, once each")
        val values = parts.toMap()
        if (values.getValue("v") != "1") bad("unknown version")
        val port = values.getValue("p").takeIf { PORT.matches(it) }?.toInt() ?: bad("bad port")
        return PairingInvite(values.getValue("a").split(','), port, values.getValue("c"), values.getValue("f")).also(::validate)
    }

    private fun validate(invite: PairingInvite) {
        if (invite.addresses.size !in 1..MAX_ADDRESSES || !invite.addresses.all { IPV4.matches(it) }) bad("bad addresses")
        if (invite.port !in 1..65535) bad("bad port")
        if (!CODE.matches(invite.code)) bad("bad code")
        if (!FINGERPRINT.matches(invite.fingerprint)) bad("bad fingerprint")
    }

    private fun bad(why: String): Nothing = throw IllegalArgumentException("Bad pairing code: $why.")
}
