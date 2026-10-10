package com.naeblis11.mealplanner.desktop.server

import com.naeblis11.mealplanner.desktop.DesktopPaths
import com.naeblis11.mealplanner.domain.Py
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The secrets file the Python server keeps (paths.env_path: `MEAL_PLANNER_HOME\.env`, else
 * `%LOCALAPPDATA%\Meal Planner\.env`), in set_password.read_env's KEY=VALUE format. The desktop reads and writes only
 * MEAL_PLANNER_API_TOKEN (P4-R6); the password hash, session key and Home Assistant lines are left as they are.
 */
class SecretsFile(val file: File) {
    /**
     * set_password.read_env: blank lines, comments and lines without "=" are skipped, keys and values trimmed, and the
     * last of a repeated key wins. A missing file reads as empty.
     */
    fun read(): Map<String, String> {
        if (!file.isFile) return emptyMap()
        val values = linkedMapOf<String, String>()
        for (raw in Py.splitLines(text())) {
            val line = Py.strip(raw)
            if (line.isEmpty() || line.startsWith("#") || '=' !in line) continue
            values[Py.strip(line.substringBefore('='))] = Py.strip(line.substringAfter('='))
        }
        return values
    }

    /**
     * Sets [key] to [value] and keeps every other line, comments included: the key's old lines go and one KEY=VALUE
     * line is added at the end. Written to a temporary file in the same folder and moved over the old one, so a crash
     * leaves the old file or the new one, never half of one. Makes the folder when needed; throws IOException when it
     * can't write.
     */
    fun put(key: String, value: String) {
        val folder = file.absoluteFile.parentFile
        folder.mkdirs()
        val kept = if (file.isFile) Py.splitLines(text()).filterNot { keyOf(it) == key } else emptyList()
        val temp = File(folder, "${file.name}.tmp")
        try {
            temp.writeText((kept + "$key=$value").joinToString("\n", postfix = "\n"), Charsets.UTF_8)
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temp.delete()
        }
    }

    // A file saved by Notepad may start with a byte-order mark.
    private fun text(): String = file.readText(Charsets.UTF_8).removePrefix("\uFEFF")

    private fun keyOf(line: String): String? {
        val trimmed = Py.strip(line)
        if (trimmed.isEmpty() || trimmed.startsWith("#") || '=' !in trimmed) return null
        return Py.strip(trimmed.substringBefore('='))
    }

    companion object {
        const val FILE_NAME = ".env"
        const val HOME_ENV = "MEAL_PLANNER_HOME"
        const val APP_NAME = "Meal Planner"

        /**
         * paths.env_path() on Windows, without its fallback to the folder's name before the rename. Tests pass [env] and
         * [userHome], so they never see the real LOCALAPPDATA.
         */
        fun location(env: (String) -> String? = System::getenv, userHome: String = System.getProperty("user.home")): File {
            env(HOME_ENV)?.takeIf { it.isNotEmpty() }?.let { return File(expandUser(it, userHome), FILE_NAME) }
            val base = env("LOCALAPPDATA")?.takeIf { it.isNotEmpty() }?.let(::File) ?: File(File(userHome, "AppData"), "Local")
            return File(File(base, APP_NAME), FILE_NAME)
        }

        /**
         * The installed app's secrets file ([installed], the Python server's); the preview and the tests, started with
         * mealplanner.dataDir, keep theirs in [dataDir], so a token made there never replaces the household's.
         */
        fun forApp(
            dataDir: File,
            property: String? = System.getProperty(DesktopPaths.DATA_DIR_PROPERTY),
            installed: () -> File = { location() },
        ): File = if (property.isNullOrBlank()) installed() else File(dataDir, FILE_NAME)

        // Path.expanduser for "~" and "~/..." (or "~\...").
        private fun expandUser(path: String, userHome: String): File = when {
            path == "~" -> File(userHome)
            path.startsWith("~/") || path.startsWith("~\\") -> File(userHome, path.substring(2))
            else -> File(path)
        }
    }
}
