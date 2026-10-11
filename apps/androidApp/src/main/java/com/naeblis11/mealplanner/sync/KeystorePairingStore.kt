package com.naeblis11.mealplanner.sync

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The phone's pairing (LAN sync stage 2c, D1), sealed with AES-256-GCM under [key] in one file, [file], in the app's
 * private files. The file holds a version byte, the IV's length, the IV, then the ciphertext with its tag. It is written
 * atomically: a temp file moved over it.
 *
 * [key] is the Android Keystore's (AndroidKeyStoreKeys.pairingKey) outside tests. It is asked for only when the file is
 * read or written, so a phone that never paired never touches the Keystore. A file that can't be opened (restored onto
 * another phone, a key Android invalidated, damage) loads as null: the phone is simply not paired, and keeps its data.
 * Only the failure's class is logged, never the pairing.
 */
class KeystorePairingStore(private val file: File, private val key: () -> SecretKey) : PairingStore {
    override suspend fun load(): PhonePairing? = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext null
        try {
            PhonePairingCodec.decode(open(file.readBytes()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            System.err.println("Meal Planner: the sync pairing couldn't be read (${e.javaClass.simpleName})")
            null
        }
    }

    override suspend fun save(pairing: PhonePairing) = withContext(Dispatchers.IO) {
        val sealed = seal(PhonePairingCodec.encode(pairing))
        val temp = File(file.parentFile, file.name + TEMP)
        temp.writeBytes(sealed)
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        Unit
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        File(file.parentFile, file.name + TEMP).delete()
        if (file.exists() && !file.delete()) throw IOException("The sync pairing couldn't be removed.")
        Unit
    }

    private fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // The cipher makes a fresh random IV (the Android Keystore insists on making it itself).
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        return byteArrayOf(VERSION, iv.size.toByte()) + iv + cipher.doFinal(plain)
    }

    private fun open(sealed: ByteArray): ByteArray {
        require(sealed.size > 2 && sealed[0] == VERSION) { "Not a sealed pairing." }
        val ivSize = sealed[1].toInt()
        require(ivSize in 12..16 && sealed.size > 2 + ivSize) { "Not a sealed pairing." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 2, ivSize))
        return cipher.doFinal(sealed, 2 + ivSize, sealed.size - 2 - ivSize)
    }

    companion object {
        /** In filesDir; kept out of backups and device transfers (data_extraction_rules.xml). */
        const val FILE_NAME = "sync-pairing.bin"
        private const val TEMP = ".tmp"
        private const val VERSION: Byte = 1
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
    }
}

/** The pairing's key in the Android Keystore (D1): AES-256 for GCM, made once, never exportable, needing no screen lock. */
object AndroidKeyStoreKeys {
    private const val PROVIDER = "AndroidKeyStore"
    const val PAIRING_ALIAS = "meal-planner-sync-pairing"

    @Synchronized
    fun pairingKey(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(PAIRING_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(PAIRING_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
