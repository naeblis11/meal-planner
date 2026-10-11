package com.naeblis11.mealplanner.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** LAN sync stage 2b: the pairing proofs, the two pairing messages and the QR text, all shared with the phone (2c). */
class SyncPairingTest {
    private val fp = "ab".repeat(32)
    private val nonce = "0f".repeat(16)
    private val device = "0123456789abcdef0123456789abcdef"

    @Test
    fun theProofsAreHmacSha256OfTheirLabelledText() {
        assertEquals("326212e843aefb55bc43058048f96da31afdd56794a082d40824c5632fe9a70c", PairingProof.device("123456", fp, nonce))
        assertEquals("43090827a03aca30a381bf564039f915bf2a36f80cfe0f923ba77588c4a13182", PairingProof.hub("123456", fp, nonce))
        assertNotEquals(PairingProof.device("123456", fp, nonce), PairingProof.device("123457", fp, nonce))
        assertNotEquals(PairingProof.device("123456", fp, nonce), PairingProof.device("123456", "cd".repeat(32), nonce))
    }

    @Test
    fun proofsAreComparedWhole() {
        val proof = PairingProof.device("123456", fp, nonce)
        assertTrue(PairingProof.matches(proof, proof))
        assertFalse(PairingProof.matches(proof, proof.dropLast(1)))
        assertFalse(PairingProof.matches(proof, "0" + proof.drop(1)))
    }

    @Test
    fun aFingerprintIsTheSha256OfTheDerBytes() {
        assertEquals("2519b49bcf69feac270ac3c8631539e8caed4c7e6a62c6cc5511228a61780745", PairingProof.fingerprint("k".repeat(64).toByteArray()))
    }

    @Test
    fun aPairRequestRoundTrips() {
        val request = PairRequest(device, "Jo's Pixel", PairingWire.KIND_PHONE, nonce, PairingProof.device("123456", fp, nonce))
        assertEquals(request, PairingWire.decodeRequest(PairingWire.encode(request)))
    }

    @Test
    fun aBadPairRequestIsRefused() {
        val good = PairRequest(device, "Pixel", "phone", nonce, "ab".repeat(32))
        val bad = listOf(
            "not json", "[]", "{}",
            PairingWire.encode(good.copy(deviceId = device.uppercase())),
            PairingWire.encode(good.copy(deviceId = device.drop(1))),
            PairingWire.encode(good.copy(name = "")),
            PairingWire.encode(good.copy(name = " ")),
            PairingWire.encode(good.copy(name = "x".repeat(65))),
            PairingWire.encode(good.copy(name = "Pixel\u0007")),
            PairingWire.encode(good.copy(name = "Pixel\u202e")),
            PairingWire.encode(good.copy(kind = "tablet")),
            PairingWire.encode(good.copy(nonce = nonce.drop(2))),
            PairingWire.encode(good.copy(proof = "zz".repeat(32))),
            """{"deviceId":1,"name":"Pixel","kind":"phone","nonce":"$nonce","proof":"${"ab".repeat(32)}"}""",
        )
        for (text in bad) assertThrows(text, IllegalArgumentException::class.java) { PairingWire.decodeRequest(text) }
        assertEquals(good.copy(name = "x".repeat(64)), PairingWire.decodeRequest(PairingWire.encode(good.copy(name = "x".repeat(64)))))
    }

    @Test
    fun aPairReplyRoundTripsAndABadOneIsRefused() {
        val reply = PairReply("ab".repeat(32), "cd".repeat(32), "0".repeat(32), "1".repeat(32), "KITCHEN")
        assertEquals(reply, PairingWire.decodeReply(PairingWire.encode(reply)))
        for (broken in listOf(reply.copy(hubProof = "ab"), reply.copy(deviceKey = "CD".repeat(32)), reply.copy(hubId = ""), reply.copy(householdId = "x".repeat(65)), reply.copy(pcName = ""))) {
            assertThrows(broken.toString(), IllegalArgumentException::class.java) { PairingWire.decodeReply(PairingWire.encode(broken)) }
        }
    }

    @Test
    fun neitherMessageShowsItsSecretsInToString() {
        val request = PairRequest(device, "Pixel", "phone", nonce, "ee".repeat(32))
        assertFalse(request.toString().contains("ee".repeat(32)))
        assertFalse(request.toString().contains(nonce))
        val reply = PairReply("ab".repeat(32), "cd".repeat(32), "0".repeat(32), "1".repeat(32), "KITCHEN")
        assertFalse(reply.toString().contains("cd".repeat(32)))
        assertFalse(reply.toString().contains("ab".repeat(32)))
    }

    @Test
    fun aQrInviteRoundTrips() {
        val text = PairingQr.encode(listOf("192.0.2.20", "198.51.100.5"), 5001, "012345", fp)
        assertEquals("mealplanner://pair?v=1&a=192.0.2.20,198.51.100.5&p=5001&c=012345&f=$fp", text)
        assertEquals(PairingInvite(listOf("192.0.2.20", "198.51.100.5"), 5001, "012345", fp), PairingQr.decode(text))
        assertFalse(PairingQr.decode(text).toString().contains("012345"))
    }

    @Test
    fun aBadQrIsRefused() {
        val base = "mealplanner://pair?"
        val good = "v=1&a=192.0.2.20&p=5001&c=012345&f=$fp"
        val bad = listOf(
            "https://pair?$good", base + good.replace("v=1", "v=2"), base + good.replace("&a=192.0.2.20", ""),
            base + good.replace("192.0.2.20", "1.1.1.1,2.2.2.2,3.3.3.3,4.4.4.4,5.5.5.5"),
            base + good.replace("192.0.2.20", "192.0.2.256"), base + good.replace("192.0.2.20", "pc.local"),
            base + good.replace("192.0.2.20", "01.2.3.4"), base + good.replace("192.0.2.20", ""),
            base + good.replace("p=5001", "p=0"), base + good.replace("p=5001", "p=65536"), base + good.replace("p=5001", "p=50x1"),
            base + good.replace("c=012345", "c=12345"), base + good.replace("c=012345", "c=1234567"), base + good.replace("c=012345", "c=12a456"),
            base + good.replace("f=$fp", "f=${fp.drop(1)}"), base + good.replace("f=$fp", "f=${fp.uppercase()}"),
            "$base$good&c=012345", "$base$good&x=1", base + good.replace("&f=$fp", ""), "$base$good&", base,
        )
        for (text in bad) assertThrows(text, IllegalArgumentException::class.java) { PairingQr.decode(text) }
    }

    @Test
    fun encodingRefusesWhatDecodingWould() {
        assertThrows(IllegalArgumentException::class.java) { PairingQr.encode(emptyList(), 5001, "012345", fp) }
        assertThrows(IllegalArgumentException::class.java) { PairingQr.encode(listOf("192.0.2.20"), 5001, "12345", fp) }
    }
}
