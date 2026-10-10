package com.naeblis11.mealplanner.update

import java.io.File
import java.nio.file.Files
import java.security.PublicKey
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec "Updates", the check's cases: newer, same, older; bad, missing and wrong-key signatures; a timeout; once a day; the switch. */
class UpdatesCheckTest {
    private val releases = FakeReleases()
    private val fixture = ReleaseFixture(releases)
    private val dir: File = Files.createTempDirectory("mp-updates-check").toFile()
    private val settings = MemorySettings()
    private val installer = FakeInstaller()
    private var now = START

    @After
    fun tearDown() {
        releases.close()
        dir.deleteRecursively()
    }

    private fun updates(
        running: RunningApp = RunningApp.Desktop("1.0.0"),
        key: PublicKey? = fixture.keys.public,
        unavailable: String? = null,
        readTimeoutMillis: Int = 2_000,
    ) = Updates(running, key, installer, settings, File(dir, "updates"), releases.http(readTimeoutMillis), releases.endpoints, unavailable, clock = { now })

    @Test
    fun aNewerVersionIsOffered() = runBlocking {
        fixture.publish(desktop = "1.0.1")
        val updates = updates()
        updates.check(manual = true)
        val state = updates.state.value
        assertEquals(UpdateOffer("1.0.1", "release-2", "MealPlanner-1.0.1.msi", fixture.msi.size.toLong(), Sha256.of(fixture.msi)), state.offer)
        assertEquals(START, state.lastChecked)
        assertEquals(START, settings.getLong(Updates.LAST_CHECKED))
        assertNull(state.message)
        assertFalse(state.upToDate)
        assertEquals(UpdatePhase.IDLE, state.phase)
    }

    @Test
    fun theSameVersionIsUpToDate() = runBlocking {
        fixture.publish(desktop = "1.0.0")
        val updates = updates()
        updates.check(manual = true)
        assertNull(updates.state.value.offer)
        assertTrue(updates.state.value.upToDate)
        assertEquals(START, updates.state.value.lastChecked)
    }

    @Test
    fun anOlderVersionIsNotOffered() = runBlocking {
        fixture.publish(desktop = "1.1.9")
        val updates = updates(RunningApp.Desktop("1.2.0"))
        updates.check(manual = true)
        assertNull(updates.state.value.offer)
        assertTrue(updates.state.value.upToDate)
    }

    @Test
    fun thePhoneComparesItsVersionCode() = runBlocking {
        fixture.publish(androidCode = 3, androidName = "1.0.0")
        val phone = updates(RunningApp.Android(versionCode = 2, versionName = "1.0.0"))
        phone.check(manual = true)
        assertEquals("1.0.0", phone.state.value.offer?.version)
        assertEquals("MealPlanner-1.0.0.apk", phone.state.value.offer?.file)
        val current = updates(RunningApp.Android(versionCode = 3, versionName = "1.0.0"))
        current.check(manual = true)
        assertNull(current.state.value.offer)
    }

    @Test
    fun aChangedListIsIgnoredAndChangesNothing() = runBlocking {
        fixture.publish(desktop = "1.0.1")
        val updates = updates()
        updates.check(manual = true)
        val before = updates.state.value
        now += HOUR
        fixture.publish(desktop = "1.0.2", tamper = true)
        updates.check(manual = true)
        val after = updates.state.value
        assertEquals(UpdateMessages.NOT_VERIFIED, after.message)
        assertEquals(before.offer, after.offer)
        assertEquals(START, after.lastChecked)
        assertEquals(START, settings.getLong(Updates.LAST_CHECKED))
        assertNull(after.problem)
    }

    @Test
    fun aMissingSignatureIsNotVerified() = runBlocking {
        fixture.publish(signer = null)
        val updates = updates()
        updates.check(manual = true)
        assertEquals(UpdateMessages.NOT_VERIFIED, updates.state.value.message)
        assertNull(updates.state.value.offer)
        assertNull(updates.state.value.lastChecked)
    }

    @Test
    fun aListSignedWithAnotherKeyIsNotVerified() = runBlocking {
        fixture.publish(signer = TestKeys.pair().private)
        val updates = updates()
        updates.check(manual = true)
        assertEquals(UpdateMessages.NOT_VERIFIED, updates.state.value.message)
        assertNull(updates.state.value.offer)
    }

    @Test
    fun aReleasePublishedBetweenTheTwoFetchesIsFetchedAgain() = runBlocking {
        // P8-F1: the list comes from release-2, then `latest` moves to release-3 before the signature is asked for.
        fixture.publish(tag = "release-3", desktop = "1.0.2")
        fixture.publish(tag = "release-2", desktop = "1.0.1")
        val latest = "${FakeReleases.REPO}/releases/latest/download/"
        // Moved once `latest` has sent the list's request on to release-2 (its second hop), as a publish would.
        val listHop = "${FakeReleases.REPO}/releases/download/release-2/${Updates.MANIFEST}"
        releases.onRequest = { path -> if (path == listHop) releases.latestTag = "release-3" }
        val updates = updates()
        updates.check(manual = true)
        val state = updates.state.value
        assertNull(state.message)
        assertEquals("1.0.2", state.offer?.version)
        assertEquals("release-3", state.offer?.tag)
        assertEquals(
            listOf(latest + Updates.MANIFEST, latest + Updates.SIGNATURE, latest + Updates.MANIFEST, latest + Updates.SIGNATURE),
            releases.requests.filter { it.startsWith(latest) },
        )
    }

    @Test
    fun aSignatureThatDoesntVerifyIsFetchedAgainOnlyOnce() = runBlocking {
        fixture.publish(signer = TestKeys.pair().private)
        val updates = updates()
        updates.check(manual = true)
        assertEquals(UpdateMessages.NOT_VERIFIED, updates.state.value.message)
        assertNull(updates.state.value.offer)
        val latest = "${FakeReleases.REPO}/releases/latest/download/"
        assertEquals(2, releases.requests.count { it == latest + Updates.MANIFEST })
        assertEquals(2, releases.requests.count { it == latest + Updates.SIGNATURE })
    }

    @Test
    fun aReleaseWithoutAListSaysSoQuietly() = runBlocking {
        // P8-PF9: `latest` points at a release with no latest.json (GitHub answers 404).
        releases.latestTag = "android-v1.0.0"
        val updates = updates()
        updates.check(manual = true)
        val state = updates.state.value
        assertEquals(UpdateMessages.NO_LIST, state.message)
        assertNull(state.offer)
        assertNull(state.lastChecked)
        assertNull(state.problem)
        assertEquals(UpdatePhase.IDLE, state.phase)
    }

    @Test
    fun theListIsFetchedOnceAndThoseBytesAreVerified() = runBlocking {
        fixture.publish()
        val updates = updates()
        updates.check(manual = true)
        assertEquals("1.0.1", updates.state.value.offer?.version)
        val latest = "${FakeReleases.REPO}/releases/latest/download/"
        assertEquals(listOf(latest + Updates.MANIFEST, latest + Updates.SIGNATURE), releases.requests.filter { it.startsWith(latest) })
        assertEquals(1, releases.requests.count { it.endsWith("/" + Updates.MANIFEST) && it.startsWith("/assets/") })
    }

    @Test
    fun aSignatureOverItsCapIsNotVerified() = runBlocking {
        fixture.publish()
        releases.publish("release-2", Updates.SIGNATURE, ByteArray(ReleaseSignature.MAX_BYTES + 1) { 'A'.code.toByte() })
        val updates = updates()
        updates.check(manual = true)
        assertEquals(UpdateMessages.NOT_VERIFIED, updates.state.value.message)
        assertNull(updates.state.value.offer)
    }

    @Test
    fun aSlowGitHubIsSaidQuietly() = runBlocking {
        fixture.publish()
        releases.delayMillis = 3_000
        val updates = updates(readTimeoutMillis = 300)
        updates.check(manual = true)
        val state = updates.state.value
        assertEquals(UpdateMessages.CHECK_FAILED, state.message)
        assertNull(state.offer)
        assertNull(state.problem)
        assertEquals(UpdatePhase.IDLE, state.phase)
    }

    @Test
    fun theLaunchCheckRunsAtMostOnceADay() = runBlocking {
        fixture.publish()
        updates().checkAtLaunch()
        val first = releases.requests.size
        assertTrue(first > 0)
        // Another launch the same day: a new Updates over the same settings asks nothing.
        now += UpdateSchedule.DAY_MILLIS - 1
        updates().checkAtLaunch()
        assertEquals(first, releases.requests.size)
        now += 1
        updates().checkAtLaunch()
        assertTrue(releases.requests.size > first)
        // The button is never held back by the day's limit.
        val count = releases.requests.size
        updates().check(manual = true)
        assertTrue(releases.requests.size > count)
    }

    @Test
    fun switchedOffTheLaunchDoesntCheckButTheButtonDoes() = runBlocking {
        fixture.publish()
        val updates = updates()
        updates.setAutomatic(false)
        updates.checkAtLaunch()
        assertEquals(emptyList<String>(), releases.requests)
        assertEquals(Updates.OFF, settings.getString(Updates.AUTOMATIC))
        // Remembered: the next run starts with it off.
        assertFalse(updates().state.value.automatic)
        updates.check(manual = true)
        assertEquals("1.0.1", updates.state.value.offer?.version)
    }

    @Test
    fun aCopyThatDoesntCheckNeverContactsGitHub() = runBlocking {
        fixture.publish()
        val preview = updates(unavailable = "Only the installed app checks for updates.")
        preview.checkAtLaunch()
        preview.check(manual = true)
        val unsigned = updates(key = null)
        unsigned.checkAtLaunch()
        unsigned.check(manual = true)
        assertEquals(emptyList<String>(), releases.requests)
        assertFalse(preview.state.value.offered)
        assertEquals("Only the installed app checks for updates.", preview.state.value.unavailableReason)
        assertFalse(unsigned.state.value.offered)
        assertEquals(UpdateMessages.NO_KEY, unsigned.state.value.unavailableReason)
    }

    @Test
    fun aBuiltInKeyThatIsMissingMeansNoUpdate() = runBlocking {
        fixture.publish()
        val key = Updates.releaseKey { null }
        assertNull(key)
        val updates = updates(key = key)
        updates.checkAtLaunch()
        updates.check(manual = true)
        assertEquals(emptyList<String>(), releases.requests)
        assertEquals(UpdateMessages.NO_KEY, updates.state.value.unavailableReason)
        assertNull(updates.state.value.offer)
    }

    @Test
    fun aBuiltInKeyThatIsntP256MeansNoUpdate() = runBlocking {
        fixture.publish()
        val key = Updates.releaseKey { throw IllegalArgumentException("The release key isn't on the P-256 curve.") }
        assertNull(key)
        val updates = updates(key = key)
        updates.check(manual = true)
        assertEquals(emptyList<String>(), releases.requests)
        assertFalse(updates.state.value.offered)
    }

    @Test
    fun aSignatureCheckThatCantStartMeansNoUpdate() = runBlocking {
        // The Android CARRY: ReleaseSignature's object initialiser may fail on a device without the JDK's P-256 table.
        assertNull(Updates.releaseKey { throw ExceptionInInitializerError("no P-256") })
        assertNull(Updates.releaseKey { throw NoClassDefFoundError("ReleaseSignature") })
        fixture.publish()
        val list = releases.http().fetch(releases.endpoints.latest(Updates.MANIFEST), ReleaseManifest.MAX_BYTES)
        val signature = ReleaseSignature.sign(list, fixture.keys.private)
        assertTrue(Updates.verifies(list, signature, fixture.keys.public))
        assertFalse(Updates.verifies(list, signature, fixture.keys.public) { _, _, _ -> throw ExceptionInInitializerError("no P-256") })
        assertFalse(Updates.verifies(list, signature, fixture.keys.public) { _, _, _ -> throw NoClassDefFoundError("ReleaseSignature") })
        assertFalse(Updates.verifies(list, signature, fixture.keys.public) { _, _, _ -> throw IllegalStateException() })
    }

    @Test
    fun anUpdateFoundAtLaunchIsNoticedUntilPutAway() = runBlocking {
        fixture.publish()
        val updates = updates()
        updates.checkAtLaunch()
        assertTrue(updates.state.value.showsNotice)
        updates.dismissNotice()
        assertFalse(updates.state.value.showsNotice)
        // Found from Settings' button, it needs no notice: Settings already shows it.
        now += UpdateSchedule.DAY_MILLIS
        val fromSettings = updates()
        fromSettings.check(manual = true)
        assertFalse(fromSettings.state.value.showsNotice)
    }

    private companion object {
        const val START = 1_000_000_000_000L
        const val HOUR = 3_600_000L
    }
}
