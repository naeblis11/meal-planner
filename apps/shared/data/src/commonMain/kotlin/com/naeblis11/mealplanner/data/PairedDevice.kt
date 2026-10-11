package com.naeblis11.mealplanner.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A phone or PC paired with this master PC (LAN sync spec, section 2). Only the SHA-256 of its device key is kept
 * ([keyHash]); the key itself was handed over once, at pairing. [kind] is "phone" or "pc". The desktop's table: the
 * phone has it and leaves it empty, as google_event. Not synced.
 */
@Entity(tableName = "paired_device", indices = [Index(value = ["key_hash"], unique = true)])
data class PairedDeviceEntity(
    @PrimaryKey @ColumnInfo(name = "device_id") val deviceId: String,
    val name: String,
    val kind: String,
    @ColumnInfo(name = "key_hash") val keyHash: String,
    @ColumnInfo(name = "paired_at") val pairedAt: Long,
    @ColumnInfo(name = "last_sync_at") val lastSyncAt: Long? = null,
) {
    override fun toString(): String =
        "PairedDeviceEntity(deviceId=$deviceId, name=$name, kind=$kind, pairedAt=$pairedAt, lastSyncAt=$lastSyncAt)"
}

@Dao
interface PairedDeviceDao {
    /** Pairs [device]; a device id already paired is replaced (a new key). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(device: PairedDeviceEntity)

    @Query("SELECT * FROM paired_device WHERE key_hash = :keyHash")
    suspend fun byKeyHash(keyHash: String): PairedDeviceEntity?

    @Query("SELECT * FROM paired_device WHERE device_id = :deviceId")
    suspend fun byId(deviceId: String): PairedDeviceEntity?

    @Query("DELETE FROM paired_device WHERE device_id = :deviceId")
    suspend fun remove(deviceId: String)

    @Query("UPDATE paired_device SET last_sync_at = :at WHERE device_id = :deviceId")
    suspend fun touch(deviceId: String, at: Long)

    @Query("SELECT COUNT(*) FROM paired_device")
    suspend fun count(): Int

    @Query("SELECT * FROM paired_device ORDER BY paired_at, device_id")
    suspend fun all(): List<PairedDeviceEntity>

    @Query("SELECT * FROM paired_device ORDER BY paired_at, device_id")
    fun observeAll(): Flow<List<PairedDeviceEntity>>
}
