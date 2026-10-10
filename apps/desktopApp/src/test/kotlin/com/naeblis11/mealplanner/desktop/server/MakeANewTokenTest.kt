package com.naeblis11.mealplanner.desktop.server

import com.naeblis11.mealplanner.desktop.DesktopApp
import com.naeblis11.mealplanner.desktop.MapSettings
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.Route
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P4-R6: Make a new token replaces the one in use, so Alexa's old line stops working until the new one is pasted. */
class MakeANewTokenTest {
    private val dir: File = Files.createTempDirectory("mp-new-token").toFile()
    private val secretsDir: File = Files.createTempDirectory("mp-new-token-secrets").toFile()
    private val app = DesktopApp(dir, settingsFactory = { MapSettings() })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val token = ApiToken(SecretsFile(File(secretsDir, ".env")))
    private val server = MealPlannerServer(token, ServerRoutes(), FakeEngine(), log = {})

    @After
    fun tearDown() {
        scope.cancel()
        app.close()
        dir.deleteRecursively()
        secretsDir.deleteRecursively()
    }

    private suspend fun ApplicationTestBuilder.addMilk(bearer: String) =
        client.post(VOICE_SHOPPING_PATH) {
            header(HttpHeaders.Authorization, "Bearer $bearer")
            contentType(ContentType.Application.Json)
            setBody("""{"item": "milk"}""")
        }.status

    @Test
    fun theOldTokenStopsWorkingOnTheVoiceRoutes() {
        server.start(scope)
        val controls = DesktopServerControls(server, token, clipboard = {})
        val old = controls.createToken()
        val new = controls.createToken()
        // The messages never print a token.
        assertTrue("the new token is the old one", old != new)
        assertTrue("the secrets file doesn't hold the new token", SecretsFile(File(secretsDir, ".env")).read()[ApiToken.KEY] == new)
        assertTrue("the token waiting to be shown is not the new one", controls.pendingReveal.take() == new)
        testApplication {
            application {
                ServerRoutes(listOf<Route.() -> Unit>({ voiceRoutes(VoiceActions(app.container), token = { token.value }) })).install(this)
            }
            assertEquals(HttpStatusCode.Unauthorized, addMilk(old))
            assertEquals(HttpStatusCode.OK, addMilk(new))
        }
    }
}
