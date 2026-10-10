package com.naeblis11.mealplanner.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver

/** The phone's database file, on the platform's own SQLite through Room's driver API (so the common migrations run). */
fun AppDatabase.Companion.open(context: Context): AppDatabase =
    Room.databaseBuilder<AppDatabase>(context.applicationContext, FILE_NAME)
        .setDriver(AndroidSQLiteDriver())
        .addMigrations(*Migrations.ALL)
        .build()
