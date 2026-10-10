package com.naeblis11.mealplanner.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateOfferTest {
    private val sha = "0123456789abcdef".repeat(4)
    private val list = ReleaseManifest(
        tag = "release-3",
        desktop = DesktopRelease("1.2.0", "MealPlanner-1.2.0.msi", 90_000_000, sha),
        android = AndroidRelease("1.2.0", 3, "MealPlanner-1.2.0.apk", 12_000_000, sha),
    )

    @Test
    fun aNewerWindowsVersionIsOffered() {
        assertEquals(
            UpdateOffer("1.2.0", "release-3", "MealPlanner-1.2.0.msi", 90_000_000, sha),
            list.offerFor(RunningApp.Desktop("1.1.9")),
        )
    }

    @Test
    fun theSameOrAnOlderWindowsVersionIsNot() {
        assertNull(list.offerFor(RunningApp.Desktop("1.2.0")))
        assertNull(list.offerFor(RunningApp.Desktop("1.10.0")))
        assertNull(list.offerFor(RunningApp.Desktop("dev")))
    }

    @Test
    fun thePhoneGoesByItsVersionCode() {
        assertEquals(
            UpdateOffer("1.2.0", "release-3", "MealPlanner-1.2.0.apk", 12_000_000, sha),
            list.offerFor(RunningApp.Android(versionCode = 2, versionName = "1.2.0")),
        )
        assertNull(list.offerFor(RunningApp.Android(versionCode = 3, versionName = "1.1.0")))
        assertNull(list.offerFor(RunningApp.Android(versionCode = 4, versionName = "1.3.0")))
    }

    @Test
    fun aListWithoutThisAppsEntryOffersNothing() {
        assertNull(list.copy(desktop = null).offerFor(RunningApp.Desktop("1.0.0")))
        assertNull(list.copy(android = null).offerFor(RunningApp.Android(1, "1.0.0")))
    }
}
