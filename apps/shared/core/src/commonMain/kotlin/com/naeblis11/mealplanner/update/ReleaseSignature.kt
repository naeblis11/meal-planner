package com.naeblis11.mealplanner.update

import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * latest.json's signature (spec "Updates"): ECDSA on P-256 with SHA-256, which the JDK and every Android from API 26
 * verify without a library. The signature file is the DER signature in base64, one line. The public key is an X.509
 * SubjectPublicKeyInfo in base64, as ReleaseKeyData holds it.
 */
object ReleaseSignature {
    const val ALGORITHM = "SHA256withECDSA"

    /** The most latest.json.sig may be (the brief: 1 KB). Compared with String.length; base64 is ASCII, so characters equal bytes. */
    const val MAX_BYTES = 1024

    // The named secp256r1 parameters (curve, generator, order, cofactor) from the JDK's own table.
    private val P256: ECParameterSpec = AlgorithmParameters.getInstance("EC").apply {
        init(ECGenParameterSpec("secp256r1"))
    }.getParameterSpec(ECParameterSpec::class.java)

    /** The release key from its base64 X.509 encoding; IllegalArgumentException unless it is an EC P-256 public key. */
    fun publicKey(base64: String): PublicKey {
        val bytes = try {
            Base64.getDecoder().decode(base64.trim())
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("The release key isn't base64.")
        }
        val key = try {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
        } catch (e: Exception) {
            throw IllegalArgumentException("The release key isn't an EC public key.")
        }
        require(isP256(key)) { "The release key isn't on the P-256 curve." }
        return key
    }

    fun isP256(key: PublicKey): Boolean {
        val params = (key as? ECPublicKey)?.params ?: return false
        return params.curve == P256.curve && params.generator == P256.generator &&
            params.order == P256.order && params.cofactor == P256.cofactor
    }

    /** The release tool's half (and the tests'): [data] signed with [key], in base64. */
    fun sign(data: ByteArray, key: PrivateKey): String {
        val signer = Signature.getInstance(ALGORITHM)
        signer.initSign(key)
        signer.update(data)
        return Base64.getEncoder().encodeToString(signer.sign())
    }

    /** True only when [signature] (base64; surrounding whitespace ignored) is [key]'s over exactly [data]. Never throws. */
    fun verify(data: ByteArray, signature: String, key: PublicKey): Boolean {
        if (!isP256(key)) return false
        if (signature.length > MAX_BYTES) return false
        val raw = try {
            Base64.getDecoder().decode(signature.trim())
        } catch (e: IllegalArgumentException) {
            return false
        }
        if (raw.isEmpty()) return false
        return try {
            val verifier = Signature.getInstance(ALGORITHM)
            verifier.initVerify(key)
            verifier.update(data)
            verifier.verify(raw)
        } catch (e: Exception) {
            // A signature that isn't DER (SignatureException), or a key the provider won't take.
            false
        }
    }
}
