package com.naeblis11.mealplanner.desktop.server

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** P4-R6: MEAL_PLANNER_API_TOKEN, read once, made by Settings' Create a token, never shown in a log or a toString. */
class ApiTokenTest {
    private val dir: File = Files.createTempDirectory("mp-token").toFile()
    private val file = File(dir, ".env")

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun noTokenMeansNotConfigured() {
        assertFalse(ApiToken(SecretsFile(file)).configured)
        file.writeText("MEAL_PLANNER_API_TOKEN=\n")
        val empty = ApiToken(SecretsFile(file))
        assertFalse(empty.configured)
        assertNull(empty.value)
    }

    @Test
    fun anExistingTokenIsRead() {
        file.writeText("MEAL_PLANNER_SECRET_KEY=abc\nMEAL_PLANNER_API_TOKEN=from-python-server\n")
        val token = ApiToken(SecretsFile(file))
        assertTrue(token.configured)
        assertTrue("the token read from the file is not the one in it", token.value == "from-python-server")
    }

    @Test
    fun oneMatchingPairOfQuotesIsTakenOff() {
        // python-dotenv's reading: KEY="value" and KEY='value' are the value; only a matching pair, and only one.
        val cases = mapOf(
            "\"abcdefghijklmnop\"" to "abcdefghijklmnop",
            "'abcdefghijklmnop'" to "abcdefghijklmnop",
            "\"\"abcdefghijklmnop\"\"" to "\"abcdefghijklmnop\"",
            "\"abcdefghijklmnop'" to "\"abcdefghijklmnop'",
            "abcdefghijklmnop\"" to "abcdefghijklmnop\"",
        )
        for ((written, expected) in cases) {
            file.writeText("MEAL_PLANNER_API_TOKEN=$written\n")
            val token = ApiToken(SecretsFile(file))
            // Messages say the case's length only, so a failure never prints a token.
            assertTrue("case of length ${written.length} is not configured", token.configured)
            assertTrue("case of length ${written.length} read wrong", token.value == expected)
        }
    }

    @Test
    fun aTokenShorterThan16CharactersIsNotConfigured() {
        val logs = mutableListOf<String>()
        for (written in listOf("abcdefghijklmno", "'abcdefghijklmno'", "\"\"", "'a'")) {
            file.writeText("MEAL_PLANNER_API_TOKEN=$written\n")
            val token = ApiToken(SecretsFile(file), log = { logs += it })
            assertFalse("case of length ${written.length} is configured", token.configured)
            assertNull("case of length ${written.length} is in use", token.value)
        }
        assertTrue(logs.isNotEmpty())
        assertTrue("a log line shows the token", logs.none { "abcdefghijklmno" in it })
        file.writeText("MEAL_PLANNER_API_TOKEN=abcdefghijklmnop\n")
        assertTrue("16 characters is not configured", ApiToken(SecretsFile(file)).configured)
    }

    @Test
    fun createWritesA64DigitHexTokenAndKeepsTheRest() {
        file.writeText("MEAL_PLANNER_PASSWORD_HASH=scrypt-hash\n")
        val token = ApiToken(SecretsFile(file))
        val made = token.create()
        // Messages say the length only, so a failure never prints the token.
        assertTrue("token is not 64 hex digits (length ${made.length})", Regex("[0-9a-f]{64}").matches(made))
        assertTrue("token in use is not the one made", token.value == made)
        val saved = SecretsFile(file).read()
        assertEquals(setOf("MEAL_PLANNER_PASSWORD_HASH", ApiToken.KEY), saved.keys)
        assertEquals("scrypt-hash", saved["MEAL_PLANNER_PASSWORD_HASH"])
        assertTrue("token saved is not the one made", saved[ApiToken.KEY] == made)
        assertTrue("token read back is not the one made", ApiToken(SecretsFile(file)).value == made)
    }

    @Test
    fun aTokenThatCantBeSavedLeavesNoneInUse() {
        val blocker = File(dir, "not-a-folder").apply { writeText("a file where the folder should be") }
        val token = ApiToken(SecretsFile(File(blocker, ".env")), log = {})
        assertThrows(IOException::class.java) { token.create() }
        assertNull(token.value)
    }

    @Test
    fun twoCreatesAtOnceTakeTurns() {
        // A double click on Create a token: the second waits for the first, so the file and the token in use agree.
        // The first create is held inside its random bytes by a latch; the second then either blocks on create's lock
        // or, were it let in, reaches the random bytes itself. Whichever happens is waited for, not timed.
        val entries = AtomicInteger()
        val firstIn = CountDownLatch(1)
        val letFirstGo = CountDownLatch(1)
        val random = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) {
                if (entries.incrementAndGet() == 1) {
                    firstIn.countDown()
                    letFirstGo.await(5, TimeUnit.SECONDS)
                }
                super.nextBytes(bytes)
            }
        }
        val token = ApiToken(SecretsFile(file), random)
        val first = thread { token.create() }
        var second: Thread? = null
        try {
            assertTrue(firstIn.await(5, TimeUnit.SECONDS))
            val waiting = thread { token.create() }
            second = waiting
            eventually { waiting.state == Thread.State.BLOCKED || entries.get() > 1 }
            assertEquals("the second create didn't wait for the first", 1, entries.get())
        } finally {
            letFirstGo.countDown()
            first.join(5_000)
            second?.join(5_000)
        }
        assertEquals(2, entries.get())
        assertTrue("the token in use is not the one saved", SecretsFile(file).read()[ApiToken.KEY] == token.value)
    }

    @Test
    fun toStringNeverShowsTheToken() {
        val token = ApiToken(SecretsFile(file))
        val made = token.create()
        assertFalse("toString shows the token", token.toString().contains(made))
        assertEquals("ApiToken(configured=true)", token.toString())
    }
}
