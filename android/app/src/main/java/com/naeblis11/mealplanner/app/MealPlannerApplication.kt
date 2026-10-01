package com.naeblis11.mealplanner.app

import android.app.Application
import android.content.Context
import com.naeblis11.mealplanner.calendar.AndroidCalendarGateway
import com.naeblis11.mealplanner.calendar.CalendarChoice
import com.naeblis11.mealplanner.calendar.CalendarGateway
import com.naeblis11.mealplanner.calendar.CalendarSync
import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.MealPlanRepository
import com.naeblis11.mealplanner.data.PantryRepository
import com.naeblis11.mealplanner.data.RecipeRepository
import com.naeblis11.mealplanner.data.ShoppingRepository
import java.io.File

/** The app's long-lived objects, created once per process. [gatewayOverride] lets full-app tests use a fake calendar. */
class AppContainer(context: Context, private val gatewayOverride: CalendarGateway? = null) {
    private val appContext = context.applicationContext

    val database: AppDatabase by lazy { AppDatabase.open(appContext) }

    /** Recipe photos (`<uuid>.jpg` and `<uuid>_thumb.jpg`), private to the app. */
    val imagesDir: File = File(appContext.filesDir, "images")

    val recipes: RecipeRepository by lazy { RecipeRepository(database, imagesDir) }

    val plans: MealPlanRepository by lazy { MealPlanRepository(database) }

    val shopping: ShoppingRepository by lazy { ShoppingRepository(database) }

    val pantry: PantryRepository by lazy { PantryRepository(database) }

    /** The phone's calendars (CalendarContract), or the tests' fake. */
    val calendarGateway: CalendarGateway by lazy { gatewayOverride ?: AndroidCalendarGateway(appContext) }

    val calendarChoice: CalendarChoice by lazy {
        CalendarChoice(appContext.getSharedPreferences(CalendarChoice.FILE, Context.MODE_PRIVATE))
    }

    val calendarSync: CalendarSync by lazy { CalendarSync(database, calendarGateway, calendarChoice) }

    /** Where the camera app writes a photo before it is processed. */
    val cameraDir: File
        get() = File(appContext.cacheDir, "camera").apply { mkdirs() }

    /** A new, empty folder for one import's staged photos; the import deletes it when it ends. */
    fun newImportStagingDir(): File = File(appContext.cacheDir, "import/${System.nanoTime()}").apply { mkdirs() }

    /** Deletes what a killed process left behind; started once, off the main thread. Tests join it. */
    internal val startupCleanup: Thread = discardLeftovers()

    // An import killed with the process leaves its staging folder behind; none can be
    // live yet, so all of cacheDir/import goes. It is moved aside at once (cheap, and a
    // new import starts clean) and deleted off the main thread. cacheDir/camera is kept.
    // An import or photo save killed mid-write leaves "<name>.tmp" or "<name>.<n>.bak"
    // next to the photos; the photos themselves are always intact. Only files older
    // than this start are touched, so a save that has just begun is never disturbed.
    private fun discardLeftovers(): Thread {
        val started = System.currentTimeMillis()
        val cache = appContext.cacheDir
        val current = File(cache, "import")
        if (current.exists() && !current.renameTo(File(cache, "$TRASH_PREFIX${System.nanoTime()}"))) current.deleteRecursively()
        val photos = imagesDir
        return Thread({
            cache.listFiles { file -> file.name.startsWith(TRASH_PREFIX) }.orEmpty().forEach { it.deleteRecursively() }
            photos.listFiles { file ->
                file.isFile && (file.name.endsWith(".tmp") || file.name.endsWith(".bak")) && file.lastModified() < started
            }.orEmpty().forEach { it.delete() }
        }, "startup-cleanup").apply { start() }
    }

    private companion object {
        const val TRASH_PREFIX = "import-discarded-"
    }
}

class MealPlannerApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

fun Context.appContainer(): AppContainer = (applicationContext as MealPlannerApplication).container
