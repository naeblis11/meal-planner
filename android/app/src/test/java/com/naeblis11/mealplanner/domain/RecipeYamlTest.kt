package com.naeblis11.mealplanner.domain

import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RecipeYamlTest {
    @Test
    fun loadTypesScalarsLikePyYaml() {
        for (case in ParityFixtures.load("yaml_load.json").jsonArray) {
            val text = case.obj["text"]!!.str()!!
            if (case.obj.containsKey("error")) {
                assertThrows(RecipeFormatException::class.java) { RecipeYaml.load(text) }
            } else {
                assertEquals("load($text)", case.obj["value"], JsonTree.toJson(RecipeYaml.load(text)))
            }
        }
    }

    @Test
    fun dumpThenLoadGivesTheSameDocument() {
        val texts = ParityFixtures.load("yaml_load.json").jsonArray
            .filter { !it.obj.containsKey("error") }.map { it.obj["text"]!!.str()!! } +
            ParityFixtures.load("orf.json").jsonArray
                .filter { it.obj["error"]?.str() != "*" }.map { it.obj["yaml"]!!.str()!! }
        for (text in texts) {
            val loaded = RecipeYaml.load(text)
            assertEquals("round trip of $text", JsonTree.toJson(loaded), JsonTree.toJson(RecipeYaml.load(RecipeYaml.dump(loaded))))
        }
    }

    @Test
    fun deepCopyIsIndependent() {
        @Suppress("UNCHECKED_CAST")
        val original = RecipeYaml.load("a:\n  b: [1, 2]\n") as YamlMap
        @Suppress("UNCHECKED_CAST")
        val copy = RecipeYaml.deepCopy(original) as YamlMap
        @Suppress("UNCHECKED_CAST")
        ((copy["a"] as YamlMap)["b"] as MutableList<Any?>).add(3)
        assertEquals(JsonTree.toJson(RecipeYaml.load("a:\n  b: [1, 2]\n")), JsonTree.toJson(original))
    }

    @Test
    fun invalidYamlIsARecipeFormatException() {
        assertThrows(RecipeFormatException::class.java) { RecipeYaml.load("recipe_name: [\n") }
    }

    @Test
    fun invalidNumbersAreRecipeFormatExceptions() {
        assertThrows(RecipeFormatException::class.java) { RecipeYaml.load("x: 0b_") }
        assertThrows(RecipeFormatException::class.java) { RecipeYaml.load("x: 0x_") }
    }

    @Test
    fun aBareEqualsSignIsPyYamlsValueTag() {
        assertThrows(RecipeFormatException::class.java) { RecipeYaml.load("a: =") }
        assertEquals("a: '='\n", RecipeYaml.dump(mapOf("a" to "=")))
    }

    @Test(timeout = 10_000)
    fun aliasBombsAreRefusedQuickly() {
        val chain = buildString {
            append("a0: &a0 [x, x]\n")
            for (i in 1..24) append("a$i: &a$i [*a${i - 1}, *a${i - 1}]\n")
        }
        // Refused by the alias limit or the node count, whichever trips first.
        assertThrows(RecipeFormatException::class.java) { RecipeYaml.load(chain) }
    }

    @Test(timeout = 10_000)
    fun documentsExpandingPastTheNodeLimitAreRefused() {
        // Within the alias limit, but 20 copies of a 6000-item list is 120k nodes.
        val text = "a0: &a0 [" + (1..6000).joinToString(", ") { "x" } + "]\n" +
            "a1: [" + (1..20).joinToString(", ") { "*a0" } + "]\n"
        val e = assertThrows(RecipeFormatException::class.java) { RecipeYaml.load(text) }
        assertEquals("Too large to be a recipe file.", e.message)
    }

    @Test
    fun anOrdinaryRecipeStillLoads() {
        @Suppress("UNCHECKED_CAST")
        val recipe = RecipeYaml.load(
            "recipe_name: Toast\ningredients:\n- bread:\n    amounts:\n    - amount: 2\n      unit: slices\n" +
                "steps:\n- step: Toast it.\n",
        ) as YamlMap
        assertEquals("Toast", recipe["recipe_name"])
    }
}

