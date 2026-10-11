package com.naeblis11.mealplanner.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.naeblis11.mealplanner.settings.SyncAttention

/** The desktop's held sync removals (stage 2b), for the Recipes banner; null on Android. MealPlannerApp provides it. */
val LocalSyncAttention = staticCompositionLocalOf<SyncAttention?> { null }
