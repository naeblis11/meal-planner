package com.naeblis11.mealplanner.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** P7-R6: the packaged app's self-check, as smoke-packaged.ps1 reads it from the log. */
class SelfCheckTest {
    private val exe = "C:\\Users\\jo\\AppData\\Local\\Meal-Planner\\Meal Planner.exe"
    private val java = "C:\\jdk\\bin\\java.exe"

    @Test
    fun onlyOnTurnsItOn() {
        assertTrue(SelfCheck.enabled("on"))
        assertFalse(SelfCheck.enabled(null))
        assertFalse(SelfCheck.enabled("true"))
        assertFalse(SelfCheck.enabled("ON"))
    }

    @Test
    fun eachCheckGetsOneLineThenASummary() {
        val report = SelfCheck.run(listOf("a" to { "" }, "b" to { "8 x 6" }))
        assertEquals(
            listOf(
                "Meal Planner: self-check a ok",
                "Meal Planner: self-check b ok (8 x 6)",
                "Meal Planner: self-check done: 2 of 2 passed",
            ),
            report.lines,
        )
        assertTrue(report.passed)
    }

    @Test
    fun aFailureIsNamedAndTheOtherChecksStillRun() {
        val report = SelfCheck.run(
            listOf(
                "jna" to { throw NoClassDefFoundError("com/sun/jna/Native") },
                "webp" to { "8 x 6" },
            ),
        )
        assertEquals(
            listOf(
                "Meal Planner: self-check jna FAILED: java.lang.NoClassDefFoundError: com/sun/jna/Native",
                "Meal Planner: self-check webp ok (8 x 6)",
                "Meal Planner: self-check done: 1 of 2 passed",
            ),
            report.lines,
        )
        assertFalse(report.passed)
    }

    @Test
    fun aLongOrMultiLineMessageBecomesOneShortLine() {
        val message = "line one\nline two" + "z".repeat(500)
        val report = SelfCheck.run(listOf("x" to { throw IllegalStateException(message) }))
        assertEquals(
            "Meal Planner: self-check x FAILED: java.lang.IllegalStateException: " + message.replace('\n', ' ').take(200),
            report.lines.first(),
        )
    }

    @Test
    fun itHoldsThenClosesThenExitsZeroWhenEverythingPassed() {
        val events = mutableListOf<String>()
        SelfCheck.runThenQuit(
            checks = listOf("a" to { "" }),
            close = { events += "close"; true },
            exit = { events += "exit $it" },
            sleep = { events += "hold $it" },
            log = { events += it },
        )
        assertEquals(
            listOf("Meal Planner: self-check a ok", "Meal Planner: self-check done: 1 of 1 passed", "hold 20000", "close", "exit 0"),
            events,
        )
    }

    @Test
    fun aFailedCheckStillClosesAndExitsThree() {
        val events = mutableListOf<String>()
        SelfCheck.runThenQuit(
            checks = listOf("a" to { error("broken") }),
            close = { events += "close"; true },
            exit = { events += "exit $it" },
            sleep = {},
            log = {},
        )
        assertEquals(listOf("close", "exit 3"), events)
    }

    @Test
    fun aCloseThatTimedOutExitsFour() {
        val events = mutableListOf<String>()
        SelfCheck.runThenQuit(
            checks = listOf("a" to { "" }),
            close = { false },
            exit = { events += "exit $it" },
            sleep = {},
            log = { if (!it.contains(" a ok") && !it.contains("done:")) events += it },
        )
        assertEquals(listOf("Meal Planner: self-check: closing timed out", "exit 4"), events)
    }

    @Test
    fun anErrorWhileClosingStillExitsFour() {
        // An Error must not skip the exit: the window is hidden by then, and the process would hold the instance lock.
        val events = mutableListOf<String>()
        SelfCheck.runThenQuit(
            checks = listOf("a" to { "" }),
            close = { throw AssertionError("boom") },
            exit = { events += "exit $it" },
            sleep = {},
            log = { if (!it.contains(" a ok") && !it.contains("done:")) events += it },
        )
        assertEquals(listOf("Meal Planner: self-check: closing failed: java.lang.AssertionError", "exit 4"), events)
    }

    @Test
    fun quitWaitsForTheSelfCheckWhileItRuns() {
        val events = mutableListOf<String>()
        SelfCheck.userQuit(enabled = true, quit = { events += "quit" }, log = { events += it })()
        assertEquals(listOf("Meal Planner: Quit is off during the self-check, which quits the app by itself"), events)
        events.clear()
        SelfCheck.userQuit(enabled = false, quit = { events += "quit" }, log = { events += it })()
        assertEquals(listOf("quit"), events)
    }

    @Test
    fun theLauncherCheckWantsTheInstalledExe() {
        val good = SelfCheck.run(listOf("launcher" to { SelfCheck.launcher(exe) { exe } }))
        assertEquals("Meal Planner: self-check launcher ok (resolved=$exe; app-path=$exe; command=$exe)", good.lines.first())
        val viaJava = SelfCheck.run(listOf("launcher" to { SelfCheck.launcher(null) { java } }))
        assertEquals("Meal Planner: self-check launcher FAILED: java.lang.IllegalStateException: resolved=null", viaJava.lines.first())
        val renamed = SelfCheck.run(listOf("launcher" to { SelfCheck.launcher("C:\\x\\Other.exe") { java } }))
        assertFalse(renamed.passed)
    }

    @Test
    fun everyRealCheckPassesInThisJvm() {
        // The checks themselves, on the test's classpath and JDK; the smoke run repeats them in the packaged app.
        assumeTrue(System.getProperty("os.name").orEmpty().startsWith("Windows")) // DPAPI, in memory only
        val report = SelfCheck.run(SelfCheck.standardChecks(appPath = { exe }, command = { java }))
        assertTrue(report.lines.joinToString("\n"), report.passed)
        assertEquals("Meal Planner: self-check done: 7 of 7 passed", report.lines.last())
    }
}
