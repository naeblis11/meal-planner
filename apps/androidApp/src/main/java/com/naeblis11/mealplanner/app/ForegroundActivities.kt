package com.naeblis11.mealplanner.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

/**
 * How many of the app's activities are resumed, counted from Android's own callbacks (MealPlannerApplication registers
 * one). Read from the update check's IO thread, so the count is atomic. [onResumed] runs on the main thread each time
 * one of them resumes (the update check's permission refresh).
 */
class ForegroundActivities(private val onResumed: () -> Unit = {}) : Application.ActivityLifecycleCallbacks {
    private val resumed = AtomicInteger()

    /** Whether one of the app's activities is in the foreground now. */
    val any: Boolean
        get() = resumed.get() > 0

    override fun onActivityResumed(activity: Activity) {
        resumed.incrementAndGet()
        onResumed()
    }

    override fun onActivityPaused(activity: Activity) {
        resumed.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
