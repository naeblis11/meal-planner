package com.naeblis11.mealplanner.update

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Throwaway keys made here, in memory; never the owner's release key. */
class ReleaseSignatureTest {
    private fun pair(curve: String = "secp256r1"): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(curve)) }.generateKeyPair()

    private val keys = pair()
    private val list = """{"format": 1, "tag": "release-2"}""".encodeToByteArray()

    @Test
    fun aSignedListVerifies() {
        val signature = ReleaseSignature.sign(list, keys.private)
        assertTrue(ReleaseSignature.verify(list, signature, keys.public))
        // As the release tool writes it: one line and a newline.
        assertTrue(ReleaseSignature.verify(list, signature + "\n", keys.public))
        assertTrue(signature.length <= ReleaseSignature.MAX_BYTES)
    }

    @Test
    fun aChangedListDoesNot() {
        val signature = ReleaseSignature.sign(list, keys.private)
        assertFalse(ReleaseSignature.verify(list + byteArrayOf(32), signature, keys.public))
    }

    @Test
    fun anotherKeysSignatureDoesNot() {
        assertFalse(ReleaseSignature.verify(list, ReleaseSignature.sign(list, pair().private), keys.public))
    }

    @Test
    fun aMalformedSignatureDoesNot() {
        val bad = listOf(
            "",
            "   ",
            "not base64!",
            Base64.getEncoder().encodeToString(ByteArray(70) { 7 }),
            "A".repeat(ReleaseSignature.MAX_BYTES + 1),
        )
        for (signature in bad) assertFalse(signature.take(20), ReleaseSignature.verify(list, signature, keys.public))
    }

    @Test
    fun onlyAP256KeyIsTheReleaseKey() {
        val encoded = Base64.getEncoder().encodeToString(keys.public.encoded)
        assertArrayEquals(keys.public.encoded, ReleaseSignature.publicKey(encoded).encoded)
        assertTrue(ReleaseSignature.isP256(keys.public))
        val p384 = Base64.getEncoder().encodeToString(pair("secp384r1").public.encoded)
        val rsa = Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public.encoded)
        for (bad in listOf(p384, rsa, "", "!!")) {
            assertThrows(IllegalArgumentException::class.java) { ReleaseSignature.publicKey(bad) }
        }
    }

    @Test
    fun aP384KeysValidSignatureDoesNot() {
        val other = pair("secp384r1")
        val signature = ReleaseSignature.sign(list, other.private)
        assertFalse(ReleaseSignature.verify(list, signature, other.public))
    }
}
