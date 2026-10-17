package com.naeblis11.mealplanner.desktop.sync

import com.naeblis11.mealplanner.desktop.google.FakeProtector
import com.naeblis11.mealplanner.desktop.google.SecretProtector
import com.naeblis11.mealplanner.sync.PhonePairing
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 4a: a client PC's pairing, sealed with (fake) DPAPI in one file; anything it can't open means "not a client". */
class DpapiPairingStoreTest {
    private val dir: File = Files.createTempDirectory("mp-client-pairing").toFile()
    private val file = File(dir, DpapiPairingStore.FILE_NAME)
    private val store = DpapiPairingStore(file, FakeProtector())
    private val pairing = PhonePairing(
        addresses = listOf("192.0.2.20", "192.0.2.21"), port = 5001, fingerprint = "ab".repeat(32), pcName = "KITCHEN",
        deviceKey = "12".repeat(32), hubId = "c".repeat(32), householdId = "d".repeat(32),
    )

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun aSavedPairingLoadsBackAndIsSealedOnDisk() = runBlocking<Unit> {
        store.save(pairing)
        assertEquals(pairing, store.load())
        val onDisk = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse("the key is sealed", onDisk.contains(pairing.deviceKey))
        assertFalse("the master's name is sealed", onDisk.contains("KITCHEN"))
        assertEquals(pairing, DpapiPairingStore(file, FakeProtector()).load())
    }

    @Test
    fun noFileIsNotPaired() = runBlocking<Unit> {
        assertNull(store.load())
        store.clear()
        assertFalse(file.exists())
    }

    @Test
    fun aDamagedFileIsNotPairedAndIsLeftWhereItIs() = runBlocking<Unit> {
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertNull(store.load())
        assertTrue(file.exists())
    }

    @Test
    fun aFileSealedByAnotherWindowsUserIsNotPaired() = runBlocking<Unit> {
        store.save(pairing)
        val anotherUser = object : SecretProtector {
            override fun protect(plain: ByteArray) = plain
            override fun unprotect(sealed: ByteArray): ByteArray = throw IllegalStateException("not this user's")
        }
        assertNull(DpapiPairingStore(file, anotherUser).load())
    }

    @Test
    fun aNewPairingReplacesTheOldAndClearRemovesIt() = runBlocking<Unit> {
        store.save(pairing)
        val moved = pairing.copy(addresses = listOf("192.0.2.30"))
        store.save(moved)
        assertEquals(moved, store.load())
        assertEquals(listOf(DpapiPairingStore.FILE_NAME), dir.list()!!.toList())
        store.clear()
        assertNull(store.load())
        assertFalse(file.exists())
    }
}
