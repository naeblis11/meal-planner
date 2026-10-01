package com.naeblis11.mealplanner.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The phone's database. Schema changes are Room migrations with the schema
 * exported to android/app/schemas; a destructive fallback is never used, so
 * an app update can't wipe a library.
 */
@Database(
    entities = [
        RecipeEntity::class, RecipeIngredientEntity::class, RecipeStepEntity::class,
        MealPlanEntity::class, PantryItemEntity::class, ShoppingItemEntity::class, IngredientAisleEntity::class,
        CalendarEventEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recipeDao(): RecipeDao

    abstract fun mealPlanDao(): MealPlanDao

    abstract fun pantryDao(): PantryDao

    abstract fun shoppingDao(): ShoppingDao

    abstract fun calendarEventDao(): CalendarEventDao

    companion object {
        const val FILE_NAME = "mealplanner.db"

        fun open(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, FILE_NAME)
                .addMigrations(*Migrations.ALL)
                .build()
    }
}
