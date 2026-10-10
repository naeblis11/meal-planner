package com.naeblis11.mealplanner.desktop.google

import com.naeblis11.mealplanner.calendar.GoogleMessages
import com.naeblis11.mealplanner.calendar.SendOutcome
import com.naeblis11.mealplanner.calendar.SignInCancelledException
import com.naeblis11.mealplanner.data.GoogleEventEntity
import com.naeblis11.mealplanner.data.Household
import com.naeblis11.mealplanner.desktop.DesktopApp
import com.naeblis11.mealplanner.desktop.MapSettings
import com.naeblis11.mealplanner.desktop.google.FakeGoogle.Companion.FAMILY
import com.naeblis11.mealplanner.desktop.peers.FakeDiscovery
import com.naeblis11.mealplanner.desktop.peers.FakeNetwork
import com.naeblis11.mealplanner.desktop.peers.PeerMessages
import com.naeblis11.mealplanner.desktop.peers.PeerRecord
import com.naeblis11.mealplanner.desktop.peers.PeerWatch
import com.naeblis11.mealplanner.desktop.server.SecretsFile
import com.naeblis11.mealplanner.domain.GoogleEventIds
import com.naeblis11.mealplanner.settings.GoogleStatus
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The desktop's Google, end to end against the fake: sign in, choose, send, sign out and revoke. Everything lives in a
 * temp folder: the secrets file, the sealed token, the database and the settings.
 */
class GoogleAccountTest {
    private val dir: File = Files.createTempDirectory("mp-google-account").toFile()
    private val settings = MapSettings()
    private val app = DesktopApp(dir, settingsFactory = { settings })
    private val fake = FakeGoogle()
    private val secrets = SecretsFile(File(dir, ".env"))
    private val tokenFile = File(dir, GoogleTokenStore.FILE_NAME)
    private val logged = CopyOnWriteArrayList<String>()
    private val monday = LocalDate.of(2026, 9, 28)

    private fun account(
        browse: (URI) -> Unit = fake.browser(),
        builtIn: GoogleClient? = null,
        refusal: () -> String? = { null },
        look: suspend () -> Unit = {},
        household: (() -> Household)? = null,
    ) = GoogleAccount(
        secrets,
        GoogleTokenStore(tokenFile, FakeProtector(), log = { logged += it }),
        settings,
        database = { app.container.database },
        builtInClient = builtIn,
        http = fake.http(),
        endpoints = fake.endpoints,
        browse = browse,
        log = { logged += it },
        zone = { ZoneId.of("America/Toronto") },
        sendRefusal = refusal,
        lookFirst = look,
        household = household,
    )

    @Before
    fun setUp() {
        GoogleClients.save(secrets, fake.client)
        File(app.recipesDir, "soup.yaml").writeText(
            "recipe_name: Soup\ningredients:\n- Rice:\n    amounts:\n    - amount: 1\n      unit: cup\nsteps:\n- step: Cook.\n",
        )
        runBlocking { app.folder.sync() }
    }

    @After
    fun tearDown() {
        fake.close()
        app.close()
        dir.deleteRecursively()
    }

    private fun planSoup(servings: String? = null) = runBlocking {
        app.container.plans.assign(monday, "Dinner", app.container.recipes.allRecipes().single().id, servings)
    }

    private fun signedInToFamily(): GoogleAccount = account().also { account ->
        account.signIn {}
        account.choose(account.calendars().first { it.id == FAMILY })
    }

    @Test
    fun signingInKeepsTheTokenSealedAndFindsTheCalendars() {
        val account = account()
        var shown: String? = null
        account.signIn { shown = it }

        assertTrue(shown!!.startsWith(fake.endpoints.auth + "?"))
        assertTrue(tokenFile.isFile)
        assertFalse(String(tokenFile.readBytes(), Charsets.ISO_8859_1).contains(FakeGoogle.REFRESH))
        assertTrue(account.status.value.signedIn)
        assertEquals(listOf("Family", "me@example.com"), account.calendars().map { it.name })
        // "Signed in as" is the primary calendar's id: the account's address.
        assertEquals("me@example.com", account.status.value.account)
    }

    @Test
    fun aWeekGoesToTheChosenCalendarOnce() {
        val account = signedInToFamily()
        planSoup()

        assertEquals(1, (runBlocking { account.sendWeek(monday) } as SendOutcome.Sent).added)
        val household = runBlocking { Household(app.container.database).id() }
        val event = fake.events.getValue(FAMILY to GoogleEventIds.forMeal(household, monday, "Dinner"))
        assertEquals("Dinner: Soup", event["summary"]!!.jsonPrimitive.content)
        assertEquals("2026-09-28T18:00:00-04:00", event["start"]!!.jsonObject["dateTime"]!!.jsonPrimitive.content)

        val writes = fake.requests.count { it.method != "GET" && it.path.startsWith("/calendar/") }
        assertEquals(1, (runBlocking { account.sendWeek(monday) } as SendOutcome.Sent).unchanged)
        assertEquals(writes, fake.requests.count { it.method != "GET" && it.path.startsWith("/calendar/") })
    }

    @Test
    fun whileAnOlderHouseholdsPcIsOnTheNetworkTheSendIsRefusedAndNothingReachesGoogle() {
        var refusal: String? = PeerMessages.yieldGoogle("DEN")
        // Signing in still works while the send is refused.
        val account = account(refusal = { refusal })
        account.signIn {}
        account.choose(account.calendars().first { it.id == FAMILY })
        planSoup()
        val asked = fake.requests.size
        assertEquals(SendOutcome.Failed(PeerMessages.yieldGoogle("DEN")), runBlocking { account.sendWeek(monday) })
        assertEquals(asked, fake.requests.size)
        // The other PC has gone: the week goes.
        refusal = null
        assertEquals(1, (runBlocking { account.sendWeek(monday) } as SendOutcome.Sent).added)
    }

    @Test
    fun anOlderPcThatCameUpAfterStartIsFoundBeforeTheSendWithoutSettingsBeingOpened() {
        // P6-FR1: this PC looked at start, before the older one was on the network, and Settings was never opened.
        // The look before the send finds it, as Main wires the two: the send is refused and nothing reaches Google.
        val network = FakeNetwork()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val me = PeerRecord("THIS-PC", "5".repeat(16), 500L, "%032x".format(0), "1.0")
        val older = PeerRecord("DEN", "9".repeat(16), 100L, "%032x".format(1), "1.0")
        val discovery = FakeDiscovery(network)
        val watch = PeerWatch(discovery, own = { me }, port = 5055, log = {})
        try {
            runBlocking { watch.start(scope).join() }
            assertNull(watch.yieldTo())
            network.add(older)
            val account = account(refusal = { watch.yieldTo()?.let(PeerMessages::yieldGoogle) }, look = watch::lookNow)
            account.signIn {}
            account.choose(account.calendars().first { it.id == FAMILY })
            planSoup()
            val asked = fake.requests.size
            assertEquals(SendOutcome.Failed(PeerMessages.yieldGoogle("DEN")), runBlocking { account.sendWeek(monday) })
            assertEquals(asked, fake.requests.size)
            assertEquals(2, discovery.browses.get())
        } finally {
            watch.close()
            scope.cancel()
        }
    }

    @Test
    fun anOldDatabaseWithoutAnIdSentBeforeStartupDatesItStillDatesFromItsFile() {
        // P6-R4: one Household for the whole app. A send that makes the id before startup has dated the household
        // must date it as startup would (from the database file, here a fixed time), not from now.
        val fileTime = 1_600_000_000_000L
        val household = Household(app.container.database, clock = { 1_800_000_000_000L }, createdFallback = { fileTime })
        val meta = app.container.database.appMetaDao()
        assertNull(runBlocking { meta.get(Household.KEY) })
        val account = account(household = { household })
        account.signIn {}
        account.choose(account.calendars().first { it.id == FAMILY })
        planSoup()
        assertEquals(1, (runBlocking { account.sendWeek(monday) } as SendOutcome.Sent).added)
        assertEquals(fileTime.toString(), runBlocking { meta.get(Household.CREATED_KEY) })
        assertEquals(fileTime, runBlocking { household.created() })
    }

    @Test
    fun withoutASignInTheSendAsksForOne() {
        val account = account()
        assertFalse(account.status.value.signedIn)
        assertEquals(SendOutcome.NeedsSettings(GoogleMessages.SIGN_IN), runBlocking { account.sendWeek(monday) })
    }

    @Test
    fun accessGoogleNoLongerHonoursAsksToSignInAgain() {
        val account = signedInToFamily()
        planSoup()
        fake.revokeAll()
        assertEquals(SendOutcome.NeedsSettings(GoogleMessages.SIGN_IN_AGAIN), runBlocking { account.sendWeek(monday) })
    }

    @Test
    fun signingOutForgetsOnThisPcOnly() {
        val account = signedInToFamily()
        account.signOut()

        // S1: Google isn't told, so the old server (or the Pi) using this client keeps its link.
        assertTrue(fake.requests.none { it.path == "/revoke" })
        assertTrue(FakeGoogle.REFRESH in fake.refreshTokens)
        assertFalse(tokenFile.exists())
        assertEquals(GoogleStatus(clientReady = true, signedIn = false, clientId = fake.clientId), account.status.value)
    }

    @Test
    fun revokingAccessTellsGoogleThenForgets() {
        val account = signedInToFamily()
        account.revokeAccess()

        val revoke = fake.requests.single { it.path == "/revoke" }
        assertEquals(FakeGoogle.REFRESH, FakeGoogle.form(revoke.body)["token"])
        assertFalse(FakeGoogle.REFRESH in fake.refreshTokens)
        assertFalse(tokenFile.exists())
        assertFalse(account.status.value.signedIn)
    }

    @Test
    fun aClientAlreadySetIsNeverOverwritten() {
        val before = secrets.file.readBytes()
        val account = account()
        val refused = assertThrows(IllegalArgumentException::class.java) {
            account.useClientFile("{\"installed\":{\"client_id\":\"other.apps.googleusercontent.com\",\"client_secret\":\"other\"}}")
        }
        assertEquals(GoogleAccount.CLIENT_ALREADY_SET, refused.message)
        assertArrayEquals(before, secrets.file.readBytes())
        assertEquals(fake.clientId, account.status.value.clientId)
    }

    @Test
    fun aHalfWrittenClientIsReplacedByTheFilesPair() {
        // P5-T5a: an id without its secret (or a secret without its id) can't refresh any token, so nothing depends on
        // it; refusing the file would leave no way to sign in. The file's pair replaces both, and nothing else changes.
        for (half in listOf(GoogleClients.ID_KEY to "half.apps.googleusercontent.com", GoogleClients.SECRET_KEY to "half-secret")) {
            secrets.file.writeText("# Meal Planner secrets\nMEAL_PLANNER_API_TOKEN=0123456789abcdef0123\n${half.first}=${half.second}\n")
            val account = account()
            assertFalse(account.status.value.clientReady)

            account.useClientFile("{\"installed\":{\"client_id\":\"${fake.clientId}\",\"client_secret\":\"${fake.clientSecret}\"}}")

            assertEquals(fake.client, GoogleClients.fromSecrets(secrets))
            assertTrue(account.status.value.clientReady)
            assertEquals(fake.clientId, account.status.value.clientId)
            val values = secrets.read()
            assertEquals(setOf("MEAL_PLANNER_API_TOKEN", GoogleClients.ID_KEY, GoogleClients.SECRET_KEY), values.keys)
            assertEquals("0123456789abcdef0123", values["MEAL_PLANNER_API_TOKEN"])
            assertTrue(secrets.file.readText().startsWith("# Meal Planner secrets\nMEAL_PLANNER_API_TOKEN=0123456789abcdef0123\n"))
        }
    }

    @Test
    fun aRecordedEventIsUpdatedUnderItsRecordedIdNotSentAgain() {
        // Monday's dinner is recorded in google_event under an id that isn't this household's, with a stale hash.
        val account = signedInToFamily()
        runBlocking { app.container.database.googleEventDao().record(GoogleEventEntity(FAMILY, "2026-09-28", "Dinner", "recorded1", "other-hash")) }
        fake.events[FAMILY to "recorded1"] = buildJsonObject {
            put("id", "recorded1")
            put("summary", "Dinner: Soup")
            put("status", "confirmed")
        }
        planSoup()

        assertEquals(1, (runBlocking { account.sendWeek(monday) } as SendOutcome.Sent).updated)
        val calendarCalls = fake.requests.filter { it.path.contains("/events") }
        assertEquals(listOf("PATCH"), calendarCalls.map { it.method })
        assertTrue(calendarCalls.single().path.endsWith("/recorded1"))
        assertEquals(1, fake.events.size)
    }

    @Test
    fun aSignInInTheSecretsFileIsNeverTakenOver() {
        // The Python server's own sign-in, under its keys: the desktop signs in for itself and never reads these.
        secrets.put("MEAL_PLANNER_GCAL_REFRESH_TOKEN", FakeGoogle.REFRESH)
        secrets.put("MEAL_PLANNER_GCAL_ID", FAMILY)
        val before = secrets.file.readBytes()

        val account = account(browse = {})

        assertFalse(account.status.value.signedIn)
        assertNull(account.status.value.calendar)
        assertFalse(tokenFile.exists())
        assertEquals(SendOutcome.NeedsSettings(GoogleMessages.SIGN_IN), runBlocking { account.sendWeek(monday) })
        assertTrue(fake.requests.isEmpty())
        assertArrayEquals(before, secrets.file.readBytes())
    }

    @Test
    fun aClientFileIsKeptForSignIn() {
        secrets.file.delete()
        val account = account()
        assertFalse(account.status.value.clientReady)
        assertEquals(GoogleAccount.NO_CLIENT, assertThrows(GoogleSignInException::class.java) { account.signIn {} }.message)

        account.useClientFile("{\"installed\":{\"client_id\":\"${fake.clientId}\",\"client_secret\":\"${fake.clientSecret}\"}}")
        assertTrue(account.status.value.clientReady)
        assertEquals(fake.client, GoogleClients.fromSecrets(secrets))
        account.signIn {}
        assertTrue(account.status.value.signedIn)
    }

    @Test
    fun withOnlyTheBuiltInClientANewInstallJustSignsInChoosesAndSends() {
        // Task 13: no .env client at all, as on a new PC. Nothing is written to the secrets file along the way.
        secrets.file.delete()
        val account = account(builtIn = fake.client)
        // Ready to sign in, so Settings offers Sign in with Google and no file picker; the built-in id isn't shown.
        assertEquals(GoogleStatus(clientReady = true, signedIn = false, clientId = null), account.status.value)

        account.signIn {}
        account.choose(account.calendars().first { it.id == FAMILY })
        planSoup()

        assertEquals(1, (runBlocking { account.sendWeek(monday) } as SendOutcome.Sent).added)
        assertEquals(1, fake.events.size)
        assertTrue(fake.requests.filter { "client_id=" in it.body }.all { FakeGoogle.form(it.body)["client_id"] == fake.clientId })
        assertFalse(secrets.file.exists())
        assertNull(account.status.value.clientId)
    }

    @Test
    fun theSecretsFilesClientWinsOverTheBuiltInOne() {
        // setUp saved the fake's client in the .env; FakeGoogle refuses any other client, so a sign-in proves which was used.
        val account = account(builtIn = GoogleClient("built.apps.googleusercontent.com", "built-secret"))
        assertEquals(fake.clientId, account.status.value.clientId)

        account.signIn {}
        account.calendars()

        assertTrue(account.status.value.signedIn)
        val clients = fake.requests.filter { "client_id=" in it.body }.map { FakeGoogle.form(it.body)["client_id"] }
        assertTrue(clients.toString(), clients.isNotEmpty() && clients.all { it == fake.clientId })
    }

    @Test
    fun withNeitherClientNothingIsReady() {
        secrets.file.delete()
        val account = account(builtIn = null)
        assertEquals(GoogleStatus(clientReady = false, signedIn = false), account.status.value)
        assertEquals(GoogleAccount.NO_CLIENT, assertThrows(GoogleSignInException::class.java) { account.signIn {} }.message)
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun aHandPickedClientFileOverridesTheBuiltInOne() {
        // The built-in client isn't in the .env, so the file is taken (P5-T5a refuses only a complete .env pair).
        secrets.file.delete()
        val account = account(builtIn = GoogleClient("built.apps.googleusercontent.com", "built-secret"))
        assertNull(account.status.value.clientId)

        account.useClientFile("{\"installed\":{\"client_id\":\"${fake.clientId}\",\"client_secret\":\"${fake.clientSecret}\"}}")

        assertEquals(fake.client, GoogleClients.fromSecrets(secrets))
        assertEquals(fake.clientId, account.status.value.clientId)
        account.signIn {}
        assertTrue(account.status.value.signedIn)
    }

    @Test
    fun aSignInCountsAsAPickAndKeepsTheCalendar() {
        // P5-T6a: the Calendar's "Sign in to Google again" banner goes on a fresh sign-in, with no calendar chosen again.
        val account = signedInToFamily()
        val picks = account.picks.value
        account.signIn {}
        assertEquals(picks + 1, account.picks.value)
        assertEquals(FAMILY, account.status.value.calendar!!.id)
        // One that fails counts for nothing.
        fake.giveRefreshToken = false
        assertThrows(IOException::class.java) { account.signIn {} }
        assertEquals(picks + 1, account.picks.value)
    }

    @Test
    fun aCancelledSignInKeepsNothing() {
        val account = account(browse = {})
        val shown = CountDownLatch(1)
        val ended = AtomicReference<Throwable?>()
        val waiting = thread {
            try {
                account.signIn { shown.countDown() }
            } catch (t: Throwable) {
                ended.set(t)
            }
        }
        assertTrue(shown.await(5, TimeUnit.SECONDS))
        account.cancelSignIn()
        waiting.join(5_000)
        assertTrue(ended.get() is SignInCancelledException)
        assertFalse(tokenFile.exists())
        assertFalse(account.status.value.signedIn)
    }

    @Test
    fun aSignInWhileAnotherWaitsStopsThatOneAndAsksToTryAgain() {
        // Defence in depth: a sign-in left waiting (its screen gone without a cancel) must not block every later one.
        var follow = false
        val browser = fake.browser()
        val account = account(browse = { uri -> if (follow) browser(uri) })
        val shown = CountDownLatch(1)
        val ended = AtomicReference<Throwable?>()
        val waiting = thread {
            try {
                account.signIn { shown.countDown() }
            } catch (t: Throwable) {
                ended.set(t)
            }
        }
        assertTrue(shown.await(5, TimeUnit.SECONDS))

        val refused = assertThrows(GoogleSignInException::class.java) { account.signIn {} }

        assertEquals(GoogleAccount.SIGN_IN_WAS_WAITING, refused.message)
        waiting.join(5_000)
        assertTrue(ended.get() is SignInCancelledException)
        assertFalse(tokenFile.exists())
        // The next one works.
        follow = true
        account.signIn {}
        assertTrue(account.status.value.signedIn)
    }

    @Test
    fun aSignInThatCantBeReadIsNotTakenAsRevoked() {
        // P5-T5b: without the token nothing can be revoked at Google, and the old server's (or the Pi's) shared grant
        // would stay live while the user believed it gone. So nothing changes, and the user is told where to remove it.
        val account = signedInToFamily()
        tokenFile.writeText("not sealed here")
        val before = tokenFile.readBytes()
        val status = account.status.value

        val refused = assertThrows(IOException::class.java) { account.revokeAccess() }

        assertEquals(GoogleAccount.REVOKE_UNREADABLE, refused.message)
        assertTrue(fake.requests.none { it.path == "/revoke" })
        assertArrayEquals(before, tokenFile.readBytes())
        assertEquals(status, account.status.value)
        assertTrue(status.signedIn)
    }

    @Test
    fun aRevokeGoogleRefusesOrNeverHearsChangesNothing() {
        val account = signedInToFamily()
        val before = tokenFile.readBytes()
        val status = account.status.value
        for (failure in listOf({ fake.revokeStatus = 500 }, { fake.revokeStatus = 200; fake.dropRevoke = true })) {
            failure()
            val refused = assertThrows(IOException::class.java) { account.revokeAccess() }
            assertEquals(GoogleAccount.REVOKE_FAILED, refused.message)
            assertArrayEquals(before, tokenFile.readBytes())
            assertEquals(status, account.status.value)
            assertTrue(FakeGoogle.REFRESH in fake.refreshTokens)
        }
        assertEquals(2, fake.requests.count { it.path == "/revoke" })
    }

    @Test
    fun aSignOutWhileTheCalendarsAreListedLeavesNoSignedInAs() {
        val account = signedInToFamily()
        settings.put(mapOf(GoogleAccount.ACCOUNT_KEY to ""))
        fake.onCalendarList = {
            fake.onCalendarList = {}
            account.signOut()
        }

        account.calendars()

        assertFalse(account.status.value.signedIn)
        assertNull(account.status.value.account)
        assertEquals("", settings.getString(GoogleAccount.ACCOUNT_KEY))
    }

    @Test
    fun calendarsGoogleWontListAreSaidAndLoggedByKindOnly() {
        val account = signedInToFamily()
        fake.calendarListStatus = 500

        val refused = assertThrows(GoogleSignInException::class.java) { account.calendars() }

        assertEquals(GoogleAccount.CANT_LIST, refused.message)
        assertTrue(logged.toString(), logged.any { it.contains("calendars") && it.contains("GoogleApiException") })
    }

    @Test
    fun noTokenOrSecretIsEverLoggedOrSaid() = assertNothingSecretIsSaid(account())

    @Test
    fun theBuiltInClientsSecretIsNeverLoggedOrSaidEither() {
        secrets.file.delete()
        assertNothingSecretIsSaid(account(builtIn = fake.client))
    }

    private fun assertNothingSecretIsSaid(account: GoogleAccount) {
        val said = mutableListOf<String>()
        // A sign-in through the browser: the authorization code, the PKCE verifier and the tokens pass through here.
        account.signIn { said += it }
        account.choose(account.calendars().first { it.id == FAMILY })
        said += account.toString()
        planSoup()
        runBlocking { account.sendWeek(monday) }
        fake.revokeAll()
        // A changed meal, so the send has to write, and finds the sign-in refused.
        planSoup("2")
        said += (runBlocking { account.sendWeek(monday) } as SendOutcome.NeedsSettings).text
        said += assertThrows(GoogleSignInException::class.java) { account.calendars() }.message!!
        account.signOut()
        // An unreadable token file is logged by name of the problem only.
        tokenFile.writeText(FakeGoogle.REFRESH)
        said += (runBlocking { account.sendWeek(monday) } as SendOutcome.NeedsSettings).text
        said += assertThrows(IOException::class.java) { account.revokeAccess() }.message!!
        said += account.toString()
        said += account.status.value.toString()
        // The token store's own lines are among those checked.
        assertTrue(logged.toString(), logged.any { it.contains("can't be unsealed") })
        val verifier = fake.requests.filter { "code_verifier=" in it.body }.map { FakeGoogle.form(it.body).getValue("code_verifier") }.single()
        for (text in said + logged) {
            for (secret in listOf(FakeGoogle.REFRESH, "access-", fake.clientSecret, FakeGoogle.CODE, verifier)) {
                assertFalse("'$text' gives away a secret", text.contains(secret))
            }
        }
        assertNull(logged.firstOrNull { it.contains("Bearer") })
    }
}
