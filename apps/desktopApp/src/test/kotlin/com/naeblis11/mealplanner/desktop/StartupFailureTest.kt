package com.naeblis11.mealplanner.desktop

import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P7-R10: a start that fails (as when Windows' Controlled folder access refused a folder) leaves a line in the log in
 * the app data folder and a small dialog naming that log, never Windows' bare "Failed to launch JVM".
 */
class StartupFailureTest {
    private val appData: File = Files.createTempDirectory("mp-startup-failure").toFile()
    private val logFile = File(File(appData, ".cache"), DesktopLog.FILE_NAME)
    private val said = mutableListOf<String>()
    private val shown = mutableListOf<String>()
    private val codes = mutableListOf<Int>()

    @After
    fun tearDown() {
        appData.deleteRecursively()
    }

    @Test
    fun aFailedStartIsLoggedWithItsPathAndExplainedInADialog() {
        startOrExplain(logFile, log = { said += it }, dialog = { shown += it }, exit = { codes += it }) {
            throw AccessDeniedException("C:\\Users\\someone\\OneDrive\\Documents\\Meal Planner")
        }
        assertEquals(listOf(1), codes)
        val line = said.single()
        assertTrue(line, line.startsWith("Meal Planner: couldn't start: java.nio.file.AccessDeniedException"))
        assertTrue(line, "C:\\Users\\someone\\OneDrive\\Documents\\Meal Planner" in line)
        assertTrue(line, "\tat " in line)
        assertEquals(listOf(failureText(logFile)), shown)
        assertTrue(shown.single(), logFile.path in shown.single())
        assertFalse(shown.single().contains("Failed to launch JVM"))
    }

    @Test
    fun aFailureIsSaidByClassNeverByItsMessage() {
        startOrExplain(logFile, log = { said += it }, dialog = { shown += it }, exit = { codes += it }) {
            throw IllegalStateException("SECRET detail")
        }
        assertEquals(listOf(1), codes)
        assertTrue(said.single(), said.single().startsWith("Meal Planner: couldn't start: java.lang.IllegalStateException"))
        assertFalse(said.single().contains("SECRET"))
        assertFalse(shown.single().contains("SECRET"))
    }

    @Test
    fun aStartThatWorksSaysNothing() {
        var ran = false
        startOrExplain(logFile, log = { said += it }, dialog = { shown += it }, exit = { codes += it }) { ran = true }
        assertTrue(ran)
        assertEquals(emptyList<String>(), said)
        assertEquals(emptyList<String>(), shown)
        assertEquals(emptyList<Int>(), codes)
    }

    @Test
    fun aDialogThatCantBeShownStillEndsTheProcess() {
        startOrExplain(logFile, log = { said += it }, dialog = { throw java.awt.HeadlessException() }, exit = { codes += it }) {
            throw IllegalStateException("boom")
        }
        assertEquals(listOf(1), codes)
        assertEquals(2, said.size)
        assertTrue(said[1], said[1].startsWith("Meal Planner: the message about it couldn't be shown: java.awt.HeadlessException"))
    }

    @Test
    fun theLineLandsInTheLogInTheAppDataFolder() {
        // As main has it: DesktopLog in the app data's .cache, made on the first line, before anything else can fail.
        val stream = DesktopLog.capture(RollingLogHandler(logFile, DesktopLog.LIMIT_BYTES, DesktopLog.FILES), null)
        startOrExplain(logFile, log = { stream.println(it) }, dialog = { shown += it }, exit = { codes += it }) {
            throw AccessDeniedException("C:\\Documents\\Meal Planner")
        }
        stream.close()
        assertTrue(logFile.readText().contains("Meal Planner: couldn't start: java.nio.file.AccessDeniedException: C:\\Documents\\Meal Planner"))
    }

    @Test
    fun aDialogNobodyAnswersNeverKeepsTheProcess() {
        // M2: the modal dialog waits on the user; the exit (which lets the single-instance lock go) never does.
        val never = java.util.concurrent.CountDownLatch(1)
        try {
            runThenExit(exit = { codes += it }, log = { said += it }, explain = { never.await() }, explainWaitMillis = 50) {
                throw IllegalStateException("x")
            }
            startOrExplain(logFile, log = { said += it }, dialog = { never.await() }, exit = { codes += it }, dialogWaitMillis = 50) {
                throw IllegalStateException("y")
            }
            assertEquals(listOf(1, 1), codes)
        } finally {
            never.countDown()
        }
    }

    @Test
    fun anExplainThatThrowsStillExits() {
        runThenExit(exit = { codes += it }, log = { said += it }, explain = { throw IllegalStateException("no screen") }) {
            throw IllegalStateException("x")
        }
        assertEquals(listOf(1), codes)
    }

    @Test
    fun aCrashAfterTheStartIsExplainedToo() {
        var explained = 0
        runThenExit(exit = { codes += it }, log = { said += it }, explain = { explained++ }) { throw IllegalStateException("x") }
        assertEquals(listOf(1), codes)
        assertEquals(1, explained)
        runThenExit(exit = { codes += it }, log = { said += it }, explain = { explained++ }) {}
        assertEquals(1, explained)
    }
}
