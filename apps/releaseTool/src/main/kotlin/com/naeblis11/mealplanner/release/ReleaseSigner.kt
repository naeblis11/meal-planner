package com.naeblis11.mealplanner.release

import com.naeblis11.mealplanner.update.ManifestException
import com.naeblis11.mealplanner.update.ReleaseManifest
import com.naeblis11.mealplanner.update.ReleaseSignature
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey

/**
 * Signs latest.json with the owner's release key (spec "Updates": ECDSA P-256, SHA256withECDSA). The key is read from
 * its PKCS12 file with the password the owner typed, used once and never written, printed or kept. Nothing is signed
 * unless the list is valid and the signature verifies against the key built into the apps ([builtIn], ReleaseKey), so
 * a release signed with another key can't be published by mistake: every installed copy would ignore it.
 */
object ReleaseSigner {
    const val ALIAS = "meal-planner-release"

    fun loadKey(keystore: File, password: CharArray, alias: String = ALIAS): PrivateKey {
        if (!keystore.isFile) throw ReleaseToolException("There is no release key at $keystore.")
        val store = KeyStore.getInstance("PKCS12")
        try {
            keystore.inputStream().use { store.load(it, password) }
        } catch (e: IOException) {
            throw ReleaseToolException("$keystore couldn't be opened with that password.")
        } catch (e: GeneralSecurityException) {
            throw ReleaseToolException("$keystore couldn't be read.")
        }
        val key = try {
            store.getKey(alias, password)
        } catch (e: GeneralSecurityException) {
            throw ReleaseToolException("The key $alias in $keystore couldn't be read with that password.")
        }
        return (key as? PrivateKey)?.takeIf { it.algorithm == "EC" } ?: throw ReleaseToolException("$keystore holds no EC key named $alias.")
    }

    /**
     * Writes `<manifest>.sig` (one line of base64 and a newline) and returns it. An older `.sig` is removed first, so a
     * refusal never leaves a signature behind that belongs to some earlier list.
     */
    fun signFile(manifest: File, key: PrivateKey, builtIn: PublicKey?): File {
        val out = File(manifest.path + ".sig")
        if (out.exists() && !out.delete()) throw ReleaseToolException("Couldn't remove the old signature $out.")
        if (builtIn == null) {
            throw ReleaseToolException("The apps have no release key built in yet. Run tools\\release-key.ps1 first (docs/RELEASING.md).")
        }
        if (!manifest.isFile) throw ReleaseToolException("There is no release list at $manifest.")
        if (manifest.length() > ReleaseManifest.MAX_BYTES) {
            throw ReleaseToolException("$manifest is larger than ${ReleaseManifest.MAX_BYTES} bytes, which the apps refuse.")
        }
        val bytes = manifest.readBytes()
        try {
            ReleaseManifest.parse(bytes.decodeToString())
        } catch (e: ManifestException) {
            throw ReleaseToolException("$manifest isn't a release list: ${e.message}")
        }
        val signature = try {
            ReleaseSignature.sign(bytes, key)
        } catch (e: GeneralSecurityException) {
            // Only the exception's kind: its message could describe the key.
            throw ReleaseToolException("The release key couldn't sign the list (${e.javaClass.simpleName}); it must be an EC P-256 key.")
        }
        if (!ReleaseSignature.verify(bytes, signature, builtIn)) {
            throw ReleaseToolException(
                "That key isn't the one built into the apps, so every installed copy would ignore this release. Use the release key you backed up.",
            )
        }
        out.writeText(signature + "\n", Charsets.US_ASCII)
        return out
    }
}
