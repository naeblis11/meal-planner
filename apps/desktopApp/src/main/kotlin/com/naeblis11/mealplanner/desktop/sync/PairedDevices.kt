package com.naeblis11.mealplanner.desktop.sync

import com.naeblis11.mealplanner.data.PairedDeviceDao
import com.naeblis11.mealplanner.data.PairedDeviceEntity
import com.naeblis11.mealplanner.sync.PairRequest
import com.naeblis11.mealplanner.update.Sha256
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Random
import kotlinx.coroutines.flow.Flow

/**
 * The master PC's paired phones and PCs (LAN sync spec, section 2). A device key is 32 random bytes as hex, handed over
 * once by [register]; only its SHA-256 is stored. [authenticate] hashes the key a request presents and looks the hash up
 * (the keys are 256 random bits, so the lookup's timing gives nothing away), then compares it whole with
 * MessageDigest.isEqual. Nothing here logs a key.
 */
class PairedDevices(
    private val dao: PairedDeviceDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = SecureRandom(),
) {
    val devices: Flow<List<PairedDeviceEntity>> = dao.observeAll()

    /** Pairs the device in [request] (a device id already paired is replaced) and returns its new key. */
    suspend fun register(request: PairRequest): String {
        val key = Sha256.hex(ByteArray(32).also(random::nextBytes))
        dao.put(PairedDeviceEntity(request.deviceId, request.name, request.kind, hashOf(key), clock()))
        return key
    }

    /** The device whose key is in the `Authorization: Bearer <key>` header [bearer], or null. */
    suspend fun authenticate(bearer: String?): PairedDeviceEntity? {
        val key = bearer?.takeIf { it.startsWith(BEARER) }?.substring(BEARER.length)?.takeIf { KEY.matches(it) } ?: return null
        val hash = hashOf(key)
        val found = dao.byKeyHash(hash) ?: return null
        return found.takeIf { MessageDigest.isEqual(it.keyHash.toByteArray(Charsets.US_ASCII), hash.toByteArray(Charsets.US_ASCII)) }
    }

    suspend fun remove(deviceId: String) = dao.remove(deviceId)

    /** Dates the device's last sync now. */
    suspend fun touch(deviceId: String) = dao.touch(deviceId, clock())

    suspend fun any(): Boolean = dao.count() > 0

    companion object {
        private const val BEARER = "Bearer "
        private val KEY = Regex("[0-9a-f]{64}")

        /** What is stored for [key]: the lower-case hex SHA-256 of its ASCII. */
        fun hashOf(key: String): String = Sha256.of(key.toByteArray(Charsets.US_ASCII))
    }
}
