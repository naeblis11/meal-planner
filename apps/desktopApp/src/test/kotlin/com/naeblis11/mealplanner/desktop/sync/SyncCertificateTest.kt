package com.naeblis11.mealplanner.desktop.sync

import com.naeblis11.mealplanner.desktop.google.FakeProtector
import com.naeblis11.mealplanner.desktop.google.SecretProtector
import com.naeblis11.mealplanner.update.Sha256
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** LAN sync stage 2b (D2): the sync port's self-signed certificate, made once, its password sealed, remade when unreadable. */
class SyncCertificateTest {
    private val dir: File = Files.createTempDirectory("mp-cert").toFile()
    private val logs = CopyOnWriteArrayList<String>()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun cert(protector: SecretProtector = FakeProtector()) = SyncCertificate(dir, protector, log = { logs += it })

    @Test
    fun theFirstUseMakesAnRsa2048CertificateForTwentyYears() {
        val certificate = cert()
        val leaf = certificate.certificate
        assertEquals("RSA", leaf.publicKey.algorithm)
        assertTrue((leaf.publicKey as RSAPublicKey).modulus.bitLength() >= 2048)
        assertEquals("SHA256withRSA", leaf.sigAlgName)
        assertTrue(leaf.subjectX500Principal.name, leaf.subjectX500Principal.name.contains("CN=Meal Planner"))
        assertEquals(20L, Duration.between(leaf.notBefore.toInstant(), leaf.notAfter.toInstant()).toDays() / 365)
        assertTrue(File(dir, SyncCertificate.STORE_FILE).isFile)
        assertTrue(File(dir, SyncCertificate.PASSWORD_FILE).isFile)
        assertNull(certificate.notice.value)
    }

    @Test
    fun theFingerprintIsTheSha256OfTheLeafsDer() {
        val certificate = cert()
        assertEquals(Sha256.of(certificate.certificate.encoded), certificate.fingerprint)
        assertTrue(Regex("[0-9a-f]{64}").matches(certificate.fingerprint))
    }

    @Test
    fun theNextStartReadsTheSameCertificate() {
        val first = cert().fingerprint
        val again = cert()
        assertEquals(first, again.fingerprint)
        assertNull(again.notice.value)
    }

    @Test
    fun thePasswordIsSealedNeverPlain() {
        cert().fingerprint
        val sealed = File(dir, SyncCertificate.PASSWORD_FILE).readBytes()
        assertTrue(String(sealed, Charsets.ISO_8859_1).startsWith("fake-dpapi:"))
        val plain = String(FakeProtector().unprotect(sealed), Charsets.UTF_8)
        assertTrue(Regex("[0-9a-f]{64}").matches(plain))
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains(plain))
    }

    @Test
    fun aDamagedKeystoreIsRemadeAndSaysSo() {
        val first = cert().fingerprint
        val password = File(dir, SyncCertificate.PASSWORD_FILE).readBytes()
        File(dir, SyncCertificate.STORE_FILE).writeBytes(byteArrayOf(1, 2, 3))
        val again = cert()
        assertNotEquals(first, again.fingerprint)
        assertEquals(SyncCertificate.REMADE_NOTICE, again.notice.value)
        // The damaged pair is kept beside the new one, never overwritten.
        assertArrayEquals(byteArrayOf(1, 2, 3), File(dir, SyncCertificate.STORE_FILE + SyncCertificate.BAD_SUFFIX).readBytes())
        assertArrayEquals(password, File(dir, SyncCertificate.PASSWORD_FILE + SyncCertificate.BAD_SUFFIX).readBytes())
        assertTrue(logs.toString(), logs.any { it.contains("can't be read") })
        // The remade one is what the next start reads, without another notice.
        val third = cert()
        assertEquals(again.fingerprint, third.fingerprint)
        assertNull(third.notice.value)
    }

    @Test
    fun aPasswordThatCantBeUnsealedIsRemadeAndOnlyItsClassIsLogged() {
        cert().fingerprint
        val refusing = object : SecretProtector {
            override fun protect(plain: ByteArray): ByteArray = FakeProtector().protect(plain)
            override fun unprotect(sealed: ByteArray): ByteArray = throw IOException("secret-detail")
        }
        val again = SyncCertificate(dir, refusing, log = { logs += it })
        again.fingerprint
        assertEquals(SyncCertificate.REMADE_NOTICE, again.notice.value)
        assertTrue(logs.toString(), logs.any { it.contains("java.io.IOException") })
        assertFalse(logs.toString(), logs.any { it.contains("secret-detail") })
    }

    @Test
    fun aMissingHalfIsRemade() {
        cert().fingerprint
        val store = File(dir, SyncCertificate.STORE_FILE).readBytes()
        File(dir, SyncCertificate.PASSWORD_FILE).delete()
        val again = cert()
        again.fingerprint
        assertEquals(SyncCertificate.REMADE_NOTICE, again.notice.value)
        assertArrayEquals(store, File(dir, SyncCertificate.STORE_FILE + SyncCertificate.BAD_SUFFIX).readBytes())
    }

    @Test
    fun anOlderBadPairIsReplacedByTheNewestOne() {
        File(dir, SyncCertificate.STORE_FILE + SyncCertificate.BAD_SUFFIX).writeBytes(byteArrayOf(9))
        File(dir, SyncCertificate.PASSWORD_FILE + SyncCertificate.BAD_SUFFIX).writeBytes(byteArrayOf(9))
        cert().fingerprint
        File(dir, SyncCertificate.STORE_FILE).writeBytes(byteArrayOf(1, 2, 3))
        cert().fingerprint
        assertArrayEquals(byteArrayOf(1, 2, 3), File(dir, SyncCertificate.STORE_FILE + SyncCertificate.BAD_SUFFIX).readBytes())
        assertTrue(File(dir, SyncCertificate.PASSWORD_FILE + SyncCertificate.BAD_SUFFIX).readBytes().size > 1)
    }

    @Test
    fun aFileThatCantBeReadJustNowIsAFailureNotARemake() {
        val first = cert().fingerprint
        val store = File(dir, SyncCertificate.STORE_FILE).readBytes()
        for (failing in listOf(SyncCertificate.STORE_FILE, SyncCertificate.PASSWORD_FILE)) {
            val flaky = SyncCertificate(dir, FakeProtector(), log = { logs += it }, read = { f -> if (f.name == failing) throw IOException("secret-busy") else f.readBytes() })
            assertThrows(IOException::class.java) { flaky.fingerprint }
            assertNull(flaky.notice.value)
            // Nothing was replaced or set aside: the next try (or the next start) reads the same certificate.
            assertArrayEquals(store, File(dir, SyncCertificate.STORE_FILE).readBytes())
            assertFalse(File(dir, SyncCertificate.STORE_FILE + SyncCertificate.BAD_SUFFIX).exists())
        }
        assertFalse(logs.toString(), logs.any { it.contains("secret-busy") })
        assertEquals(first, cert().fingerprint)
    }

    @Test
    fun itsContextCanSpeakTls13() {
        assertTrue(cert().sslContext().supportedSSLParameters.protocols.contains("TLSv1.3"))
    }

    @Test
    fun itsToStringNamesOnlyTheFile() {
        assertEquals("SyncCertificate(sync-tls.p12)", cert().toString())
    }
}
