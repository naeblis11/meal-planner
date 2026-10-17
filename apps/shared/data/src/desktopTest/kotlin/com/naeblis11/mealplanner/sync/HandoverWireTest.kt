package com.naeblis11.mealplanner.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 4b: the hand-over's request and bundle on the wire, and the exchange reply's offer. */
class HandoverWireTest {
    private val request = HandoverRequest("STUDY", listOf("192.0.2.30", "192.0.2.31"), 5001, "c".repeat(32), "ab".repeat(32))
    private val phone = HandedDevice("1".repeat(32), "Pixel", PairingWire.KIND_PHONE, "2".repeat(64), 100L, 200L)
    private val pc = HandedDevice("3".repeat(32), "LAPTOP", PairingWire.KIND_PC, "4".repeat(64), 300L, null)
    private val bundle = HandoverBundle(listOf(phone, pc), byteArrayOf(1, 2, 3, 0, -1), "p".repeat(64), "t".repeat(64), "5".repeat(32), "KITCHEN")

    private fun edited(text: String, key: String, value: JsonPrimitive?): String {
        val o = Json.parseToJsonElement(text).jsonObject
        return JsonObject(if (value == null) o - key else o + (key to value)).toString()
    }

    @Test
    fun aRequestGoesAndComesBack() {
        assertEquals(request, HandoverWire.decodeRequest(HandoverWire.encode(request)))
        assertFalse("never the key", request.toString().contains(request.masterKey))
    }

    @Test
    fun aBadRequestIsRefused() {
        val good = HandoverWire.encode(request)
        for ((key, value) in listOf(
            "pcName" to JsonPrimitive(""), "port" to JsonPrimitive(0), "port" to JsonPrimitive(70000), "hubId" to JsonPrimitive(""),
            "masterKey" to JsonPrimitive("AB".repeat(32)), "masterKey" to JsonPrimitive("ab"),
        )) {
            assertThrows("$key=$value", IllegalArgumentException::class.java) { HandoverWire.decodeRequest(edited(good, key, value)) }
        }
        assertThrows(IllegalArgumentException::class.java) { HandoverWire.decodeRequest(good.replace("192.0.2.30", "fe80::1")) }
        assertThrows(IllegalArgumentException::class.java) { HandoverWire.decodeRequest(good.replace("\"192.0.2.30\",\"192.0.2.31\"", "")) }
        assertThrows(IllegalArgumentException::class.java) { HandoverWire.decodeRequest("[]") }
    }

    @Test
    fun aBundleGoesAndComesBackWithOrWithoutAToken() {
        assertEquals(bundle, HandoverWire.decodeBundle(HandoverWire.encode(bundle)))
        val noToken = HandoverBundle(emptyList(), byteArrayOf(9), "pw-1234", null, "5".repeat(32), "KITCHEN")
        val back = HandoverWire.decodeBundle(HandoverWire.encode(noToken))
        assertNull(back.alexaToken)
        assertEquals(noToken, back)
        val shown = bundle.toString()
        assertFalse(shown.contains("p".repeat(64)))
        assertFalse(shown.contains("t".repeat(64)))
    }

    @Test
    fun aBadBundleIsRefused() {
        val good = HandoverWire.encode(bundle)
        for ((key, value) in listOf(
            "certificate" to JsonPrimitive("not base64!"), "certificate" to JsonPrimitive(""),
            "certificatePassword" to JsonPrimitive(""), "certificatePassword" to JsonPrimitive("a b"),
            "alexaToken" to JsonPrimitive("short"), "alexaToken" to JsonPrimitive(1),
            "masterDeviceId" to JsonPrimitive("x"), "masterName" to JsonPrimitive(""),
        )) {
            assertThrows("$key=$value", IllegalArgumentException::class.java) { HandoverWire.decodeBundle(edited(good, key, value)) }
        }
        assertThrows(IllegalArgumentException::class.java) { HandoverWire.decodeBundle(good.replace("\"phone\"", "\"tablet\"")) }
        val twice = HandoverBundle(listOf(phone, phone), byteArrayOf(1), "pw-1234", null, "5".repeat(32), "KITCHEN")
        assertThrows(IllegalArgumentException::class.java) { HandoverWire.decodeBundle(HandoverWire.encode(twice)) }
        val big = HandoverBundle(emptyList(), ByteArray(HandoverWire.MAX_CERTIFICATE_BYTES + 1), "pw-1234", null, "5".repeat(32), "KITCHEN")
        assertThrows(IllegalArgumentException::class.java) { HandoverWire.decodeBundle(HandoverWire.encode(big)) }
    }

    @Test
    fun anExchangeReplyCarriesTheOfferOnlyWhenThereIsOne() {
        val reply = ExchangeReply(1L, 2L, false, emptyList(), emptyList(), emptyList(), hubId = "hub-1")
        val plain = SyncWire.encode(reply)
        assertFalse(plain.contains("handover"))
        assertFalse(SyncWire.decodeReply(plain).handover)
        val offered = SyncWire.encode(reply.copy(handover = true))
        assertTrue(SyncWire.decodeReply(offered).handover)
        assertThrows(IllegalArgumentException::class.java) { SyncWire.decodeReply(offered.replace("\"handover\":true", "\"handover\":\"yes\"")) }
    }
}
