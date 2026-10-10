package com.naeblis11.mealplanner.release

import com.naeblis11.mealplanner.update.ReleaseSignature
import java.io.File
import java.nio.file.Files
import java.security.cert.CertificateFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class PublicKeyFileTest {
    private val apps = File(System.getProperty("appsDir") ?: error("appsDir is not set; run through Gradle"))
    private val dir: File = Files.createTempDirectory("mp-release-public-key").toFile()
    private val password = "test-only-password"

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun certificate(name: String, curve: String = "secp256r1"): File {
        val folder = File(dir, name).apply { mkdirs() }
        return Keytool.exportCertificate(Keytool.makeStore(folder, password, curve), password, File(folder, "test-key.cer"))
    }

    @Test
    fun itWritesTheCertificatesKeyForTheApps() {
        val cert = certificate("a")
        val target = File(dir, "ReleaseKeyData.kt").apply { writeText(PublicKeyFile.render("")) }
        PublicKeyFile.write(cert, target, replace = false)
        val fromCert = cert.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }.publicKey
        assertArrayEquals(fromCert.encoded, ReleaseSignature.publicKey(PublicKeyFile.current(target)!!).encoded)
        assertFalse(target.readText().contains("PRIVATE"))
    }

    @Test
    fun theCommittedFileIsWhatTheToolWrites() {
        val committed = File(apps, PublicKeyFile.PATH)
        val key = PublicKeyFile.current(committed) ?: error("${PublicKeyFile.PATH} has no PUBLIC_KEY line the tool can read")
        assertEquals(PublicKeyFile.render(key), committed.readText().replace("\r\n", "\n"))
    }

    @Test
    fun itNeverQuietlyReplacesAKeyTheAppsTrust() {
        val first = certificate("first")
        val second = certificate("second")
        val target = File(dir, "ReleaseKeyData.kt").apply { writeText(PublicKeyFile.render("")) }
        PublicKeyFile.write(first, target, replace = false)
        val trusted = PublicKeyFile.current(target)
        // The same key again is fine (a re-run); another one is refused unless the owner says the old one is lost.
        PublicKeyFile.write(first, target, replace = false)
        assertThrows(ReleaseToolException::class.java) { PublicKeyFile.write(second, target, replace = false) }
        assertEquals(trusted, PublicKeyFile.current(target))
        PublicKeyFile.write(second, target, replace = true)
        assertFalse(trusted == PublicKeyFile.current(target))
    }

    @Test
    fun onlyAP256KeyIsAccepted() {
        val p384 = certificate("p384", curve = "secp384r1")
        val target = File(dir, "ReleaseKeyData.kt").apply { writeText(PublicKeyFile.render("")) }
        assertThrows(ReleaseToolException::class.java) { PublicKeyFile.write(p384, target, replace = false) }
        assertEquals("", PublicKeyFile.current(target))
    }
}
