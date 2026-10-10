package com.naeblis11.mealplanner.desktop

import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P3-R1 and P3-R6: closing hides the window, the tray brings it back, Quit runs once. */
class WindowShellTest {
    private val settings = MapSettings()

    private fun shell(minimized: Boolean = false, tray: Boolean = true) = WindowShell(minimized, tray, TrayNotice(settings))

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
