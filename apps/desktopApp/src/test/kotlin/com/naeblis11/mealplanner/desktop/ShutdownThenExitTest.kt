package com.naeblis11.mealplanner.desktop

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The window is already hidden when shutdown runs, so the application must end however shutdown goes. */
class ShutdownThenExitTest {
    private var exits = 0

    @Test
    fun aShutdownThatWorksExits() {
        runBlocking { shutdownThenExit(shutdown = {}, exit = { exits++ }) }
        assertEquals(1, exits)
    }

    @Test
    fun aShutdownThatThrowsStillExits() {
        runBlocking { shutdownThenExit(shutdown = { throw IOException("database is locked") }, exit = { exits++ }) }
        assertEquals(1, exits)
    }

    @Test
    fun aCancelledShutdownStillExitsAndStaysCancelled() {
        assertThrows(CancellationException::class.java) {
            runBlocking { shutdownThenExit(shutdown = { throw CancellationException("window scope gone") }, exit = { exits++ }) }
        }
        assertEquals(1, exits)
    }

    @Test
    fun aCrashAfterStartIsSaidByClassAndStillEndsTheProcess() {
        // JmDNS's non-daemon threads would keep a windowless process alive, holding the single-instance lock.
        val said = mutableListOf<String>()
        val codes = mutableListOf<Int>()
        runThenExit(exit = { codes += it }, log = { said += it }) { throw IllegalStateException("SECRET detail") }
        assertEquals(listOf(1), codes)
        assertEquals(1, said.size)
        assertTrue(said.single(), said.single().startsWith("Meal Planner: stopped by an error: java.lang.IllegalStateException"))
        assertTrue(said.single(), "\tat " in said.single())
        assertFalse(said.single().contains("SECRET"))
    }

    @Test
    fun aRunThatEndsEndsTheProcessQuietly() {
        val said = mutableListOf<String>()
        val codes = mutableListOf<Int>()
        runThenExit(exit = { codes += it }, log = { said += it }) {}
        assertEquals(listOf(0), codes)
        assertEquals(emptyList<String>(), said)
    }

    @Test
    fun aTimedOutCloseKeepsTheLockForTheExitAndSaysSo() {
        val said = mutableListOf<String>()
        var released = 0
        // Background work may still be running: the lock goes only with the process, so a relaunch can't overlap it.
        runBlocking { closeForQuit(shutdown = { false }, release = { released++ }, log = { said += it }) }
        assertEquals(0, released)
        assertEquals(listOf(QUIT_TIMED_OUT), said)
        assertThrows(IOException::class.java) {
            runBlocking { closeForQuit(shutdown = { throw IOException("database is locked") }, release = { released++ }, log = { said += it }) }
        }
        assertEquals(0, released)
        runBlocking { closeForQuit(shutdown = { true }, release = { released++ }, log = { said += it }) }
        assertEquals(1, released)
        assertEquals(1, said.size)
    }
}
