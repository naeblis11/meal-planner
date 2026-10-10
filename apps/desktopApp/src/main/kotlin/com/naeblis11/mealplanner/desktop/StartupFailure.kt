package com.naeblis11.mealplanner.desktop

import java.awt.GraphicsEnvironment
import java.io.File
import java.nio.file.FileSystemException
import javax.swing.JOptionPane
import kotlin.system.exitProcess

/**
 * P7-R10: what main does until the app is up. [block] runs; if it throws, the failure is [log]ged (into the log in the
 * app data folder, which DesktopLog has opened first), a small [dialog] names that log, and the process [exit]s with 1.
 * Without this Windows' launcher says only "Failed to launch JVM". A failure is said by its class and stack frames,
 * plus the path a file system failure names, never its message (not ours to vouch for: it might carry the token).
 */
internal fun startOrExplain(
    logFile: File,
    log: (String) -> Unit = { System.err.println(it) },
    dialog: (String) -> Unit = ::showFailureDialog,
    exit: (Int) -> Unit = ::exitProcess,
    dialogWaitMillis: Long = EXPLAIN_WAIT_MILLIS,
    block: () -> Unit,
) {
    try {
        block()
    } catch (t: Throwable) {
        try {
            log("Meal Planner: couldn't start: ${describeFailure(t)}")
            waitAtMost(dialogWaitMillis, log) { explainFailure(logFile, log, dialog) }
        } finally {
            exit(1)
        }
    }
}

/**
 * How long a failure's dialog is waited for before the process exits anyway (M2): the single-instance lock is let go
 * with the process, so a dialog left unanswered must never hold it.
 */
internal const val EXPLAIN_WAIT_MILLIS = 60_000L

/**
 * Runs [explain] (a modal dialog, which waits on the user) on a daemon thread and waits for it at most [millis]; a
 * failure in it is logged. Returns either way, so the caller always goes on to exit.
 */
internal fun waitAtMost(millis: Long, log: (String) -> Unit, explain: () -> Unit) {
    val shown = Thread({
        try {
            explain()
        } catch (t: Throwable) {
            log("Meal Planner: the message about it couldn't be shown: ${t.javaClass.name}")
        }
    }, "failure-dialog").apply { isDaemon = true }
    shown.start()
    shown.join(millis)
}

/** The dialog's text: that the app stopped, and where its log is. */
internal fun failureText(logFile: File): String =
    "Meal Planner stopped because of an error.\n\nWhat went wrong is written in its log:\n${logFile.path}"

/** Shows [failureText] in [dialog]; a dialog that can't be shown (no screen) is logged and passed over. */
internal fun explainFailure(logFile: File, log: (String) -> Unit, dialog: (String) -> Unit) {
    try {
        dialog(failureText(logFile))
    } catch (t: Throwable) {
        log("Meal Planner: the message about it couldn't be shown: ${t.javaClass.name}")
    }
}

/** The class, the path a file system failure names, and the first stack frames. */
internal fun describeFailure(t: Throwable): String {
    val path = (t as? FileSystemException)?.file?.let { ": $it" }.orEmpty()
    return t.javaClass.name + path + t.stackTrace.take(STACK_FRAMES_LOGGED).joinToString("") { "\n\tat $it" }
}

/** A plain Swing message box; nothing at all where there is no screen. */
internal fun showFailureDialog(text: String) {
    if (GraphicsEnvironment.isHeadless()) return
    JOptionPane.showMessageDialog(null, text, "Meal Planner", JOptionPane.ERROR_MESSAGE)
}

internal const val STACK_FRAMES_LOGGED = 20
