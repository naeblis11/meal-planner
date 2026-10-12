package com.naeblis11.mealplanner.desktop

import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.Window

/**
 * Asks Windows to draw the window's title bar dark or light to match the app (owner, 2026-10-12: dark mode), through
 * DWM's "immersive dark mode" attribute. Windows draws that bar, not Compose, so without this a dark app keeps a white
 * title bar. Best effort: an older Windows, another OS or any failure leaves the bar as it was, and nothing is logged.
 */
object DarkTitleBar {
    private interface Dwm : StdCallLibrary {
        fun DwmSetWindowAttribute(hwnd: WinDef.HWND, attribute: Int, value: IntByReference, size: Int): Int
    }

    // DWMWA_USE_IMMERSIVE_DARK_MODE: 20 from Windows 10 20H1 on, 19 on the Windows 10 builds before it.
    private const val IMMERSIVE_DARK_MODE = 20
    private const val IMMERSIVE_DARK_MODE_BEFORE_20H1 = 19

    private val dwm: Dwm? by lazy {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return@lazy null
        try {
            Native.load("dwmapi", Dwm::class.java)
        } catch (e: Throwable) {
            null
        }
    }

    fun apply(window: Window, dark: Boolean) {
        val dwm = dwm ?: return
        try {
            if (!window.isDisplayable) return
            val hwnd = WinDef.HWND(Native.getWindowPointer(window))
            val value = IntByReference(if (dark) 1 else 0)
            if (dwm.DwmSetWindowAttribute(hwnd, IMMERSIVE_DARK_MODE, value, 4) != 0) {
                dwm.DwmSetWindowAttribute(hwnd, IMMERSIVE_DARK_MODE_BEFORE_20H1, value, 4)
            }
        } catch (e: Throwable) {
            // Nothing to do: the title bar keeps Windows' own look.
        }
    }
}
