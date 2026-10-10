package com.naeblis11.mealplanner.desktop.google

import com.naeblis11.mealplanner.desktop.server.SecretsFile
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5-R4: the OAuth client is a hand-picked one (Google's downloaded client file, kept under the .env keys) or, failing
 * that, the one built into the app (Task 13).
 */
class GoogleClientsTest {
    private val dir: File = Files.createTempDirectory("mp-google-client").toFile()
    private val secrets = SecretsFile(File(dir, ".env"))

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun googlesDesktopClientFileIsRead() {
        val text = "{\"installed\":{\"client_id\":\"abc.apps.googleusercontent.com\",\"project_id\":\"meal\"," +
            "\"auth_uri\":\"https://accounts.google.com/o/oauth2/auth\",\"token_uri\":\"https://oauth2.googleapis.com/token\"," +
            "\"client_secret\":\"GOCSPX-xyz\",\"redirect_uris\":[\"http://localhost\"]}}"
        assertEquals(GoogleClient("abc.apps.googleusercontent.com", "GOCSPX-xyz"), GoogleClients.fromJson(text))
    }

    @Test
    fun anythingButADesktopClientIsRefusedWithWhatToDo() {
        for (text in listOf(
            "{\"web\":{\"client_id\":\"abc\",\"client_secret\":\"xyz\"}}",
            "{\"installed\":{\"client_id\":\"abc\"}}",
            "{\"installed\":{\"client_id\":\" \",\"client_secret\":\"xyz\"}}",
            "not json at all",
        )) {
            val refused = assertThrows(text, IllegalArgumentException::class.java) { GoogleClients.fromJson(text) }
            assertEquals(GoogleClients.BAD_FILE, refused.message)
        }
    }

    @Test
    fun aValueThatCouldAddALineToTheSecretsFileIsRefusedAndTheFileIsUntouched() {
        val before = "# Meal Planner secrets\nMEAL_PLANNER_API_TOKEN=0123456789abcdef0123\n".toByteArray(Charsets.UTF_8)
        secrets.file.writeBytes(before)
        // JSON escapes for a line feed, a carriage return, NEL (U+0085), LINE SEPARATOR (U+2028) and PARAGRAPH SEPARATOR
        // (U+2029), and a plain space; in the id or the secret, inside the value or at either end.
        for (bad in listOf("\\n", "\\r", " ", "\\u0085", "\\u2028", "\\u2029")) {
            for (text in listOf(
                "{\"installed\":{\"client_id\":\"abc${bad}MEAL_PLANNER_API_TOKEN=x\",\"client_secret\":\"xyz\"}}",
                "{\"installed\":{\"client_id\":\"abc\",\"client_secret\":\"xyz${bad}MEAL_PLANNER_API_TOKEN=x\"}}",
                "{\"installed\":{\"client_id\":\"${bad}abc\",\"client_secret\":\"xyz\"}}",
                "{\"installed\":{\"client_id\":\"abc\",\"client_secret\":\"xyz${bad}\"}}",
            )) {
                val refused = assertThrows(text, IllegalArgumentException::class.java) { GoogleClients.save(secrets, GoogleClients.fromJson(text)) }
                assertEquals(GoogleClients.BAD_FILE, refused.message)
                assertArrayEquals(text, before, secrets.file.readBytes())
            }
        }
    }

    @Test
    fun savingRefusesAValueThatCouldAddALineWhereverTheClientCameFrom() {
        val before = "MEAL_PLANNER_API_TOKEN=0123456789abcdef0123\n".toByteArray(Charsets.UTF_8)
        secrets.file.writeBytes(before)
        for (client in listOf(
            GoogleClient("abc\nMEAL_PLANNER_API_TOKEN=x", "xyz"),
            GoogleClient("abc", "xyz\rMEAL_PLANNER_API_TOKEN=x"),
            GoogleClient("abc def", "xyz"),
            GoogleClient("abc", "xyz\u0085"),
            GoogleClient("abc", ""),
        )) {
            assertThrows(client.toString(), IllegalArgumentException::class.java) { GoogleClients.save(secrets, client) }
            assertArrayEquals(client.toString(), before, secrets.file.readBytes())
        }
    }

    @Test
    fun theClientThePythonServerSavedIsUsed() {
        secrets.file.writeText("SECRET_KEY=abc\nMEAL_PLANNER_GCAL_CLIENT_ID=\"abc.apps.googleusercontent.com\"\nMEAL_PLANNER_GCAL_CLIENT_SECRET=GOCSPX-xyz\n")
        assertEquals(GoogleClient("abc.apps.googleusercontent.com", "GOCSPX-xyz"), GoogleClients.fromSecrets(secrets))
    }

    @Test
    fun withoutBothHalvesThereIsNoClient() {
        assertNull(GoogleClients.fromSecrets(secrets))
        secrets.file.writeText("MEAL_PLANNER_GCAL_CLIENT_ID=abc\n")
        assertNull(GoogleClients.fromSecrets(secrets))
    }

    @Test
    fun savingAClientKeepsEveryOtherLine() {
        secrets.file.writeText("# Meal Planner secrets\nMEAL_PLANNER_API_TOKEN=0123456789abcdef0123\n")
        val client = GoogleClient("abc.apps.googleusercontent.com", "GOCSPX-xyz")
        GoogleClients.save(secrets, client)
        val text = secrets.file.readText()
        assertTrue(text, text.startsWith("# Meal Planner secrets\nMEAL_PLANNER_API_TOKEN=0123456789abcdef0123\n"))
        assertEquals(client, GoogleClients.fromSecrets(secrets))
        assertFalse(client.toString().contains("GOCSPX-xyz"))
    }

    @Test
    fun theBuiltInClientIsReadFromTheResource() {
        val text = "{\"installed\":{\"client_id\":\"built.apps.googleusercontent.com\",\"client_secret\":\"built-secret\"}}"
        assertEquals(GoogleClient("built.apps.googleusercontent.com", "built-secret"), GoogleClients.builtIn(read = { text }, log = {}))
    }

    @Test
    fun aBuildWithoutAClientHasNoBuiltInOne() {
        val logged = mutableListOf<String>()
        assertNull(GoogleClients.builtIn(read = { null }, log = { logged += it }))
        assertEquals(emptyList<String>(), logged)
    }

    @Test
    fun aBadBuiltInClientIsRefusedAndLoggedWithoutItsValues() {
        for (text in listOf(
            "{\"installed\":{\"client_id\":\"built.apps.googleusercontent.com\"}}",
            "{\"installed\":{\"client_id\":\"built.apps\\nX=1\",\"client_secret\":\"built-secret\"}}",
            "{\"web\":{\"client_id\":\"built.apps.googleusercontent.com\",\"client_secret\":\"built-secret\"}}",
            "not json built-secret",
        )) {
            val logged = mutableListOf<String>()
            assertNull(text, GoogleClients.builtIn(read = { text }, log = { logged += it }))
            assertEquals(text, 1, logged.size)
            assertFalse(logged.single(), logged.single().contains("built-secret") || logged.single().contains("built.apps"))
        }
        // A resource that can't be read is no client either, and its error's text isn't repeated.
        val logged = mutableListOf<String>()
        assertNull(GoogleClients.builtIn(read = { throw IOException("built-secret") }, log = { logged += it }))
        assertEquals(1, logged.size)
        assertFalse(logged.single(), logged.single().contains("built-secret"))
    }

    @Test
    fun theSecretsFilesClientWinsThenTheBuiltInOneThenNone() {
        val builtIn = GoogleClient("built.apps.googleusercontent.com", "built-secret")
        assertEquals(builtIn, GoogleClients.inUse(secrets, builtIn))
        assertNull(GoogleClients.inUse(secrets, null))
        // A half-written client in the .env can refresh nothing: the built-in one is used.
        secrets.file.writeText("MEAL_PLANNER_GCAL_CLIENT_ID=half\n")
        assertEquals(builtIn, GoogleClients.inUse(secrets, builtIn))
        secrets.file.writeText("MEAL_PLANNER_GCAL_CLIENT_ID=abc.apps.googleusercontent.com\nMEAL_PLANNER_GCAL_CLIENT_SECRET=GOCSPX-xyz\n")
        assertEquals(GoogleClient("abc.apps.googleusercontent.com", "GOCSPX-xyz"), GoogleClients.inUse(secrets, builtIn))
    }
}
