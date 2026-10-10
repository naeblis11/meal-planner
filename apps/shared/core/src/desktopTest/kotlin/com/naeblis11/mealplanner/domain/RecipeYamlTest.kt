package com.naeblis11.mealplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeYamlTest {
    @Test
    fun loadTypesScalarsLikePyYaml() {
        for (case in ParityFixtures.cases("yaml_load.json")) {
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
        val texts = ParityFixtures.cases("yaml_load.json")
            .filter { !it.obj.containsKey("error") }.map { it.obj["text"]!!.str()!! } +
            ParityFixtures.cases("orf.json")
                .filter { it.obj["error"]?.str() != "*" }.map { it.obj["yaml"]!!.str()!! }
        assertTrue("the fixtures should have loadable cases", texts.isNotEmpty())
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
    fun aBareDateOrDatetimeIsItsOwnText() {
        // SnakeYAML's timestamp type would show as Java's local-time toString and dump as 2026-01-01T00:00:00Z;
        // Python's str(date) is "2026-01-01". The scalar's text is kept, so nothing downstream sees a Date.
        val text = "recipe_name: 2026-01-01\nsteps:\n- step: 2026-01-01 10:30:00\n- step: 2026-01-01T10:30:00.5Z\n"
        val loaded = RecipeYaml.load(text) as Map<*, *>
        assertEquals("2026-01-01", loaded["recipe_name"])
        assertEquals("2026-01-01", Py.str(loaded["recipe_name"]))
        val steps = (loaded["steps"] as List<*>).map { (it as Map<*, *>)["step"] }
        assertEquals(listOf("2026-01-01 10:30:00", "2026-01-01T10:30:00.5Z"), steps)
        assertEquals("2026-01-01 10:30:00", Py.str(steps[0]))
        // Dumped quoted, since bare it would read as a date again (here and in PyYAML); it reads back as the same text.
        val dumped = RecipeYaml.dump(loaded)
        assertEquals("recipe_name: '2026-01-01'\nsteps:\n- step: '2026-01-01 10:30:00'\n- step: '2026-01-01T10:30:00.5Z'\n", dumped)
        assertEquals(JsonTree.toJson(loaded), JsonTree.toJson(RecipeYaml.load(dumped)))
        assertEquals("\"2026-01-01\"", JsonTree.toJson(loaded["recipe_name"]).toString())
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

