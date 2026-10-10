package com.naeblis11.mealplanner.desktop.google

import com.naeblis11.mealplanner.calendar.SignInCancelledException
import java.io.IOException
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** P5-R4: OAuth for an installed app, with the answer on 127.0.0.1 and PKCE, against the fake Google. */
class GoogleSignInTest {
    private val fake = FakeGoogle()

    @After
    fun tearDown() = fake.close()

    private fun signIn(browse: (URI) -> Unit = fake.browser(), timeout: Long = 5_000) =
        GoogleSignIn(fake.http(), fake.endpoints, browse, timeoutMillis = timeout)

    private fun asked(url: String) = FakeGoogle.form(URI(url).rawQuery)

    @Test
    fun allowingInTheBrowserGivesTheRefreshToken() {
        var shown: String? = null
        assertEquals(FakeGoogle.REFRESH, signIn().run(fake.client) { shown = it })
        assertTrue(shown!!.startsWith(fake.endpoints.auth + "?"))
    }

    @Test
    fun itAsksGoogleForTheRightThings() {
        var shown = ""
        signIn().run(fake.client) { shown = it }
        val asked = asked(shown)
        assertEquals(fake.clientId, asked["client_id"])
        assertTrue(asked["redirect_uri"]!!, asked.getValue("redirect_uri").matches(Regex("http://127\\.0\\.0\\.1:[0-9]+/")))
        assertEquals("code", asked["response_type"])
        assertEquals("https://www.googleapis.com/auth/calendar.events https://www.googleapis.com/auth/calendar.calendarlist.readonly", asked["scope"])
        assertEquals(GoogleSignIn.SCOPES, asked["scope"])
        assertEquals("offline", asked["access_type"])
        assertEquals("consent", asked["prompt"])
        assertEquals("S256", asked["code_challenge_method"])
        assertTrue(asked.getValue("state").length >= 16)
    }

    @Test
    fun theCodeIsExchangedWithTheVerifierOfTheChallengeSent() {
        var shown = ""
        signIn().run(fake.client) { shown = it }
        val exchange = FakeGoogle.form(fake.requests.single { it.path == "/token" }.body)
        assertEquals("authorization_code", exchange["grant_type"])
        assertEquals(FakeGoogle.CODE, exchange["code"])
        assertEquals(asked(shown)["code_challenge"], FakeGoogle.challengeOf(exchange.getValue("code_verifier")))
        assertEquals(asked(shown)["redirect_uri"], exchange["redirect_uri"])
        assertEquals(fake.clientSecret, exchange["client_secret"])
    }

    @Test
    fun anAnswerWithoutThisSignInsStateIsIgnoredAndTheWaitGoesOn() {
        val browser = fake.browser()
        val flow = signIn(browse = { uri ->
            val back = asked(uri.toString()).getValue("redirect_uri")
            // A stray local request, even one that refuses, changes nothing without this sign-in's state.
            assertEquals(404, fake.get(back + "?error=access_denied&state=someone-else"))
            assertEquals(404, fake.get(back + "?code=forged"))
            browser(uri)
        })
        assertEquals(FakeGoogle.REFRESH, flow.run(fake.client) {})
        assertEquals(FakeGoogle.CODE, FakeGoogle.form(fake.requests.single { it.path == "/token" }.body)["code"])
    }

    @Test
    fun aRefusedConsentSaysSo() {
        val refused = assertThrows(GoogleSignInException::class.java) {
            signIn(fake.browser { it.remove("code"); it["error"] = "access_denied" }).run(fake.client) {}
        }
        assertEquals(GoogleSignIn.DENIED, refused.message)
    }

    @Test
    fun noRefreshTokenSaysHowToGetOne() {
        fake.giveRefreshToken = false
        val refused = assertThrows(GoogleSignInException::class.java) { signIn().run(fake.client) {} }
        assertEquals(GoogleSignIn.NO_REFRESH_TOKEN, refused.message)
    }

    @Test
    fun nobodyAllowingTimesOutAndTheAddressCloses() {
        var shown = ""
        val refused = assertThrows(GoogleSignInException::class.java) { signIn(browse = {}, timeout = 300).run(fake.client) { shown = it } }
        assertEquals(GoogleSignIn.TIMED_OUT, refused.message)
        assertThrows(IOException::class.java) { fake.get(asked(shown).getValue("redirect_uri") + "?code=late&state=x") }
    }

    @Test
    fun cancellingEndsTheWait() {
        val shown = CountDownLatch(1)
        val flow = signIn(browse = {})
        val ended = AtomicReference<Throwable?>()
        val waiting = thread {
            try {
                flow.run(fake.client) { shown.countDown() }
            } catch (t: Throwable) {
                ended.set(t)
            }
        }
        assertTrue(shown.await(5, TimeUnit.SECONDS))
        flow.cancel()
        waiting.join(5_000)
        assertTrue(ended.get().toString(), ended.get() is SignInCancelledException)
    }

    @Test
    fun aCancelThatArrivesBeforeTheAnswerAddressOpensStillEndsTheSignIn() {
        lateinit var flow: GoogleSignIn
        // The verifier is drawn before the answer address opens: a cancel then is one the old code lost.
        val cancelling = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) {
                flow.cancel()
                super.nextBytes(bytes)
            }
        }
        var browsed = false
        var shown = false
        flow = GoogleSignIn(fake.http(), fake.endpoints, { browsed = true }, cancelling, timeoutMillis = 5_000)
        assertThrows(SignInCancelledException::class.java) { flow.run(fake.client) { shown = true } }
        assertFalse(shown)
        assertFalse(browsed)
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun aCancelWithNoSignInRunningDoesNotEndTheNextOne() {
        val flow = signIn()
        flow.cancel()
        assertEquals(FakeGoogle.REFRESH, flow.run(fake.client) {})
    }

    @Test
    fun aSecondSignInWhileOneIsWaitingIsRefusedAndTheFirstGoesOn() {
        val shown = CountDownLatch(1)
        var first = ""
        val flow = signIn(browse = {})
        val ended = AtomicReference<Throwable?>()
        val waiting = thread {
            try {
                flow.run(fake.client) { first = it; shown.countDown() }
            } catch (t: Throwable) {
                ended.set(t)
            }
        }
        assertTrue(shown.await(5, TimeUnit.SECONDS))
        assertThrows(IllegalStateException::class.java) { flow.run(fake.client) {} }
        // The first is still waiting on its own address.
        assertEquals(404, fake.get(asked(first).getValue("redirect_uri") + "favicon.ico"))
        flow.cancel()
        waiting.join(5_000)
        assertTrue(ended.get().toString(), ended.get() is SignInCancelledException)
    }

    @Test
    fun noLocalAddressToAnswerOnSaysSoAndTheNextSignInCanRun() {
        var browsed = false
        var shown = false
        val flow = GoogleSignIn(
            fake.http(),
            fake.endpoints,
            { browsed = true },
            timeoutMillis = 5_000,
            bind = { throw java.net.BindException("Address already in use: bind") },
        )
        val refused = assertThrows(GoogleSignInException::class.java) { flow.run(fake.client) { shown = true } }
        assertEquals(GoogleSignIn.NO_LOOPBACK, refused.message)
        assertFalse(shown)
        assertFalse(browsed)
        assertTrue(fake.requests.isEmpty())
        // The failed run is over: it no longer counts as one waiting for the browser.
        assertThrows(GoogleSignInException::class.java) { flow.run(fake.client) {} }
    }

    @Test
    fun aStrayRequestDoesNotEndTheSignIn() {
        val browser = fake.browser()
        val flow = signIn(browse = { uri ->
            // Browsers ask for a favicon too; only Google's redirect is the answer.
            assertEquals(404, fake.get(asked(uri.toString()).getValue("redirect_uri") + "favicon.ico"))
            browser(uri)
        })
        assertEquals(FakeGoogle.REFRESH, flow.run(fake.client) {})
    }
}
