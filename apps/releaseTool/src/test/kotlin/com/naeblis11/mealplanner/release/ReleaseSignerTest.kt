package com.naeblis11.mealplanner.release

import com.naeblis11.mealplanner.update.DesktopRelease
import com.naeblis11.mealplanner.update.ReleaseManifest
import com.naeblis11.mealplanner.update.ReleaseSignature
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.cert.CertificateFactory
import java.security.spec.ECGenParameterSpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The signer on a throwaway keytool key in a temp folder; never the owner's release key. */
class ReleaseSignerTest {
    private val dir: File = Files.createTempDirectory("mp-release-signer").toFile()
    private val password = "test-only-password"
    private val list = File(dir, "latest.json").apply {
        writeText(ReleaseManifest("release-2", DesktopRelease("1.0.1", "MealPlanner-1.0.1.msi", 10, "a".repeat(64)), null).toJson())
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun certificateKey(store: File): PublicKey {
        val cert = Keytool.exportCertificate(store, password, File(dir, "test-key.cer"))
        return cert.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }.publicKey
    }

    @Test
    fun itSignsWithTheKeyFileAndTheAppsVerify() {
        val store = Keytool.makeStore(dir, password)
        val builtIn = certificateKey(store)
        val signature = ReleaseSigner.signFile(list, ReleaseSigner.loadKey(store, password.toCharArray()), builtIn)
        assertEquals("latest.json.sig", signature.name)
        assertTrue(ReleaseSignature.verify(list.readBytes(), signature.readText(), builtIn))
        assertTrue(signature.length() <= ReleaseSignature.MAX_BYTES)
    }

    @Test
    fun aWrongPasswordIsRefused() {
        val store = Keytool.makeStore(dir, password)
        assertThrows(ReleaseToolException::class.java) { ReleaseSigner.loadKey(store, "wrong-password".toCharArray()) }
        assertThrows(ReleaseToolException::class.java) { ReleaseSigner.loadKey(File(dir, "none.p12"), password.toCharArray()) }
    }

    @Test
    fun aKeyTheAppsDontTrustSignsNothing() {
        val store = Keytool.makeStore(dir, password)
        val other = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public
        val key = ReleaseSigner.loadKey(store, password.toCharArray())
        assertThrows(ReleaseToolException::class.java) { ReleaseSigner.signFile(list, key, other) }
        assertFalse(File(dir, "latest.json.sig").exists())
    }

    @Test
    fun withNoKeyBuiltIntoTheAppsNothingIsSigned() {
        val store = Keytool.makeStore(dir, password)
        assertThrows(ReleaseToolException::class.java) { ReleaseSigner.signFile(list, ReleaseSigner.loadKey(store, password.toCharArray()), null) }
        assertFalse(File(dir, "latest.json.sig").exists())
    }

    @Test
    fun onlyAValidListIsSigned() {
        val store = Keytool.makeStore(dir, password)
        val builtIn = certificateKey(store)
        list.writeText("{}")
        assertThrows(ReleaseToolException::class.java) { ReleaseSigner.signFile(list, ReleaseSigner.loadKey(store, password.toCharArray()), builtIn) }
        assertFalse(File(dir, "latest.json.sig").exists())
    }

    @Test
    fun aListLargerThanTheAppsReadIsRefused() {
        val store = Keytool.makeStore(dir, password)
        val builtIn = certificateKey(store)
        list.writeText(" ".repeat(ReleaseManifest.MAX_BYTES + 1))
        assertThrows(ReleaseToolException::class.java) { ReleaseSigner.signFile(list, ReleaseSigner.loadKey(store, password.toCharArray()), builtIn) }
        assertFalse(File(dir, "latest.json.sig").exists())
    }

    @Test
    fun aRefusedSignLeavesNoOldSignatureBehind() {
        val store = Keytool.makeStore(dir, password)
        val key = ReleaseSigner.loadKey(store, password.toCharArray())
        ReleaseSigner.signFile(list, key, certificateKey(store))
        assertTrue(File(dir, "latest.json.sig").exists())
        val other = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public
        assertThrows(ReleaseToolException::class.java) { ReleaseSigner.signFile(list, key, other) }
        assertFalse(File(dir, "latest.json.sig").exists())
    }

    @Test
    fun aKeyThatCantSignIsRefusedWithoutDescribingIt() {
        val store = Keytool.makeStore(dir, password)
        val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private
        val e = assertThrows(ReleaseToolException::class.java) { ReleaseSigner.signFile(list, rsa, certificateKey(store)) }
        assertFalse(e.message!!.contains(java.util.Base64.getEncoder().encodeToString(rsa.encoded).take(40)))
        assertFalse(File(dir, "latest.json.sig").exists())
    }
}
