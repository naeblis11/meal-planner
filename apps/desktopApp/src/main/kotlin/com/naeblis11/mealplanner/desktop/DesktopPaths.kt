package com.naeblis11.mealplanner.desktop

import com.naeblis11.mealplanner.desktop.server.SecretsFile
import java.io.File
import javax.swing.filechooser.FileSystemView

/**
 * Where the desktop keeps things (P7-R10). The library (the recipe files and their photos) is paths.py's data_dir()
 * without its fallback to the folder's name before the rename: `Documents\Meal Planner`. The app's own data (its
 * database, log, lock and import staging) lives in the secrets folder, `%LOCALAPPDATA%\Meal Planner`, which Windows'
 * Controlled folder access doesn't guard, so a blocked Documents folder can never stop the app from starting or from
 * logging why. The program itself is in `%LOCALAPPDATA%\Meal-Planner`, which uninstall deletes: nothing is kept there.
 *
 * Development sets a Java property, which puts both in that one folder: the library in it, and the app data beside
 * the library in the same folder, as before the split. So the preview's existing folder, the tests' temp folders and
 * the smoke run's temp folder keep working unchanged, and none of them ever reaches the real Documents or
 * LOCALAPPDATA.
 */
object DesktopPaths {
    /** Set by the Gradle run task and the tests, so development never touches the real library or app data. */
    const val DATA_DIR_PROPERTY = "mealplanner.dataDir"
    const val DATA_DIR_ENV = "MEAL_PLANNER_DATA_DIR"
    const val APP_NAME = "Meal Planner"
    const val PREFS_NODE = "com/naeblis11/mealplanner"
    const val PREVIEW_PREFS_NODE = "com/naeblis11/mealplanner/preview"

    /** The library: the property, else the environment variable, else `Meal Planner` in Documents. */
    fun libraryDir(
        property: String? = System.getProperty(DATA_DIR_PROPERTY),
        env: String? = System.getenv(DATA_DIR_ENV),
        documents: () -> File = ::documentsDir,
    ): File {
        property?.takeIf { it.isNotBlank() }?.let { return File(it) }
        env?.takeIf { it.isNotBlank() }?.let { return File(it) }
        return File(documents(), APP_NAME)
    }

    /**
     * The app's own data: the property's folder (the same as the library's), else the secrets file's folder
     * (`MEAL_PLANNER_HOME`, else `%LOCALAPPDATA%\Meal Planner`). MEAL_PLANNER_DATA_DIR moves only the library. Tests
     * pass [env] and [userHome], so they never see the real LOCALAPPDATA.
     */
    fun appDataDir(
        property: String? = System.getProperty(DATA_DIR_PROPERTY),
        env: (String) -> String? = System::getenv,
        userHome: String = System.getProperty("user.home"),
    ): File {
        property?.takeIf { it.isNotBlank() }?.let { return File(it) }
        return SecretsFile.location(env, userHome).parentFile
    }

    /** The preview (started with the property) keeps its settings apart from the installed app's. */
    fun prefsNode(property: String? = System.getProperty(DATA_DIR_PROPERTY)): String =
        if (property.isNullOrBlank()) PREFS_NODE else PREVIEW_PREFS_NODE

    /**
     * The user's Documents folder as the Windows shell reports it, so a folder redirected into OneDrive
     * (or anywhere else) is honoured without native code. The fallback is `<home>\Documents`.
     */
    fun documentsDir(): File =
        FileSystemView.getFileSystemView().defaultDirectory ?: File(System.getProperty("user.home"), "Documents")
}
