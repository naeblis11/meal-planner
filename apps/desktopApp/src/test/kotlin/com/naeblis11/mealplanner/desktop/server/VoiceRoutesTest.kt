package com.naeblis11.mealplanner.desktop.server

import com.naeblis11.mealplanner.data.IngredientAisleEntity
import com.naeblis11.mealplanner.desktop.DesktopApp
import com.naeblis11.mealplanner.desktop.MapSettings
import com.naeblis11.mealplanner.domain.Voice
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.Route
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of tests/test_voice_routes.py against the desktop's routes and repositories (P4-R2, P4-R5). */
class VoiceRoutesTest {
    private val dir: File = Files.createTempDirectory("mp-voice").toFile()
    private val app = DesktopApp(dir, settingsFactory = { MapSettings() })
    private val actions = VoiceActions(app.container, today = { TODAY })

    @After
    fun tearDown() {
        app.close()
        dir.deleteRecursively()
    }

    private fun voice(
        token: String? = TOKEN,
        maxBytes: Int = VOICE_MAX_BYTES,
        log: (String) -> Unit = {},
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            ServerRoutes(listOf<Route.() -> Unit>({ voiceRoutes(actions, token = { token }, maxBytes = maxBytes, log = log) })).install(this)
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.send(path: String, body: String?, auth: String? = "Bearer $TOKEN"): HttpResponse =
        client.post(path) {
            if (auth != null) header(HttpHeaders.Authorization, auth)
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpResponse.speech(): String = json()["speech"]!!.jsonPrimitive.content

    private fun reply(ok: Boolean, speech: String) = JsonObject(mapOf("ok" to JsonPrimitive(ok), "speech" to JsonPrimitive(speech)))

    private suspend fun items() = app.container.database.shoppingDao().allInIdOrder()

    private suspend fun pantry() = app.container.pantry.observe().first()

    // The meal tests' library: files in the recipe folder, indexed as at startup.
    private fun library(vararg names: String) {
        for (name in names) {
            File(app.recipesDir, name.lowercase().replace(' ', '-') + ".yaml").writeText(
                "recipe_name: $name\ningredients:\n- Salt:\n    amounts:\n    - amount: 1\n      unit: tsp\nsteps:\n- step: Stir.\n",
            )
        }
        runBlocking { app.folder.sync() }
    }

    private suspend fun idOf(name: String): Long = app.container.recipes.allRecipes().first { it.name == name }.id

    private suspend fun planned(date: String, slot: String): Long? = app.container.plans.assignment(LocalDate.parse(date), slot)?.recipeId

    // The gate

    @Test
    fun aMissingHeaderIsRefusedWithSpeech() = voice {
        val response = send(VOICE_SHOPPING_PATH, """{"item": "milk"}""", auth = null)
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(reply(false, Voice.SPEECH_REFUSED), response.json())
    }

    @Test
    fun aWrongTokenIsRefused() = voice {
        assertEquals(HttpStatusCode.Unauthorized, send(VOICE_SHOPPING_PATH, """{"item": "milk"}""", auth = "Bearer nope").status)
    }

    @Test
    fun aWrongSchemeIsRefused() = voice {
        assertEquals(HttpStatusCode.Unauthorized, send(VOICE_SHOPPING_PATH, """{"item": "milk"}""", auth = "Token $TOKEN").status)
    }

    @Test
    fun theRightTokenIsAccepted() = voice {
        assertEquals(HttpStatusCode.OK, send(VOICE_SHOPPING_PATH, """{"item": "milk"}""").status)
    }

    @Test
    fun withNoTokenSetUpItIsNotSetUp() = voice(token = null) {
        val response = send(VOICE_SHOPPING_PATH, """{"item": "milk"}""")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals(reply(false, Voice.SPEECH_NOT_SET_UP), response.json())
    }

    @Test
    fun anEmptyTokenIsNotSetUpEither() = voice(token = "") {
        assertEquals(HttpStatusCode.ServiceUnavailable, send(VOICE_SHOPPING_PATH, """{"item": "milk"}""").status)
    }

    @Test
    fun theBearerSchemeAndTokenAreComparedExactly() {
        assertTrue(bearerMatches("Bearer abc", "abc"))
        assertTrue(bearerMatches("Bearer  abc ", "abc"))
        assertFalse(bearerMatches("bearer abc", "abc"))
        assertFalse(bearerMatches("Token abc", "abc"))
        assertFalse(bearerMatches("Bearer abcd", "abc"))
        assertFalse(bearerMatches("Bearer caf\u00e9", "abc"))
        assertFalse(bearerMatches("Bearer", "abc"))
        assertFalse(bearerMatches(null, "abc"))
    }

    // The shopping list

    @Test
    fun itAddsWithAmountUnitAndAisle() = voice {
        val response = send(VOICE_SHOPPING_PATH, """{"item": "milk", "quantity": "2", "unit": "gallons", "aisle": "Dairy & Eggs"}""")
        assertEquals(reply(true, "Added 2 gallons of milk to your shopping list, under Dairy & Eggs."), response.json())
        val item = items().single()
        assertEquals(listOf("milk", "2", "gal", "Dairy & Eggs"), listOf(item.name, item.amount, item.unit, item.aisle))
    }

    @Test
    fun skipWordsLeaveTheAmountBlankAndTheAisleGuessed() = voice {
        val response = send(VOICE_SHOPPING_PATH, """{"item": "milk", "quantity": "skip", "unit": "", "aisle": "skip"}""")
        assertEquals("Added milk to your shopping list, under Dairy & Eggs.", response.speech())
        val item = items().single()
        assertNull(item.amount)
        assertEquals("Dairy & Eggs", item.aisle)
    }

    @Test
    fun aRememberedAisleBeatsTheGuess() = voice {
        app.container.database.shoppingDao().rememberAisle(IngredientAisleEntity(name = "milk", aisle = "Beverages"))
        assertTrue(send(VOICE_SHOPPING_PATH, """{"item": "milk"}""").speech().contains("under Beverages"))
    }

    @Test
    fun aNumberWordQuantity() = voice {
        send(VOICE_SHOPPING_PATH, """{"item": "eggs", "quantity": "a dozen"}""")
        assertEquals("12", items().single().amount)
    }

    @Test
    fun aNumericQuantityFromHomeAssistantsTemplate() = voice {
        // Home Assistant's templates can turn "12" into the number 12 before it is sent.
        send(VOICE_SHOPPING_PATH, """{"item": "eggs", "quantity": 12}""")
        assertEquals("12", items().single().amount)
    }

    @Test
    fun aDuplicateWithoutAnAmount() = voice {
        send(VOICE_SHOPPING_PATH, """{"item": "milk"}""")
        val response = send(VOICE_SHOPPING_PATH, """{"item": "Milk"}""")
        assertEquals(reply(true, "Milk is already on your shopping list."), response.json())
        assertEquals(1, items().size)
    }

    @Test
    fun itMergesIntoTheRowAlreadyThere() = voice {
        send(VOICE_SHOPPING_PATH, """{"item": "milk", "quantity": "2", "unit": "gallons"}""")
        val response = send(VOICE_SHOPPING_PATH, """{"item": "milk", "quantity": "1", "unit": "gallon"}""")
        assertEquals("Milk was already on your shopping list; it's now 3 gallons.", response.speech())
        val item = items().single()
        assertEquals("3" to "gal", item.amount to item.unit)
    }

    @Test
    fun theMergeSentenceDescribesTheRowMergedIntoNotTheNewest() = voice {
        // "flour 2 cups" and "flour 1 lb" are both on the list (units that don't combine); "1 cup of flour" merges into
        // the cups row, so the sentence must say 3 cups, not read back the newer lb row.
        send(VOICE_SHOPPING_PATH, """{"item": "flour", "quantity": "2", "unit": "cups"}""")
        send(VOICE_SHOPPING_PATH, """{"item": "flour", "quantity": "1", "unit": "lb"}""")
        val response = send(VOICE_SHOPPING_PATH, """{"item": "flour", "quantity": "1", "unit": "cup"}""")
        assertEquals("Flour was already on your shopping list; it's now 3 cups.", response.speech())
        assertEquals(listOf("1" to "lb", "3" to "cups"), items().map { it.amount to it.unit }.sortedBy { it.first })
    }

    @Test
    fun aSentenceTailInTheItemSlotIsDropped() = voice {
        // "add milk to cart" once arrived as item "milk to cart".
        val response = send(VOICE_SHOPPING_PATH, """{"item": "milk to cart", "quantity": "1", "unit": "gallon"}""")
        assertEquals("Added 1 gallon of milk to your shopping list, under Dairy & Eggs.", response.speech())
        assertEquals("milk", items().single().name)
    }

    @Test
    fun aBlankItemIsOkFalseWithSpeech() = voice {
        val response = send(VOICE_SHOPPING_PATH, """{"item": "  "}""")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(reply(false, Voice.SPEECH_NOTHING_HEARD), response.json())
        assertTrue(items().isEmpty())
    }

    @Test
    fun aMissingBodyIsTreatedAsEmpty() = voice {
        assertFalse(send(VOICE_SHOPPING_PATH, null).json()["ok"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun aBodyThatIsNotAnObjectIsOkFalse() = voice {
        val response = send(VOICE_SHOPPING_PATH, """["milk"]""")
        assertEquals(HttpStatusCode.OK, response.status)
        assertFalse(response.json()["ok"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun anItemThatIsNotTextIsOkFalse() = voice {
        val response = send(VOICE_SHOPPING_PATH, """{"item": {"a": 1}}""")
        assertEquals(HttpStatusCode.OK, response.status)
        assertFalse(response.json()["ok"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun aBodyOverTheCapIsRefused() = voice(maxBytes = 32) {
        val response = send(VOICE_SHOPPING_PATH, """{"item": "milk", "aisle": "a very long aisle name indeed"}""")
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertEquals(reply(false, Voice.SPEECH_NOTHING_HEARD), response.json())
        assertTrue(items().isEmpty())
    }

    // The pantry

    @Test
    fun itAddsANewPantryItemWithItsAisle() = voice {
        val response = send(VOICE_PANTRY_PATH, """{"item": "olive oil", "aisle": "condiments & sauces"}""")
        assertEquals(reply(true, "Added olive oil to your pantry."), response.json())
        val row = pantry().single()
        assertEquals(listOf<Any?>("olive oil", "Condiments & Sauces", true), listOf(row.name, row.aisle, row.active))
    }

    @Test
    fun aSkippedPantryAisleIsBlank() = voice {
        send(VOICE_PANTRY_PATH, """{"item": "olive oil", "aisle": "skip"}""")
        assertNull(pantry().single().aisle)
    }

    @Test
    fun somethingAlreadyOnHandIsADuplicate() = voice {
        send(VOICE_PANTRY_PATH, """{"item": "olive oil"}""")
        val response = send(VOICE_PANTRY_PATH, """{"item": "Olive Oil"}""")
        assertEquals("Olive Oil is already in your pantry.", response.speech())
        assertEquals(1, pantry().size)
    }

    @Test
    fun somethingMarkedOutIsPutBack() = voice {
        send(VOICE_PANTRY_PATH, """{"item": "olive oil"}""")
        app.container.pantry.setActive(pantry().single().id, false)
        val response = send(VOICE_PANTRY_PATH, """{"item": "olive oil"}""")
        assertEquals("Put olive oil back in your pantry.", response.speech())
        assertTrue(pantry().single().active)
    }

    @Test
    fun aBlankPantryItem() = voice {
        assertEquals(reply(false, Voice.SPEECH_NOTHING_HEARD), send(VOICE_PANTRY_PATH, """{"item": "skip"}""").json())
    }

    // The calendar

    @Test
    fun itPlansAMatchedRecipe() {
        library("Ground Beef Tacos", "Chili", "Fish Tacos")
        voice {
            val response = send(VOICE_MEAL_PATH, """{"recipe": "ground beef tacos", "meal": "dinner", "date": "2026-09-17"}""")
            assertEquals(reply(true, "Added Ground Beef Tacos for dinner on Thursday, September 17."), response.json())
            assertEquals(idOf("Ground Beef Tacos"), planned("2026-09-17", "Dinner"))
        }
    }

    @Test
    fun theMealIsDinnerAndTheDayTodayUnlessSaid() {
        library("Ground Beef Tacos", "Chili", "Fish Tacos")
        voice {
            val response = send(VOICE_MEAL_PATH, """{"recipe": "chili"}""")
            assertTrue(response.json()["ok"]!!.jsonPrimitive.boolean)
            assertEquals(idOf("Chili"), planned(TODAY.toString(), "Dinner"))
        }
    }

    @Test
    fun replacingNamesTheRecipeThatWasThere() {
        library("Ground Beef Tacos", "Chili", "Fish Tacos")
        voice {
            app.container.plans.assign(LocalDate.parse("2026-09-17"), "Dinner", idOf("Chili"), null)
            val response = send(VOICE_MEAL_PATH, """{"recipe": "ground beef tacos", "date": "2026-09-17"}""")
            assertEquals("Added Ground Beef Tacos for dinner on Thursday, September 17, replacing Chili.", response.speech())
            assertEquals(idOf("Ground Beef Tacos"), planned("2026-09-17", "Dinner"))
        }
    }

    @Test
    fun theSameRecipeAlreadyPlanned() {
        library("Ground Beef Tacos", "Chili", "Fish Tacos")
        voice {
            send(VOICE_MEAL_PATH, """{"recipe": "chili", "date": "2026-09-17"}""")
            val response = send(VOICE_MEAL_PATH, """{"recipe": "chili", "date": "2026-09-17"}""")
            assertEquals("Chili is already planned for dinner on Thursday, September 17.", response.speech())
        }
    }

    @Test
    fun noRecipeLikeIt() {
        library("Ground Beef Tacos", "Chili", "Fish Tacos")
        voice {
            val response = send(VOICE_MEAL_PATH, """{"recipe": "lasagna", "date": "2026-09-17"}""")
            assertEquals(reply(false, "I couldn't find a recipe like 'lasagna'."), response.json())
            assertNull(planned("2026-09-17", "Dinner"))
        }
    }

    @Test
    fun twoRecipesTooCloseToCallAreAsked() {
        library("Ground Beef Tacos", "Chili", "Fish Tacos", "Beef Tacos")
        voice {
            val response = send(VOICE_MEAL_PATH, """{"recipe": "tacos", "date": "2026-09-17"}""")
            assertEquals(reply(false, "I found Beef Tacos and Fish Tacos. Which one?"), response.json())
        }
    }

    @Test
    fun aWeekIsNotADay() {
        library("Ground Beef Tacos", "Chili", "Fish Tacos")
        voice {
            val response = send(VOICE_MEAL_PATH, """{"recipe": "chili", "date": "2026-W38"}""")
            assertEquals(reply(false, Voice.SPEECH_NEED_A_DAY), response.json())
        }
    }

    @Test
    fun aBlankRecipe() = voice {
        assertEquals(reply(false, Voice.SPEECH_NOTHING_HEARD), send(VOICE_MEAL_PATH, """{"recipe": ""}""").json())
    }

    // Beyond the Python tests

    @Test
    fun planningAMealWritesNoRecipeFile() {
        library("Ground Beef Tacos", "Chili", "Fish Tacos")
        val before = recipeFiles()
        voice {
            send(VOICE_MEAL_PATH, """{"recipe": "chili", "date": "2026-09-17"}""")
            send(VOICE_MEAL_PATH, """{"recipe": "ground beef tacos", "date": "2026-09-17"}""")
            assertEquals(idOf("Ground Beef Tacos"), planned("2026-09-17", "Dinner"))
        }
        assertEquals(before, recipeFiles())
    }

    @Test
    fun aCommandThatFailsInsideIsA500WithSpeechLoggedByClassOnly() {
        val logged = CopyOnWriteArrayList<String>()
        voice(log = { logged.add(it) }) {
            // A closed database makes the repository throw, as a broken disk would.
            app.container.database.close()
            val response = send(VOICE_SHOPPING_PATH, """{"item": "secret milk"}""")
            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertEquals(reply(false, Voice.SPEECH_FAILED), response.json())
        }
        val line = logged.single()
        assertTrue(line, Regex("Meal Planner: a voice command failed: [A-Za-z0-9_.$]+").matches(line))
        assertFalse(line, line.contains(TOKEN) || line.contains("secret"))
    }

    // Each recipe file's name, bytes and modified time.
    private fun recipeFiles(): Map<String, Pair<List<Byte>, Long>> =
        app.recipesDir.listFiles().orEmpty().associate { it.name to (it.readBytes().toList() to it.lastModified()) }

    companion object {
        const val TOKEN = "test-token"
        val TODAY: LocalDate = LocalDate.of(2026, 9, 13)
    }
}
