package com.naeblis11.mealplanner.sync

import java.io.File
import java.nio.file.Files
import java.security.KeyStoreException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** LAN sync stage 2c (D1): the pairing sealed in one file. Robolectric has no AndroidKeyStore, so a software AES key stands in. */
class KeystorePairingStoreTest {
    private val dir: File = Files.createTempDirectory("mp-pairing").toFile()
    private val file = File(dir, KeystorePairingStore.FILE_NAME)
    private val key: SecretKey = newKey()
    private val pairing = PhonePairing(
        listOf("172.16.4.20"), 5001, "ab".repeat(32), "KITCHEN", "12".repeat(32), "f".repeat(32), "0123456789abcdef0123456789abcdef",
    )

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun aSavedPairingComesBackAndIsNeverPlainText() = runBlocking<Unit> {
        val store = KeystorePairingStore(file) { key }
        assertNull(store.load())
        store.save(pairing)
        assertEquals(pairing, store.load())
        val stored = file.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(stored.contains(pairing.deviceKey))
        assertFalse(stored.contains("KITCHEN"))
        // Atomic: no temp file is left beside it.
        assertEquals(listOf(KeystorePairingStore.FILE_NAME), dir.list()!!.toList())
    }

    @Test
    fun eachSaveHasItsOwnIv() = runBlocking<Unit> {
        val store = KeystorePairingStore(file) { key }
        store.save(pairing)
        val first = file.readBytes()
        store.save(pairing)
        assertFalse(first.contentEquals(file.readBytes()))
        assertEquals(pairing, store.load())
    }

    @Test
    fun aFileThisKeyCantOpenIsNotAPairingAndIsKept() = runBlocking<Unit> {
        KeystorePairingStore(file) { key }.save(pairing)
        assertNull(KeystorePairingStore(file) { newKey() }.load())
        assertTrue(file.isFile)
    }

    @Test
    fun aDamagedFileIsNotAPairing() = runBlocking<Unit> {
        file.writeBytes(byteArrayOf(1, 12, 3, 4))
        assertNull(KeystorePairingStore(file) { key }.load())
        KeystorePairingStore(file) { key }.save(pairing)
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()
        file.writeBytes(bytes)
        assertNull(KeystorePairingStore(file) { key }.load())
    }

    @Test
    fun clearForgetsIt() = runBlocking<Unit> {
        val store = KeystorePairingStore(file) { key }
        store.save(pairing)
        store.clear()
        assertNull(store.load())
        assertFalse(file.exists())
        // Clearing nothing is fine.
        store.clear()
    }

    @Test
    fun withNoFileTheKeystoreIsNeverAsked() = runBlocking<Unit> {
        val store = KeystorePairingStore(file) { throw KeyStoreException("no keystore here") }
        assertNull(store.load())
        assertThrows(KeyStoreException::class.java) { runBlocking { store.save(pairing) } }
        assertFalse(file.exists())
    }
}
