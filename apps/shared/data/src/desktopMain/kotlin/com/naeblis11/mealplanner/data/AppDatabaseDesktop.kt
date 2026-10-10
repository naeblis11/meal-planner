package com.naeblis11.mealplanner.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlinx.coroutines.Dispatchers

/** The desktop's database file, on the SQLite bundled with the app (Windows has none of its own to rely on). */
fun AppDatabase.Companion.openAt(file: File): AppDatabase {
    file.parentFile?.mkdirs()
    return Room.databaseBuilder<AppDatabase>(name = file.absolutePath)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .addMigrations(*Migrations.ALL)
        .build()
}
