package com.naeblis11.mealplanner.desktop

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.util.logging.ErrorManager
import java.util.logging.Level
import java.util.logging.LogRecord
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P3-R5: what the app says goes to <dataDir>\.cache\meal-planner.log, rolled at 1 MB, three files kept. */
class DesktopLogTest {
    private val dir: File = Files.createTempDirectory("mp-log").toFile()
    private val file = File(dir, DesktopLog.FILE_NAME)

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun aRecordIsOneLineInTheLog() {
        val handler = RollingLogHandler(file, 1_000, 3)
        try {
            handler.publish(LogRecord(Level.WARNING, "Meal Planner: recipe folder sync failed"))
        } finally {
            handler.close()
        }
        val lines = file.readLines()
        assertEquals(1, lines.size)
        assertTrue(lines[0], Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} WARNING Meal Planner: recipe folder sync failed$""").matches(lines[0]))
    }

    @Test
    fun theLogRollsAtItsLimitAndKeepsThreeFiles() {
        val handler = RollingLogHandler(file, 200, 3)
        try {
            repeat(30) { handler.publish(LogRecord(Level.INFO, "line $it of the log")) }
        } finally {
            handler.close()
        }
        val older = File(dir, "${DesktopLog.FILE_NAME}.1")
        val oldest = File(dir, "${DesktopLog.FILE_NAME}.2")
        assertTrue(file.isFile)
        assertTrue(older.isFile)
        assertTrue(oldest.isFile)
        assertFalse(File(dir, "${DesktopLog.FILE_NAME}.3").exists())
        listOf(file, older, oldest).forEach { assertTrue("${it.name} is ${it.length()} bytes", it.length() <= 200) }
        assertTrue(file.readText().contains("line 29 of the log"))
        // .2 holds the oldest lines, .1 the ones after them, the current file the newest.
        val numbers = listOf(oldest, older, file).map { f -> Regex("""line (\d+) of""").findAll(f.readText()).map { it.groupValues[1].toInt() }.toList() }
        assertTrue(numbers.toString(), numbers[0].max() < numbers[1].min())
        assertTrue(numbers.toString(), numbers[1].max() < numbers[2].min())
    }

    @Test
    fun aLogThatIsAlreadyThereIsAppendedToAndCountedTowardTheLimit() {
        val first = "first".padEnd(20, '.')
        val second = "second".padEnd(20, '.')
        val third = "third".padEnd(20, '.')
        RollingLogHandler(file, 100, 3).apply { publish(LogRecord(Level.INFO, first)); close() }
        RollingLogHandler(file, 100, 3).apply {
            publish(LogRecord(Level.INFO, second))
            publish(LogRecord(Level.INFO, third))
            close()
        }
        // The new handler knew the file already held a line: two fit, the third rolled it.
        assertEquals(listOf(first, second), File(dir, "${DesktopLog.FILE_NAME}.1").readLines().map { it.substringAfter(" INFO ") })
        assertEquals(listOf(third), file.readLines().map { it.substringAfter(" INFO ") })
    }

    @Test
    fun aRollingFileLeftByACrashIsPromotedNotDeleted() {
        File(dir, "${DesktopLog.FILE_NAME}.1").writeText("an older log\n")
        File(dir, "${DesktopLog.FILE_NAME}.rolling").writeText("interrupted log\n")
        file.writeText("current log line\n".repeat(11))
        val handler = RollingLogHandler(file, 200, 3)
        try {
            handler.publish(LogRecord(Level.INFO, "after the crash"))
        } finally {
            handler.close()
        }
        assertFalse(File(dir, "${DesktopLog.FILE_NAME}.rolling").exists())
        assertEquals("interrupted log\n", File(dir, "${DesktopLog.FILE_NAME}.1").readText())
        assertEquals("an older log\n", File(dir, "${DesktopLog.FILE_NAME}.2").readText())
        // The current log was not moved this time; it keeps its lines and gets the new one.
        assertTrue(file.readText().startsWith("current log line"))
        assertTrue(file.readText().contains("after the crash"))
    }

    @Test
    fun aRollThatFailsHalfwayAndCannotPutTheLogBackLeavesItForTheNextRoll() {
        file.writeText("current log line\n".repeat(11))
        val first = RollingLogHandler(file, 200, 3)
        var moves = 0
        // Moving the log aside works; everything after that, including putting it back, fails.
        first.move = { from, to, _ ->
            if (moves++ > 0) throw IOException("${from.name} is locked")
            Files.move(from.toPath(), to.toPath())
        }
        try {
            first.publish(LogRecord(Level.INFO, "while locked"))
        } finally {
            first.close()
        }
        val aside = File(dir, "${DesktopLog.FILE_NAME}.rolling")
        assertEquals("current log line\n".repeat(11), aside.readText())
        assertTrue(file.readText().contains("while locked"))

        val second = RollingLogHandler(file, 200, 3)
        try {
            repeat(5) { second.publish(LogRecord(Level.INFO, "line $it of the log")) }
        } finally {
            second.close()
        }
        assertFalse(aside.exists())
        // Promoted to .1, then shifted to .2 by the roll that follows it.
        val kept = listOf(1, 2).map { File(dir, "${DesktopLog.FILE_NAME}.$it") }.filter { it.isFile }.map { it.readText() }
        assertTrue(kept.contains("current log line\n".repeat(11)))
    }

    @Test
    fun aLogThatCannotBeMovedKeepsItsHistoryAndDoesNotTryOnEveryLine() {
        val older = File(dir, "${DesktopLog.FILE_NAME}.1").apply { writeText("an older log\n") }
        val oldest = File(dir, "${DesktopLog.FILE_NAME}.2").apply { writeText("the oldest log\n") }
        var errors = 0
        val handler = RollingLogHandler(file, 1_000, 3)
        // A lock on the log (OneDrive, a scanner) makes moving it aside fail.
        handler.move = { from, _, _ -> throw IOException("${from.name} is locked") }
        handler.errorManager = object : ErrorManager() {
            override fun error(msg: String?, ex: Exception?, code: Int) {
                errors++
            }
        }
        try {
            repeat(40) { handler.publish(LogRecord(Level.INFO, "line $it of the log")) }
        } finally {
            handler.close()
        }
        assertEquals("an older log\n", older.readText())
        assertEquals("the oldest log\n", oldest.readText())
        assertTrue(file.readText().contains("line 0 of the log"))
        assertTrue(file.readText().contains("line 39 of the log"))
        assertTrue("$errors roll attempts failed", errors in 1..5)
    }

    @Test
    fun stderrLinesGoToTheLogAndStillToTheConsole() {
        val handler = RollingLogHandler(file, 10_000, 3)
        val console = ByteArrayOutputStream()
        val err = DesktopLog.capture(handler, PrintStream(console, true, Charsets.UTF_8))
        err.println("Meal Planner: Importing from the old Meal Planner failed: disk full")
        err.print("half a line")
        err.close()
        handler.close()
        assertEquals(
            listOf("Meal Planner: Importing from the old Meal Planner failed: disk full", "half a line"),
            file.readLines().map { it.substringAfter(" INFO ") },
        )
        assertTrue(console.toString(Charsets.UTF_8).startsWith("Meal Planner: Importing from the old Meal Planner failed"))
    }
}
