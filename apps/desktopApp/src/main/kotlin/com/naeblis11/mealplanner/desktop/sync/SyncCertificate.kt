package com.naeblis11.mealplanner.desktop.sync

import com.naeblis11.mealplanner.desktop.google.SecretProtector
import com.naeblis11.mealplanner.sync.PairingProof
import com.naeblis11.mealplanner.update.Sha256
import io.ktor.network.tls.certificates.KeyType
import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.network.tls.extensions.HashAlgorithm
import io.ktor.network.tls.extensions.SignatureAlgorithm
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The sync port's certificate (LAN sync spec, section 2): self-signed, made the first time it is needed, kept in [dir]
 * (the app data folder: never Documents, never the install folder) as [STORE_FILE] with its random password sealed by
 * [protector] (DPAPI) in [PASSWORD_FILE], each written to a temporary file and moved over. Devices pin its
 * [fingerprint], and a new one makes every device pair again, so it is remade only when the old one is surely broken:
 * the password won't unseal (another Windows user), the keystore won't load with it (a damaged file), or one half is
 * missing. The old pair is then kept as `.bad` (replacing an older `.bad`) and [notice] says the devices must pair
 * again. Any other failure to read a file (a transient IOException) is thrown, and nothing is changed: the sync port
 * fails to start and the next try reads the same certificate. [log] gets only exception class names. Blocking (an RSA
 * key is made on first use): never on the UI thread. [read] reads a file (a seam for tests).
 */
class SyncCertificate(
    private val dir: File,
    private val protector: SecretProtector,
    private val log: (String) -> Unit = { System.err.println(it) },
    private val random: SecureRandom = SecureRandom(),
    private val read: (File) -> ByteArray = { it.readBytes() },
) {
    private class Loaded(val certificate: X509Certificate, val fingerprint: String, val context: SSLContext)

    private var loaded: Loaded? = null
    private val _notice = MutableStateFlow<String?>(null)

    /** [REMADE_NOTICE] once this run had to replace an unreadable certificate; null otherwise. */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    val storeFile: File get() = File(dir, STORE_FILE)
    val passwordFile: File get() = File(dir, PASSWORD_FILE)

    /** The server side's TLS context, with this certificate's key. */
    @Synchronized
    fun sslContext(): SSLContext = load().context

    /** The lower-case hex SHA-256 of the certificate's DER encoding: what a device pins. */
    val fingerprint: String
        @Synchronized get() = load().fingerprint

    val certificate: X509Certificate
        @Synchronized get() = load().certificate

    // Under the lock. Throws (and changes nothing) when a file can't be read just now.
    private fun load(): Loaded {
        loaded?.let { return it }
        val hasStore = storeFile.exists()
        val hasPassword = passwordFile.exists()
        val made = when {
            !hasStore && !hasPassword -> make()
            !hasStore || !hasPassword -> remake("half of it is missing")
            else -> {
                // Read failures (the files' bytes) pass through as they are; only what the bytes say can call for a remake.
                val sealed = read(passwordFile)
                val stored = read(storeFile)
                try {
                    open(sealed, stored)
                } catch (e: Exception) {
                    remake(kind(e))
                }
            }
        }
        loaded = made
        return made
    }

    // The password unsealed and the keystore loaded with it. Any failure here is the files' content: a remake.
    private fun open(sealed: ByteArray, stored: ByteArray): Loaded {
        val password = String(protector.unprotect(sealed), Charsets.UTF_8).toCharArray()
        val store = KeyStore.getInstance(STORE_TYPE)
        store.load(stored.inputStream(), password)
        return loaded(store, password)
    }

    // The broken pair is set aside as .bad first (never overwritten), then a new one is made and the devices are told.
    private fun remake(why: String): Loaded {
        log("Meal Planner: the sync certificate can't be read ($why); keeping it as .bad and making a new one, so paired devices must pair again.")
        for (file in listOf(storeFile, passwordFile)) {
            if (file.exists()) {
                Files.move(file.toPath(), File(file.path + BAD_SUFFIX).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
        }
        return make().also { _notice.value = REMADE_NOTICE }
    }

    private fun make(): Loaded {
        val password = Sha256.hex(ByteArray(32).also(random::nextBytes))
        val built = buildKeyStore {
            certificate(ALIAS) {
                this.password = password
                hash = HashAlgorithm.SHA256
                sign = SignatureAlgorithm.RSA
                keySizeInBits = KEY_BITS
                keyType = KeyType.Server
                subject = X500Principal(SUBJECT)
                daysValid = VALID_DAYS
            }
        }
        val bytes = ByteArrayOutputStream().also { built.store(it, password.toCharArray()) }.toByteArray()
        // What is used now is exactly what the next start reads.
        val store = KeyStore.getInstance(STORE_TYPE).apply { load(bytes.inputStream(), password.toCharArray()) }
        val sealed = protector.protect(password.toByteArray(Charsets.UTF_8))
        writeAtomically(passwordFile, sealed)
        writeAtomically(storeFile, bytes)
        return loaded(store, password.toCharArray())
    }

    private fun loaded(store: KeyStore, password: CharArray): Loaded {
        val certificate = store.getCertificate(ALIAS) as? X509Certificate ?: throw IllegalStateException("The keystore has no certificate.")
        if (store.getKey(ALIAS, password) !is PrivateKey) throw IllegalStateException("The keystore has no key.")
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        val context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, random) }
        return Loaded(certificate, PairingProof.fingerprint(certificate.encoded), context)
    }

    // GoogleTokenStore's pattern: a temporary file in the same folder, moved over the old one.
    private fun writeAtomically(file: File, bytes: ByteArray) {
        val folder = file.absoluteFile.parentFile
        folder.mkdirs()
        val temp = File(folder, "${file.name}.tmp")
        try {
            temp.writeBytes(bytes)
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temp.delete()
        }
    }

    override fun toString(): String = "SyncCertificate($STORE_FILE)"

    companion object {
        const val STORE_FILE = "sync-tls.p12"
        const val PASSWORD_FILE = "sync-tls.key"
        const val ALIAS = "meal-planner-sync"
        const val SUBJECT = "CN=Meal Planner"
        const val KEY_BITS = 2048

        /** 20 years. */
        const val VALID_DAYS = 20L * 365 + 5

        /** Settings' notice after a remade certificate (D2). */
        const val REMADE_NOTICE = "This PC's sync certificate was remade; pair your devices again."

        /** Added to a broken pair's names when it is set aside before a remake. */
        const val BAD_SUFFIX = ".bad"

        private const val STORE_TYPE = "PKCS12"

        // The chain of exception classes; never a message.
        private fun kind(e: Throwable): String = generateSequence(e) { it.cause }.take(4).joinToString(" <- ") { it.javaClass.name }
    }
}
