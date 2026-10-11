package com.naeblis11.mealplanner.sync

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * What a phone keeps of its pairing with the PC (LAN sync stage 2c, D1):
 * - where the PC was last reached: [addresses] (the one that worked first; one to PairingQr.MAX_ADDRESSES IPv4
 *   literals) and [port];
 * - the certificate [fingerprint] every sync is pinned to;
 * - the PC's [pcName], this phone's secret [deviceKey], and the [hubId] and [householdId] the PC sent.
 * Checked when made, so a damaged file never becomes a pairing. [toString] hides the key and the fingerprint.
 */
data class PhonePairing(
    val addresses: List<String>,
    val port: Int,
    val fingerprint: String,
    val pcName: String,
    val deviceKey: String,
    val hubId: String,
    val householdId: String,
) {
    init {
        require(addresses.size in 1..PairingQr.MAX_ADDRESSES && addresses.all(PairingQr::isIpv4)) { "Bad addresses." }
        require(port in 1..65535) { "Bad port." }
        require(HEX64.matches(fingerprint)) { "Bad fingerprint." }
        require(PairingWire.isName(pcName)) { "Bad PC name." }
        require(HEX64.matches(deviceKey)) { "Bad device key." }
        require(ID.matches(hubId)) { "Bad hub id." }
        require(ID.matches(householdId)) { "Bad household id." }
    }

    override fun toString(): String = "PhonePairing(addresses=$addresses, port=$port, pcName=$pcName, hubId=$hubId)"

    private companion object {
        val HEX64 = Regex("[0-9a-f]{64}")
        val ID = Regex("[!-~]{1,64}")
    }
}

/** The pairing as the bytes the phone seals: JSON, version 1. [decode] is strict: anything else is IllegalArgumentException. */
object PhonePairingCodec {
    private const val VERSION = 1

    fun encode(pairing: PhonePairing): ByteArray = buildJsonObject {
        put("v", VERSION)
        put("addresses", JsonArray(pairing.addresses.map { JsonPrimitive(it) }))
        put("port", pairing.port)
        put("fingerprint", pairing.fingerprint)
        put("pcName", pairing.pcName)
        put("deviceKey", pairing.deviceKey)
        put("hubId", pairing.hubId)
        put("householdId", pairing.householdId)
    }.toString().toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): PhonePairing {
        val o = try {
            Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: bad("not a JSON object")
        if ((o["v"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull != VERSION) bad("unknown version")
        val addresses = (o["addresses"] as? JsonArray)
            ?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: bad("bad addresses") }
            ?: bad("no addresses")
        val port = (o["port"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: bad("bad port")
        return try {
            PhonePairing(addresses, port, text(o, "fingerprint"), text(o, "pcName"), text(o, "deviceKey"), text(o, "hubId"), text(o, "householdId"))
        } catch (e: IllegalArgumentException) {
            bad("a value is out of range")
        }
    }

    private fun text(o: JsonObject, name: String): String =
        (o[name] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: bad("$name is missing or not text")

    private fun bad(why: String): Nothing = throw IllegalArgumentException("Bad pairing file: $why.")
}

/** Where the phone keeps its pairing (D1): the Keystore-sealed file on the phone, memory in tests. */
interface PairingStore {
    /**
     * The pairing, or null when there is none or it can't be read (a file restored from another phone, a key Android
     * invalidated, damage): the phone is then simply not paired, and keeps its data.
     */
    suspend fun load(): PhonePairing?

    suspend fun save(pairing: PhonePairing)

    suspend fun clear()
}

/** A pairing kept only as long as the process: tests, as MemorySettings is. */
class MemoryPairingStore(@Volatile private var pairing: PhonePairing? = null) : PairingStore {
    override suspend fun load(): PhonePairing? = pairing

    override suspend fun save(pairing: PhonePairing) {
        this.pairing = pairing
    }

    override suspend fun clear() {
        pairing = null
    }
}
