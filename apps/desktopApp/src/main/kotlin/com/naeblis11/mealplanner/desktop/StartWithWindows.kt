package com.naeblis11.mealplanner.desktop

import com.naeblis11.mealplanner.app.SettingsStore
import com.naeblis11.mealplanner.settings.StartupSwitch
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Runs reg.exe with [args] and returns its exit code (0 is success; for `query`, 1 means "not there"). Tests fake it. */
fun interface RegistryRunner {
    fun run(args: List<String>): Int
}

/**
 * The real reg.exe, in System32 as the known-folder API names it ([folders], FOLDERID_System; P8-PF2: never the
 * environment, which whatever started the app can set, and never PATH). When Windows doesn't say where System32 is, or
 * reg.exe isn't there, nothing runs: every call answers NOT_RUN, which reads as "not there" and "didn't happen", and it
 * is said once in the log. No JNA for the registry itself: one short process per change, through [start] (tests pass
 * a fake and never start a process).
 */
class RegExe(
    private val folders: WindowsFolders = KnownWindowsFolders,
    private val timeoutMillis: Long = 10_000,
    private val log: (String) -> Unit = { System.err.println(it) },
    private val start: (List<String>) -> Process = { ProcessBuilder(it).redirectErrorStream(true).start() },
) : RegistryRunner {
    private val saidMissing = AtomicBoolean(false)

    override fun run(args: List<String>): Int {
        val exe = exe(folders)
        if (exe == null) {
            if (!saidMissing.getAndSet(true)) log("Meal Planner: Windows didn't say where $EXE_NAME is, so Start with Windows is left as it is.")
            return NOT_RUN
        }
        val process = start(listOf(exe.path) + args)
        if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            log("Meal Planner: $EXE_NAME ${args.firstOrNull()} didn't finish in $timeoutMillis ms")
            return NOT_RUN
        }
        // Its few lines fit the pipe, so it never waited on us; they are read once it has ended.
        val output = process.inputStream.readBytes().decodeToString().trim()
        val code = process.exitValue()
        if (code != 0 && args.firstOrNull() != "query") log("Meal Planner: $EXE_NAME ${args.firstOrNull()} failed ($code): $output")
        return code
    }

    companion object {
        const val EXE_NAME = "reg.exe"

        /** The answer when reg.exe never ran: not 0 (done) and not 1 (query: not there). */
        const val NOT_RUN = -1

        /** `<FOLDERID_System>\reg.exe`, or null when Windows names no absolute System32 or reg.exe isn't in it. */
        fun exe(folders: WindowsFolders): File? =
            folders.system()?.takeIf { it.isAbsolute }?.let { File(it, EXE_NAME) }?.takeIf { it.isFile }
    }
}

/**
 * Start with Windows (P3-R3): the per-user Run key value "Meal Planner", whose command line is the installed
 * Meal Planner.exe ([launcherPath]) with MINIMIZED_ARG. It is on by default, but only the installed app writes it (the
 * packaged launcher sets `mealplanner.installed=true`, plan 7), and never a run with [GATE_PROPERTY] off (the smoke
 * run, P7-R6); development and the preview never do. The user's choice is kept in [settings], so off stays off.
 */
class StartWithWindows(
    private val registry: RegistryRunner,
    private val settings: SettingsStore,
    private val installed: Boolean = allowed(System.getProperty(INSTALLED_PROPERTY), System.getProperty(GATE_PROPERTY)),
    private val launcher: () -> String? = { launcherPath() },
) : StartupSwitch {
    override val available: Boolean get() = installed && launcher() != null

    override fun isOn(): Boolean = available && attempt(false) { registry.run(queryArgs()) == 0 }

    override fun setOn(on: Boolean): Boolean {
        val path = launcher()
        if (!installed || path == null) return false
        val done = attempt(false) {
            if (on) {
                registry.run(addArgs(path)) == 0
            } else {
                // Already gone counts as done.
                registry.run(deleteArgs()) == 0 || registry.run(queryArgs()) != 0
            }
        }
        if (done) attempt(Unit) { settings.put(mapOf(PREF_KEY to if (on) ON else OFF)) }
        return done
    }

    /**
     * At every start of the installed app: on, unless the user turned it off, and pointing at this launcher (an
     * upgrade may have moved it). Never in development. Blocking: call it off the UI thread.
     */
    fun applyAtStartup() {
        if (!available) return
        if (attempt<String?>(null) { settings.getString(PREF_KEY) } == OFF) return
        setOn(true)
    }

    // A registry or settings failure is logged and read as "didn't happen"; it never takes the app down.
    private fun <T> attempt(fallback: T, block: () -> T): T =
        try {
            block()
        } catch (e: Exception) {
            System.err.println("Meal Planner: Start with Windows failed: $e")
            fallback
        }

    companion object {
        const val INSTALLED_PROPERTY = "mealplanner.installed"

        /** P7-R6: set to anything but "on" (the smoke run passes "off"), no Run key is ever written. */
        const val GATE_PROPERTY = "mealplanner.startWithWindows"

        /** Set by jpackage's launcher to its own path (P7-R3). */
        const val APP_PATH_PROPERTY = "jpackage.app-path"

        const val RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        const val VALUE_NAME = "Meal Planner"
        const val PREF_KEY = "start_with_windows"
        const val ON = "on"
        const val OFF = "off"

        private val JAVA_EXES = setOf("java.exe", "javaw.exe")

        /**
         * P7-R6: whether this run may write the Run key: only the installed app ([installed] is INSTALLED_PROPERTY's
         * value, "true" in the packaged launcher), and never when [gate] (GATE_PROPERTY's value) is set to anything
         * but "on". JAVA_TOOL_OPTIONS can add a property but can't override the launcher's own (the JVM reads it before
         * the command line), so the smoke run's off has to win here, in code. A typo counts as off.
         */
        fun allowed(installed: String?, gate: String?): Boolean = installed == "true" && (gate == null || gate == ON)

        /**
         * P7-R3: the installed Meal Planner.exe, for the Run key. jpackage's launcher sets [appPath] (APP_PATH_PROPERTY)
         * to itself; [command] is the process's own executable, the same exe, since the launcher runs the JVM in its
         * own process. Blank counts as unset, and java.exe or javaw.exe is never the answer: a Run key starting the
         * bare JDK would open nothing.
         */
        fun launcherPath(
            appPath: String? = System.getProperty(APP_PATH_PROPERTY),
            command: () -> String? = { ProcessHandle.current().info().command().orElse(null) },
        ): String? {
            fun usable(path: String?): String? =
                path?.trim()?.takeIf { it.isNotEmpty() && File(it).name.lowercase() !in JAVA_EXES }
            return usable(appPath) ?: usable(command())
        }

        /** What the Run value holds: the launcher, quoted (its path has spaces), then MINIMIZED_ARG. */
        fun runValue(launcher: String): String = "\"$launcher\" $MINIMIZED_ARG"

        /**
         * `reg add`. Java quotes an argument that has spaces but leaves quotes inside it alone, and reg.exe reads
         * a backslash-quote as a quote, so the value's own quotes are escaped here.
         */
        fun addArgs(launcher: String): List<String> =
            listOf("add", RUN_KEY, "/v", VALUE_NAME, "/t", "REG_SZ", "/d", runValue(launcher).replace("\"", "\\\""), "/f")

        fun deleteArgs(): List<String> = listOf("delete", RUN_KEY, "/v", VALUE_NAME, "/f")

        fun queryArgs(): List<String> = listOf("query", RUN_KEY, "/v", VALUE_NAME)
    }
}
