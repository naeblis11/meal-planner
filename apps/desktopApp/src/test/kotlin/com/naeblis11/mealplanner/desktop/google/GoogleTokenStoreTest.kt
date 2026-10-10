package com.naeblis11.mealplanner.desktop.google

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** P5-R4: the refresh token is kept sealed, never in plain text, and never said. */
class GoogleTokenStoreTest {
    private val dir: File = Files.createTempDirectory("mp-google-token").toFile()
    private val store = GoogleTokenStore(File(dir, GoogleTokenStore.FILE_NAME), FakeProtector())
    private val said = mutableListOf<String>()
    private val logging = GoogleTokenStore(File(dir, GoogleTokenStore.FILE_NAME), FakeProtector()) { said += it }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun aSavedTokenComesBackAndIsNeverPlainOnDisk() {
        store.save("refresh-secret-1")
        assertTrue(store.exists())
        assertEquals("refresh-secret-1", store.load())
        assertFalse(String(store.file.readBytes(), Charsets.ISO_8859_1).contains("refresh-secret-1"))
        assertEquals(listOf(GoogleTokenStore.FILE_NAME), dir.list()!!.toList())
    }

    @Test
    fun noFileIsNoToken() {
        assertFalse(store.exists())
        assertNull(store.load())
    }

    @Test
    fun aFileThatCantBeUnsealedSaysSoWithoutItsContents() {
        // Sealed by another Windows user, or copied from another PC.
        store.file.writeText("refresh-secret-1")
        val failure = assertThrows(IOException::class.java) { store.load() }
        assertFalse(failure.message!!.contains("refresh-secret-1"))
    }

    @Test
    fun savingOverATokenReplacesItAndLeavesNoTemporaryFile() {
        store.save("refresh-secret-1")
        store.save("refresh-secret-2")
        assertEquals("refresh-secret-2", store.load())
        assertEquals(listOf(GoogleTokenStore.FILE_NAME), dir.list()!!.toList())
    }

    @Test
    fun aCorruptOrEmptyFileIsAFixedMessageAndTheLogSaysOnlyWhatKindOfFailure() {
        for (bytes in listOf("fake-dpapi".toByteArray(Charsets.US_ASCII), ByteArray(0), "refresh-secret-1".toByteArray(Charsets.US_ASCII))) {
            said.clear()
            store.file.writeBytes(bytes)
            val failure = assertThrows(IOException::class.java) { logging.load() }
            assertEquals(GoogleTokenStore.CANT_UNSEAL, failure.message)
            assertEquals(1, said.size)
            assertTrue(said.single(), said.single().contains(IllegalArgumentException::class.java.name))
            // FakeProtector's own message ("not sealed here") and the file's contents stay out of the log.
            assertFalse(said.single(), said.single().contains("not sealed here"))
            assertFalse(said.single(), said.single().contains("refresh-secret-1"))
        }
    }

    @Test
    fun aProtectorThatFailsToSealIsAFixedMessageAndNothingIsWritten() {
        val failing = object : SecretProtector {
            override fun protect(plain: ByteArray): ByteArray = throw IllegalStateException(String(plain, Charsets.UTF_8))

            override fun unprotect(sealed: ByteArray): ByteArray = sealed
        }
        val store = GoogleTokenStore(File(dir, GoogleTokenStore.FILE_NAME), failing) { said += it }
        val failure = assertThrows(IOException::class.java) { store.save("refresh-secret-1") }
        assertEquals(GoogleTokenStore.CANT_SEAL, failure.message)
        assertTrue(said.single(), said.single().contains(IllegalStateException::class.java.name))
        assertFalse(said.single(), said.single().contains("refresh-secret-1"))
        assertFalse(failure.toString().contains("refresh-secret-1"))
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun deleteForgetsItAndToStringNeverShowsIt() {
        store.save("refresh-secret-1")
        assertFalse(store.toString().contains("refresh-secret-1"))
        assertTrue(store.delete())
        assertNull(store.load())
        assertTrue(store.delete())
    }
}
