package com.naeblis11.mealplanner.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** A small app-wide value that isn't a user setting, such as the household id (Household.KEY). */
@Entity(tableName = "app_meta")
data class AppMetaEntity(@PrimaryKey val key: String, val value: String)

@Dao
interface AppMetaDao {
    @Query("SELECT `value` FROM app_meta WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: AppMetaEntity)
}
