package com.naeblis11.mealplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class MealMasterParityTest {
    private fun MealMasterResult.asJson() = JsonTree.toJson(
        linkedMapOf(
            "recipes" to recipes.map { linkedMapOf("title" to it.title, "data" to it.data) },
            "errors" to errors.map { listOf(it.first, it.second) },
        ),
    )

    @Test
    fun parseMatchesThePi() {
        for (case in ParityFixtures.cases("mealmaster.json")) {
            val file = case.obj["file"]?.str()
            val text = if (file != null) {
                MealMaster.decode(ParityFixtures.sibling("../mealmaster/$file").readBytes())
            } else {
                case.obj["text"]!!.str()!!
            }
            assertEquals(file ?: case.obj["name"]!!.str(), case.obj["expected"], MealMaster.parse(text).asJson())
        }
    }
}
