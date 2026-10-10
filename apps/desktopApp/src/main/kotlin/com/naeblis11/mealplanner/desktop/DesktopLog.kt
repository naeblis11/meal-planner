package com.naeblis11.mealplanner.desktop

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PrintStream
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.ZoneId
import java.util.logging.ErrorManager
import java.util.logging.Formatter
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord

/**
 * The desktop's log (P3-R5): `<dataDir>\.cache\meal-planner.log`, rolled at 1 MB with the two files before it kept
 * (`.log.1`, `.log.2`). Everything the app says on stderr goes there too, so the packaged app, which has no console,
 * still leaves a record of watcher, import and shutdown problems.
 */
object DesktopLog {
    const val FILE_NAME = "meal-planner.log"
    const val LIMIT_BYTES: Long = 1_048_576L
    const val FILES = 3

    /** Opens the log in [cacheDir] and sends System.err there as well as to the console. Records are written straight through, so nothing needs closing at exit. */
    fun install(cacheDir: File) {
        val handler = RollingLogHandler(File(cacheDir, FILE_NAME), LIMIT_BYTES, FILES)
        System.setErr(capture(handler, System.err))
    }

    /** A stream whose every finished line becomes one log record (logger "stderr"), echoed byte for byte to [echo]. */
    fun capture(handler: Handler, echo: PrintStream?): PrintStream = PrintStream(LineSink(handler, echo), true, Charsets.UTF_8)
}

/**
 * A java.util.logging handler that appends to [file] and, when the next record would take it past [limitBytes],
 * moves it to `<file>.1` (the older `.1` to `.2`, and so on), keeping [files] files in all. FileHandler can't keep
 * the current file under its own name, which is the name the docs give. Each record is written straight through, so
 * a crash loses nothing already logged. If the file can't be moved (a lock from OneDrive or a virus scanner), the
 * older files are left alone, the problem is reported once per attempt, and the file keeps growing until it has
 * grown by a quarter of the limit, when the roll is tried again.
 */
class RollingLogHandler(private val file: File, private val limitBytes: Long, private val files: Int) : Handler() {
    private var out: FileOutputStream? = null
    private var size = 0L
    private var retryRollAt = 0L

    init {
        require(files >= 1) { "files must be at least 1: $files" }
        formatter = LineFormatter()
    }

    @Synchronized
    override fun publish(record: LogRecord) {
        if (!isLoggable(record)) return
        val bytes = try {
            formatter.format(record).toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            reportError(null, e, ErrorManager.FORMAT_FAILURE)
            return
        }
        try {
            if (out == null) open()
            if (size > 0 && size + bytes.size > limitBytes && size >= retryRollAt) roll()
            out!!.write(bytes)
            size += bytes.size
        } catch (e: IOException) {
            reportError(null, e, ErrorManager.WRITE_FAILURE)
        }
    }

    private fun open() {
        out?.close()
        out = null
        file.parentFile?.mkdirs()
        out = FileOutputStream(file, true)
        size = file.length()
    }

    // Moves a file; [replace] lets it take the place of an older file. A test can make it fail.
    internal var move: (from: File, to: File, replace: Boolean) -> Unit = { from, to, replace ->
        if (replace) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } else {
            Files.move(from.toPath(), to.toPath())
        }
    }

    // See rollFiles. On failure the problem is reported and the log keeps growing until a quarter of the limit more.
    private fun roll() {
        out?.close()
        out = null
        val rolled = try {
            rollFiles()
            true
        } catch (e: IOException) {
            reportError("The log was not rolled", e, ErrorManager.GENERIC_FAILURE)
            false
        }
        open()
        retryRollAt = if (rolled) 0L else size + limitBytes / 4
    }

    // (a) The current file moves to `<file>.rolling`, (b) each older file moves up one, oldest first, and (c) the
    // moved file becomes `.1`. No step deletes anything but the oldest file, which the shift replaces. If a step
    // fails, the file is put back; if that fails too, `.rolling` stays, and the next roll finds it and promotes it
    // to `.1` (keeping the current file as it is) instead of moving the current file aside.
    private fun rollFiles() {
        val aside = File("${file.path}.rolling")
        val moved = !aside.exists()
        if (moved) move(file, aside, false)
        try {
            if (files == 1) {
                aside.delete()
                return
            }
            for (n in files - 2 downTo 1) {
                val from = File("${file.path}.$n")
                if (from.exists()) move(from, File("${file.path}.${n + 1}"), true)
            }
            move(aside, File("${file.path}.1"), true)
        } catch (e: IOException) {
            if (moved) {
                try {
                    move(aside, file, false)
                } catch (back: IOException) {
                    e.addSuppressed(back)
                }
            }
            throw e
        }
    }

    @Synchronized
    override fun flush() {
        out?.flush()
    }

    @Synchronized
    override fun close() {
        out?.close()
        out = null
    }
}

/** One line per record, "2026-10-05 14:03:12 INFO Meal Planner: ...", then the stack trace when there is one. */
class LineFormatter : Formatter() {
    override fun format(record: LogRecord): String {
        val time = record.instant.atZone(ZoneId.systemDefault())
        val line = String.format("%1\$tF %1\$tT %2\$s %3\$s%n", time, record.level.name, formatMessage(record))
        val thrown = record.thrown ?: return line
        val trace = StringWriter()
        thrown.printStackTrace(PrintWriter(trace))
        return line + trace
    }
}

// Bytes in, one log record per finished line out; every byte also goes on to the console, as before.
private class LineSink(private val handler: Handler, private val echo: PrintStream?) : OutputStream() {
    private val line = ByteArrayOutputStream()

    @Synchronized
    override fun write(b: Int) {
        echo?.write(b)
        if (b == '\n'.code) emit() else line.write(b)
    }

    @Synchronized
    override fun write(b: ByteArray, off: Int, len: Int) {
        for (i in off until off + len) write(b[i].toInt() and 0xFF)
    }

    @Synchronized
    override fun flush() {
        echo?.flush()
    }

    @Synchronized
    override fun close() {
        if (line.size() > 0) emit()
        flush()
    }

    private fun emit() {
        val text = line.toString(Charsets.UTF_8).trimEnd('\r')
        line.reset()
        handler.publish(LogRecord(Level.INFO, text).apply { loggerName = "stderr" })
    }
}
