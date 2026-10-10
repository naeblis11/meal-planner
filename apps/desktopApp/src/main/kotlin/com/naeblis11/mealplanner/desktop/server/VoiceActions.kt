package com.naeblis11.mealplanner.desktop.server

import com.naeblis11.mealplanner.app.AppContainer
import com.naeblis11.mealplanner.data.AddStatus
import com.naeblis11.mealplanner.data.PantryAdd
import com.naeblis11.mealplanner.data.RecipeEntity
import com.naeblis11.mealplanner.domain.Voice
import java.time.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A voice command's answer, {"ok": ..., "speech": ...} with its HTTP status (app.py's _voice_reply). */
data class VoiceReply(val ok: Boolean, val speech: String, val status: Int = 200) {
    fun json(): JsonReply = JsonReply(status, JsonObject(mapOf("ok" to JsonPrimitive(ok), "speech" to JsonPrimitive(speech))))
}

/**
 * Alexa's three commands (P4-R5), as app.py's api_voice_* routes, writing through the repositories the screens use:
 * - shopping: the list's additive Add, as on the Shopping screen;
 * - pantry: add, or put back an item marked out;
 * - meal: assign the recipe matched by name to a day and slot.
 * What the user should hear about (nothing heard, no such recipe) is ok=false with status 200, as on the Python server.
 */
class VoiceActions(private val container: AppContainer, private val today: () -> LocalDate = LocalDate::now) {
    suspend fun shopping(body: Map<Any?, Any?>): VoiceReply {
        val name = Voice.itemName(Voice.field(body, "item")) ?: return nothingHeard()
        val amount = Voice.normalizeQuantity(Voice.field(body, "quantity"))
        val unit = Voice.normalizeUnit(Voice.field(body, "unit"), amount)
        val aisle = Voice.normalizeAisle(Voice.field(body, "aisle"))
        val added = container.shopping.addItem(name, amount, unit, aisle)
        // By id, not by name: the same name can be on the list twice with units that don't combine, and the sentence
        // must describe the row the amount went into.
        val row = container.database.shoppingDao().item(added.id)
        val status = when (added.status) {
            AddStatus.ADDED -> Voice.ShoppingStatus.ADDED
            AddStatus.MERGED -> Voice.ShoppingStatus.MERGED
            AddStatus.DUPLICATE -> Voice.ShoppingStatus.DUPLICATE
        }
        return said(Voice.shoppingSpeech(status, name, row?.amount, row?.unit, row?.aisle))
    }

    suspend fun pantry(body: Map<Any?, Any?>): VoiceReply {
        val name = Voice.itemName(Voice.field(body, "item")) ?: return nothingHeard()
        val aisle = Voice.normalizeAisle(Voice.field(body, "aisle"))
        if (container.pantry.add(name, aisle) is PantryAdd.Added) return said(Voice.pantrySpeech(Voice.PantryStatus.ADDED, name))
        val existing = container.database.pantryDao().byName(name)
        if (existing == null || existing.active) return said(Voice.pantrySpeech(Voice.PantryStatus.DUPLICATE, name))
        container.pantry.setActive(existing.id, true)
        return said(Voice.pantrySpeech(Voice.PantryStatus.RESTORED, name))
    }

    suspend fun meal(body: Map<Any?, Any?>): VoiceReply {
        val spoken = Voice.given(Voice.field(body, "recipe")) ?: return nothingHeard()
        val slot = Voice.normalizeMeal(Voice.field(body, "meal"))
        val day = Voice.parseDay(Voice.field(body, "date"), today()) ?: return VoiceReply(false, Voice.SPEECH_NEED_A_DAY)
        // SQLite's NOCASE order, as the Python route listed them; it only decides between names that read the same.
        val recipes = container.recipes.allRecipes().sortedWith(compareBy<RecipeEntity>({ nocase(it.name) }, { it.id }))
        val match = Voice.matchRecipe(spoken, recipes.map { it.name })
        val name = when (match.status) {
            Voice.MatchStatus.NONE -> return VoiceReply(false, Voice.noMatchSpeech(spoken))
            Voice.MatchStatus.AMBIGUOUS -> return VoiceReply(false, Voice.ambiguousSpeech(match.candidates))
            Voice.MatchStatus.FOUND -> match.name!!
        }
        val recipe = recipes.first { it.name == name }
        val previous = container.plans.assignment(day, slot)?.recipeName
        if (previous != name) container.plans.assign(day, slot, recipe.id, null)
        return said(Voice.mealSpeech(name, slot, day, previous))
    }

    private fun said(speech: String) = VoiceReply(true, speech)

    private fun nothingHeard() = VoiceReply(false, Voice.SPEECH_NOTHING_HEARD)

    // ASCII letters folded, as SQLite's NOCASE does.
    private fun nocase(text: String): String = buildString(text.length) { for (c in text) append(if (c in 'A'..'Z') c + 32 else c) }
}
