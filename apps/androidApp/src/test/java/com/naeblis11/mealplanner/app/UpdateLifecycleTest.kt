package com.naeblis11.mealplanner.app

import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import com.naeblis11.mealplanner.MainActivity
import com.naeblis11.mealplanner.update.DeferredUpdates
import com.naeblis11.mealplanner.update.PhoneReleases
import com.naeblis11.mealplanner.update.testKeys
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The update check's ties to the app's lifecycle (plan 8): a launch check once per process, however the first activity
 * starts, and the "allow installs" banner refreshed when the user comes back. Each Robolectric test is a fresh process
 * (a new MealPlannerApplication), whose update check runs on a fake release server on 127.0.0.1.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdateLifecycleTest {
    private val app = ApplicationProvider.getApplicationContext<MealPlannerApplication>()
    private val keys = testKeys()
    private val releases = PhoneReleases(keys)

    @After
    fun tearDown() {
        releases.close()
    }

    @Test
    fun aRestoredActivityOnAFreshProcessRunsTheLaunchCheckOnce() {
        // Android ended the process and now restores MainActivity with its saved state: still this process's launch.
        app.updatesFactory = { releases.updates(app, keys) }
        val saved = Bundle()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup(saved)
        assertTrue("the launch check asked for latest.json", releases.listAsked.await(10, TimeUnit.SECONDS))
        assertEquals(1, app.launchChecks.get())
        // A rotation's re-creation, and a second activity, aren't launches.
        activity.recreate()
        Robolectric.buildActivity(MainActivity::class.java).setup(saved).pause().stop().destroy()
        assertEquals(1, app.launchChecks.get())
        activity.pause().stop().destroy()
    }

    @Test
    fun theUpdateCheckIsMadeOffTheMainThread() {
        // P8-F1: making Updates reads files and settings; MainActivity's onCreate never does it on the main thread.
        val builtOn = AtomicReference<Thread?>(null)
        app.updatesFactory = {
            builtOn.set(Thread.currentThread())
            releases.updates(app, keys)
        }
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertTrue("the launch check asked for latest.json", releases.listAsked.await(10, TimeUnit.SECONDS))
        val thread = builtOn.get()
        assertNotNull(thread)
        assertNotSame(Looper.getMainLooper().thread, thread)
        // The screens' view follows the one Updates once it is made.
        assertSame(app.updates, app.updateControls.builtOrNull)
        runBlocking { withTimeout(10_000) { app.updateControls.state.first { it.offered } } }
        assertEquals(1, app.launchChecks.get())
        activity.pause().stop().destroy()
    }

    @Test
    fun aTapBeforeTheCheckIsMadeWaitsForIt() {
        // P8-F1: until Updates is made the screens see nothing to show; a tap meanwhile reaches it once it is.
        val gate = CountDownLatch(1)
        val controls = DeferredUpdates(
            build = {
                gate.await(10, TimeUnit.SECONDS)
                releases.updates(app, keys)
            },
            main = Dispatchers.Unconfined,
        )
        assertFalse(controls.state.value.offered)
        assertNull(controls.state.value.unavailableReason)
        assertNull(controls.builtOrNull)
        controls.setAutomatic(false)
        gate.countDown()
        val seen = runBlocking { withTimeout(10_000) { controls.state.first { it.offered && !it.automatic } } }
        assertFalse(seen.automatic)
        assertFalse(checkNotNull(controls.builtOrNull).state.value.automatic)
    }

    @Test
    fun comingBackWithInstallsAllowedClearsTheBanner() {
        app.updatesFactory = { releases.updates(app, keys) }
        val updates = app.updates
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        runBlocking {
            updates.check(manual = true)
            updates.installOffer()
        }
        assertTrue(updates.state.value.needsPermission)
        // Back without allowing it: the banner stays.
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        assertTrue(updates.state.value.needsPermission)
        activity.pause()
        // Back from Android's page with "install unknown apps" allowed: the banner goes; nothing is downloaded.
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        activity.resume()
        assertFalse(updates.state.value.needsPermission)
        assertEquals(0, releases.assetRequests())
        activity.pause().stop().destroy()
    }
}
