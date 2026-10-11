package com.naeblis11.mealplanner.desktop.sync

import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.openAt
import com.naeblis11.mealplanner.sync.PairRequest
import com.naeblis11.mealplanner.update.Sha256
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** LAN sync stage 2b (D5): a device key is handed over once; only its SHA-256 is kept, and it is checked by that. */
class PairedDevicesTest {
    private val dir: File = Files.createTempDirectory("mp-paired").toFile()
    private val db = AppDatabase.openAt(File(dir, "pc.db"))
    private var now = 5_000L
    private val devices = PairedDevices(db.pairedDeviceDao(), clock = { now })

    @After
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    private fun request(name: String = "Pixel") = PairRequest(ID, name, "phone", "0f".repeat(16), "ab".repeat(32))

    @Test
    fun aRegisteredDeviceGetsA64HexKeyAndOnlyItsHashIsKept() = runBlocking<Unit> {
        val key = devices.register(request())
        assertTrue(Regex("[0-9a-f]{64}").matches(key))
        val row = db.pairedDeviceDao().byId(ID)!!
        assertEquals(Sha256.of(key.toByteArray(Charsets.US_ASCII)), row.keyHash)
        assertNotEquals(key, row.keyHash)
        assertEquals("Pixel", row.name)
        assertEquals("phone", row.kind)
        assertEquals(5_000L, row.pairedAt)
        assertNull(row.lastSyncAt)
    }

    @Test
    fun theKeyAuthenticatesItsDevice() = runBlocking<Unit> {
        val key = devices.register(request())
        assertEquals(ID, devices.authenticate("Bearer $key")!!.deviceId)
    }

    @Test
    fun anythingElseIsNotAuthenticated() = runBlocking<Unit> {
        val key = devices.register(request())
        val refused = listOf(
            null, "", "Bearer", "Bearer ", "Basic $key", "bearer $key", "Bearer ${key.uppercase()}",
            "Bearer ${key.dropLast(1)}", "Bearer ${key}0", "Bearer " + "0".repeat(64),
        )
        for (header in refused) assertNull(header, devices.authenticate(header))
    }

    @Test
    fun pairingAgainReplacesTheKey() = runBlocking<Unit> {
        val first = devices.register(request())
        val second = devices.register(request(name = "Pixel 9"))
        assertNull(devices.authenticate("Bearer $first"))
        assertEquals("Pixel 9", devices.authenticate("Bearer $second")!!.name)
        assertEquals(1, db.pairedDeviceDao().count())
    }

    @Test
    fun aRemovedDeviceIsNoLongerAuthenticated() = runBlocking<Unit> {
        val key = devices.register(request())
        devices.remove(ID)
        assertNull(devices.authenticate("Bearer $key"))
    }

    @Test
    fun touchDatesTheLastSync() = runBlocking<Unit> {
        devices.register(request())
        now = 9_000L
        devices.touch(ID)
        assertEquals(9_000L, db.pairedDeviceDao().byId(ID)!!.lastSyncAt)
    }

    @Test
    fun anyAndTheListFollowTheTable() = runBlocking<Unit> {
        assertFalse(devices.any())
        devices.register(request())
        assertTrue(devices.any())
        assertEquals(listOf("Pixel"), devices.devices.first().map { it.name })
    }

    private companion object {
        const val ID = "0123456789abcdef0123456789abcdef"
    }
}
