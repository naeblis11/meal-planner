package com.naeblis11.mealplanner.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P3-R3: the Run key, through a fake reg.exe; the real registry is never touched. */
class StartWithWindowsTest {
    /** reg.exe as far as these calls go: [exit] for add and delete, and whether the value is [present]. */
    private class FakeReg(var exit: Int = 0, var present: Boolean = false) : RegistryRunner {
        val calls = mutableListOf<List<String>>()

        override fun run(args: List<String>): Int {
            calls += args
            return when (args.first()) {
                "query" -> if (present) 0 else 1
                "add" -> exit.also { if (it == 0) present = true }
                "delete" -> exit.also { if (it == 0) present = false }
                else -> 1
            }
        }
    }

    private val launcher = "C:\\Users\\jo\\AppData\\Local\\Meal-Planner\\Meal Planner.exe"
    private val settings = MapSettings()

    private fun startup(reg: RegistryRunner, installed: Boolean = true) =
        StartWithWindows(reg, settings, installed = installed, launcher = { launcher })

    @Test
    fun theRunValueStartsThisLauncherInTheTray() {
        assertEquals("\"$launcher\" --minimized", StartWithWindows.runValue(launcher))
        assertEquals(
            listOf(
                "add", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run", "/v", "Meal Planner",
                "/t", "REG_SZ", "/d", "\\\"$launcher\\\" --minimized", "/f",
            ),
            StartWithWindows.addArgs(launcher),
        )
    }

    @Test
    fun theFirstInstalledStartTurnsItOn() {
        val reg = FakeReg()
        startup(reg).applyAtStartup()
        assertEquals(listOf(StartWithWindows.addArgs(launcher)), reg.calls)
        assertEquals("on", settings.getString(StartWithWindows.PREF_KEY))
        assertTrue(startup(reg).isOn())
    }

    @Test
    fun anInstalledStartPointsTheRunKeyAtThisLauncherAgain() {
        settings.put(mapOf(StartWithWindows.PREF_KEY to "on"))
        val reg = FakeReg(present = true)
        startup(reg).applyAtStartup()
        assertEquals(listOf(StartWithWindows.addArgs(launcher)), reg.calls)
    }

    @Test
    fun turnedOffItStaysOffAtTheNextStart() {
        val reg = FakeReg(present = true)
        assertTrue(startup(reg).setOn(false))
        assertEquals(listOf(StartWithWindows.deleteArgs()), reg.calls)
        assertEquals("off", settings.getString(StartWithWindows.PREF_KEY))
        reg.calls.clear()
        startup(reg).applyAtStartup()
        assertEquals(emptyList<List<String>>(), reg.calls)
        assertFalse(startup(reg).isOn())
    }

    @Test
    fun aRefusedWriteKeepsTheChoiceAsItWas() {
        val reg = FakeReg(exit = 1)
        assertFalse(startup(reg).setOn(true))
        assertNull(settings.getString(StartWithWindows.PREF_KEY))
    }

    @Test
    fun developmentNeverTouchesTheRegistry() {
        val reg = FakeReg()
        val dev = startup(reg, installed = false)
        dev.applyAtStartup()
        assertFalse(dev.available)
        assertFalse(dev.isOn())
        assertFalse(dev.setOn(true))
        assertEquals(emptyList<List<String>>(), reg.calls)
        assertNull(settings.getString(StartWithWindows.PREF_KEY))
    }

    @Test
    fun anOffSwitchBeatsTheInstalledLauncher() {
        // P7-R6: JAVA_TOOL_OPTIONS can add -Dmealplanner.startWithWindows=off but can't override the launcher's own
        // -Dmealplanner.installed=true (the JVM reads it before the command line), so the off has to win here.
        assertTrue(StartWithWindows.allowed(installed = "true", gate = null))
        assertTrue(StartWithWindows.allowed(installed = "true", gate = "on"))
        assertFalse(StartWithWindows.allowed(installed = "true", gate = "off"))
        assertFalse(StartWithWindows.allowed(installed = "true", gate = "no"))
        assertFalse(StartWithWindows.allowed(installed = "true", gate = ""))
        assertFalse(StartWithWindows.allowed(installed = null, gate = null))
        assertFalse(StartWithWindows.allowed(installed = "false", gate = "on"))
    }

    @Test
    fun anInstalledRunSwitchedOffNeverTouchesTheRegistry() {
        val reg = FakeReg()
        val gated = startup(reg, installed = StartWithWindows.allowed(installed = "true", gate = "off"))
        gated.applyAtStartup()
        assertFalse(gated.available)
        assertFalse(gated.isOn())
        assertFalse(gated.setOn(true))
        assertEquals(emptyList<List<String>>(), reg.calls)
        assertNull(settings.getString(StartWithWindows.PREF_KEY))
    }

    @Test
    fun theLauncherIsThePathJpackageSets() {
        assertEquals(launcher, StartWithWindows.launcherPath(appPath = launcher) { "C:\\elsewhere\\other.exe" })
    }

    @Test
    fun withoutJpackagesPathTheProcessIsTheLauncher() {
        assertEquals(launcher, StartWithWindows.launcherPath(appPath = null) { launcher })
        assertEquals(launcher, StartWithWindows.launcherPath(appPath = "  ") { launcher })
        assertNull(StartWithWindows.launcherPath(appPath = null) { null })
    }

    @Test
    fun theJdksJavaIsNeverTheLauncher() {
        // A Run key that starts the bare JDK would open nothing.
        assertNull(StartWithWindows.launcherPath(appPath = null) { "C:\\jdk\\bin\\java.exe" })
        assertNull(StartWithWindows.launcherPath(appPath = null) { "C:\\jdk\\bin\\JAVAW.EXE" })
        assertEquals(launcher, StartWithWindows.launcherPath(appPath = "C:\\jdk\\bin\\java.exe") { launcher })
    }

    @Test
    fun startedByJavaItIsNotOffered() {
        val reg = FakeReg()
        val viaJava = StartWithWindows(
            reg,
            settings,
            installed = true,
            launcher = { StartWithWindows.launcherPath(appPath = null) { "C:\\jdk\\bin\\javaw.exe" } },
        )
        viaJava.applyAtStartup()
        assertFalse(viaJava.available)
        assertFalse(viaJava.setOn(true))
        assertEquals(emptyList<List<String>>(), reg.calls)
    }
}
