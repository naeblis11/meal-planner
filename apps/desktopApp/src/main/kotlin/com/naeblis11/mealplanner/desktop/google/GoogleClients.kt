package com.naeblis11.mealplanner.desktop.google

import com.naeblis11.mealplanner.desktop.server.SecretsFile
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Where the OAuth client comes from (P5-R4, Task 13), in this order ([inUse]):
 * 1. a hand-picked client in the secrets file: the place one is kept is the pair of keys [ID_KEY] and [SECRET_KEY]
 *    (the names set_gcal_oauth.py gave them), written there by Settings from the client file Google's console
 *    downloads for a Desktop app, or by hand;
 * 2. the client built into the app ([builtIn]): the google-client.json resource the build writes from
 *    apps/google-client.properties (docs/WINDOWS.md);
 * 3. none, and Settings asks for the client file.
 */
object GoogleClients {
    const val ID_KEY = "MEAL_PLANNER_GCAL_CLIENT_ID"
    const val SECRET_KEY = "MEAL_PLANNER_GCAL_CLIENT_SECRET"

    /** The classpath resource the build writes when it has a client (apps/desktopApp/build.gradle.kts). */
    const val RESOURCE = "/google-client.json"

    const val BAD_FILE =
        "That file isn't a Google OAuth client for a desktop app. In the Google Cloud console, create an OAuth client ID of type Desktop app and download its JSON file."

    /** The client to use: the secrets file's when both halves are there, else [builtIn], else null. */
    fun inUse(secrets: SecretsFile, builtIn: GoogleClient?): GoogleClient? = fromSecrets(secrets) ?: builtIn

    /**
     * The client built into the app, read through [fromJson] from [read] (the [RESOURCE]); null when the build had none.
     * One that can't be read or isn't a client is null too, and [log] says so by kind only, never with its values:
     * Settings then asks for the client file, as for a build without one.
     */
    fun builtIn(read: () -> String? = ::readResource, log: (String) -> Unit = { System.err.println(it) }): GoogleClient? {
        val text = try {
            read()
        } catch (e: IOException) {
            log("Meal Planner: the Google client built into this app can't be read (${e.javaClass.simpleName}).")
            return null
        } ?: return null
        return try {
            fromJson(text)
        } catch (e: IllegalArgumentException) {
            log("Meal Planner: the Google client built into this app isn't a desktop OAuth client, so it isn't used.")
            null
        }
    }

    private fun readResource(): String? =
        GoogleClients::class.java.getResourceAsStream(RESOURCE)?.use { String(it.readBytes(), Charsets.UTF_8) }

    /** The client in [secrets], or null when either half is missing or the file can't be read. */
    fun fromSecrets(secrets: SecretsFile): GoogleClient? = fromValues(
        try {
            secrets.read()
        } catch (e: IOException) {
            emptyMap()
        },
    )

    fun fromValues(values: Map<String, String>): GoogleClient? {
        val id = values[ID_KEY]?.let(::unquote).orEmpty()
        val secret = values[SECRET_KEY]?.let(::unquote).orEmpty()
        return if (id.isNotEmpty() && secret.isNotEmpty()) GoogleClient(id, secret) else null
    }

    /** Google's client file ({"installed": {"client_id": ..., "client_secret": ...}}); IllegalArgumentException(BAD_FILE) for anything else. */
    fun fromJson(text: String): GoogleClient {
        val installed = try {
            (Json.parseToJsonElement(text.removePrefix("\uFEFF")) as? JsonObject)?.get("installed") as? JsonObject
        } catch (e: Exception) {
            null
        }
        val id = installed?.string("client_id").orEmpty()
        val secret = installed?.string("client_secret").orEmpty()
        require(printable(id) && printable(secret)) { BAD_FILE }
        return GoogleClient(id, secret)
    }

    /**
     * Saves [client] as the hand-picked client, under [ID_KEY] and [SECRET_KEY]; every other line of the file stays as it is. A value that isn't
     * [printable] is refused (IllegalArgumentException(BAD_FILE)) before anything is written: it could add a line.
     */
    fun save(secrets: SecretsFile, client: GoogleClient) {
        require(printable(client.id) && printable(client.secret)) { BAD_FILE }
        secrets.put(ID_KEY, client.id)
        secrets.put(SECRET_KEY, client.secret)
    }

    /**
     * Google's client ids and secrets are printable ASCII without spaces ('!' to '~'). Anything else (a line break, a
     * space, NEL, a control character) is refused rather than trimmed, so a crafted client file can never put a
     * second line into the secrets file.
     */
    fun printable(value: String): Boolean = value.isNotEmpty() && value.all { it in '!'..'~' }

    /** One matching pair of surrounding quotes comes off (python-dotenv's KEY="value"), then spaces. */
    fun unquote(raw: String): String {
        val trimmed = raw.trim()
        val quoted = trimmed.length >= 2 && trimmed.first() == trimmed.last() && (trimmed.first() == '"' || trimmed.first() == '\'')
        return (if (quoted) trimmed.substring(1, trimmed.length - 1) else trimmed).trim()
    }
}
