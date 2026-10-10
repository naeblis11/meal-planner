package com.naeblis11.mealplanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.naeblis11.mealplanner.app.MealPlannerApplication
import com.naeblis11.mealplanner.app.appContainer
import com.naeblis11.mealplanner.app.appUpdates
import com.naeblis11.mealplanner.ui.MealPlannerApp
import com.naeblis11.mealplanner.ui.theme.MealPlannerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = appContainer()
        // Plan 8: ready at once; the update check itself is made on a background thread (P8-F1, DeferredUpdates).
        val updates = appUpdates()
        // The launch check, once per process (a rotation isn't a launch; a process Android ended and restored with this
        // activity's saved state is), on Updates' own IO scope, never this thread. At most once a day.
        (application as MealPlannerApplication).startLaunchCheckOnce()
        setContent {
            MealPlannerTheme {
                MealPlannerApp(container, updates = updates)
            }
        }
    }
}
