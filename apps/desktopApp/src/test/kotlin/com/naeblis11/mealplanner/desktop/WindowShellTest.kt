package com.naeblis11.mealplanner.desktop

import com.naeblis11.mealplanner.desktop.server.eventually
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P3-R1 and P3-R6: closing hides the window, the tray brings it back, Quit runs once. */
class WindowShellTest {
    private val settings = MapSettings()

    private fun shell(minimized: Boolean = false, tray: Boolean = true, look: () -> Unit = {}) =
        WindowShell(minimized, tray, TrayNotice(settings), look = look)

    @Test
    fun theLookRunsOffTheCallingThreadOnceTheWindowIsShown() {
        // The look reads the disk (P7-R12) and show() is called on the Swing thread: it must never wait on the look.
        val seen = CopyOnWriteArrayList<String>()
        val release = CountDownLatch(1)
        lateinit var s: WindowShell
        s = shell(minimized = true) {
            seen += "visible=${s.isVisible} thread=${Thread.currentThread().name}"
            release.await(10, TimeUnit.SECONDS)
        }
        s.show()
        // Back at once, with the window told to come forward, while the look is still held.
        assertTrue(s.isVisible)
        assertTrue(s.hasBeenShown)
        assertEquals(1, s.raiseRequests)
        assertEquals(0, s.looksCompleted)
        release.countDown()
        eventually { s.looksCompleted == 1 }
        assertEquals(1, seen.size)
        assertTrue(seen.single(), seen.single().startsWith("visible=true thread=install-look"))
        // Every show looks, in order, on the one thread.
        s.show()
        eventually { s.looksCompleted == 2 }
        assertEquals(2, seen.size)
        assertEquals(2, s.raiseRequests)
    }

    @Test
    fun aLookThatThrowsStillCountsAndNeverStopsTheShow() {
        val s = shell(minimized = true) { throw IllegalStateException("the disk is away") }
        s.show()
        assertTrue(s.isVisible)
        eventually { s.looksCompleted == 1 }
        s.show()
        eventually { s.looksCompleted == 2 }
        assertTrue(s.isVisible)
    }

    @Test
    fun aQuittingShellDoesntLook() {
        var looks = 0
        val s = shell { looks++ }
        assertTrue(s.quit())
        s.show()
        assertEquals(0, s.looksCompleted)
        assertEquals(0, looks)
    }

    @Test
    fun aMinimizedStartStaysInTheTrayUntilOpened() {
        val s = shell(minimized = true)
        assertFalse(s.isVisible)
        assertFalse(s.hasBeenShown)
        s.show()
        assertTrue(s.isVisible)
        assertTrue(s.hasBeenShown)
        assertEquals(1, s.raiseRequests)
        assertTrue(shell(minimized = false).isVisible)
    }

    @Test
    fun withoutATrayEvenAMinimizedStartShowsTheWindow() {
        val s = shell(minimized = true, tray = false)
        assertTrue(s.isVisible)
        assertTrue(s.hasBeenShown)
    }

    @Test
    fun closingHidesAndSaysSoOnceOnThisPc() {
        val s = shell()
        assertEquals(CloseOutcome.HIDDEN_FIRST_TIME, s.closeRequested())
        assertFalse(s.isVisible)
        s.show()
        assertEquals(CloseOutcome.HIDDEN, s.closeRequested())
        // The next run on this PC reads the same settings: no second message.
        assertEquals(CloseOutcome.HIDDEN, shell().closeRequested())
        assertEquals(TrayNotice.SHOWN, settings.getString(TrayNotice.KEY))
    }

    @Test
    fun withoutATrayClosingQuits() {
        val s = shell(tray = false)
        assertEquals(CloseOutcome.QUIT, s.closeRequested())
        assertTrue(s.quit())
        assertFalse(s.quit())
    }

    @Test
    fun quitRunsOnceAndNothingReopensTheWindow() {
        val s = shell()
        assertTrue(s.quit())
        assertTrue(s.quitting)
        assertFalse(s.quit())
        s.show()
        assertFalse(s.isVisible)
    }

    @Test
    fun theIconIsTheRepoIconScaledForTheTray() {
        val image = WindowShell::class.java.getResourceAsStream("/$ICON_RESOURCE")!!.use { ImageIO.read(it) }
        assertEquals(64, image.width)
        assertEquals(64, image.height)
        assertNotNull(appIconBitmap())
    }
}
