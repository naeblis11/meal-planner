package com.naeblis11.mealplanner.domain

import java.util.regex.Pattern
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.AbstractConstruct
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.representer.Representer
import org.yaml.snakeyaml.resolver.Resolver

/** A recipe (or part of one) as loaded from YAML: a mutable, insertion-ordered map. */
typealias YamlMap = MutableMap<Any?, Any?>

/** A recipe file that can't be read: bad YAML, a missing field, a wrong shape. */
class RecipeFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * YAML as the Pi's PyYAML reads and writes it (`yaml.safe_load`,
 * `yaml.safe_dump(sort_keys=False, allow_unicode=True)`). SnakeYAML is also
 * YAML 1.1, but guesses plain scalars' types slightly differently (it reads
 * `1e3` as a number; PyYAML keeps it as text), so it is given PyYAML's exact
 * implicit-type rules. Dumping uses the same rules, so anything written here
 * reads back identically on the Pi.
 */
object RecipeYaml {
    /** More nodes than any real recipe has; a document expanding past this is an alias bomb. */
    const val MAX_NODES = 100_000

    fun load(text: String): Any? {
        val value = try {
            yaml().load<Any?>(text)
        } catch (e: RuntimeException) {
            // YAMLException, plus the raw NumberFormatException SnakeYAML throws for e.g. `0b_`.
            throw RecipeFormatException("Not valid YAML: ${e.message}", e)
        }
        if (exceedsNodeLimit(value)) throw RecipeFormatException("Too large to be a recipe file.")
        return value
    }

    /**
     * Counts maps, lists and scalars as a walk sees them -- an alias counts its whole
     * expansion each time it appears -- and stops as soon as the count passes [MAX_NODES].
     * Iterative, so a deep or self-referencing document can't overflow the stack.
     */
    private fun exceedsNodeLimit(root: Any?): Boolean {
        val stack = ArrayDeque<Any?>()
        stack.addLast(root)
        var count = 0
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (++count > MAX_NODES) return true
            when (node) {
                is Map<*, *> -> for ((k, v) in node) {
                    stack.addLast(k)
                    stack.addLast(v)
                }
                is List<*> -> for (item in node) stack.addLast(item)
            }
            if (stack.size > MAX_NODES) return true
        }
        return false
    }

    fun dump(value: Any?): String = yaml().dump(value)

    /** Copies maps and lists all the way down, so a staged recipe can be edited without touching the original. */
    fun deepCopy(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.entries.associateTo(LinkedHashMap<Any?, Any?>()) { (k, v) -> k to deepCopy(v) }
        is List<*> -> value.mapTo(ArrayList()) { deepCopy(it) }
        else -> value
    }

    private fun yaml(): Yaml {
        val dumper = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isAllowUnicode = true
            indent = 2
            indicatorIndent = 0
            width = 4096
            splitLines = false
        }
        val loader = LoaderOptions().apply {
            // Defence in depth; exceedsNodeLimit is what actually bounds the expansion.
            maxAliasesForCollections = 20
        }
        return Yaml(TextTimestampConstructor(loader), Representer(dumper), dumper, loader, PyYamlResolver())
    }

    /**
     * SafeConstructor, except that a bare date or datetime (`2026-01-01`, `2026-01-01 10:30:00`) stays the
     * scalar's own text. SnakeYAML would make it a java.util.Date, which nothing downstream expects: Py.str
     * printed Java's local-time form where Python's str(date) is "2026-01-01", and a dump wrote it back as
     * `2026-01-01T00:00:00Z`. As text, a recipe or step written as a date reads and shows as written, and
     * dumps quoted (`'2026-01-01'`), which reads back as the same text here and in PyYAML.
     */
    private class TextTimestampConstructor(options: LoaderOptions) : SafeConstructor(options) {
        init {
            yamlConstructors[Tag.TIMESTAMP] = object : AbstractConstruct() {
                override fun construct(node: Node): Any = (node as ScalarNode).value
            }
        }
    }

    /** PyYAML's implicit resolvers (yaml/resolver.py), in PyYAML's order. */
    private class PyYamlResolver : Resolver() {
        override fun addImplicitResolvers() {
            addImplicitResolver(
                Tag.BOOL,
                Pattern.compile("^(?:yes|Yes|YES|no|No|NO|true|True|TRUE|false|False|FALSE|on|On|ON|off|Off|OFF)$"),
                "yYnNtTfFoO",
            )
            addImplicitResolver(
                Tag.FLOAT,
                Pattern.compile(
                    "^(?:[-+]?(?:[0-9][0-9_]*)\\.[0-9_]*(?:[eE][-+][0-9]+)?" +
                        "|\\.[0-9][0-9_]*(?:[eE][-+][0-9]+)?" +
                        "|[-+]?[0-9][0-9_]*(?::[0-5]?[0-9])+\\.[0-9_]*" +
                        "|[-+]?\\.(?:inf|Inf|INF)" +
                        "|\\.(?:nan|NaN|NAN))$",
                ),
                "-+0123456789.",
            )
            addImplicitResolver(
                Tag.INT,
                Pattern.compile(
                    "^(?:[-+]?0b[0-1_]+" +
                        "|[-+]?0[0-7_]+" +
                        "|[-+]?(?:0|[1-9][0-9_]*)" +
                        "|[-+]?0x[0-9a-fA-F_]+" +
                        "|[-+]?[1-9][0-9_]*(?::[0-5]?[0-9])+)$",
                ),
                "-+0123456789",
            )
            addImplicitResolver(Tag.MERGE, Pattern.compile("^(?:<<)$"), "<")
            addImplicitResolver(Tag.NULL, Pattern.compile("^(?:~|null|Null|NULL|)$"), "~nN\u0000")
            addImplicitResolver(Tag.NULL, Pattern.compile("^$"), null)
            addImplicitResolver(
                Tag.TIMESTAMP,
                Pattern.compile(
                    "^(?:[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]" +
                        "|[0-9][0-9][0-9][0-9]-[0-9][0-9]?-[0-9][0-9]?" +
                        "(?:[Tt]|[ \\t]+)[0-9][0-9]?:[0-9][0-9]:[0-9][0-9](?:\\.[0-9]*)?" +
                        "(?:[ \\t]*(?:Z|[-+][0-9][0-9]?(?::[0-9][0-9])?))?)$",
                ),
                "0123456789",
            )
            // PyYAML's `=` (value) tag: SafeConstructor can't build it, so loading a bare `=`
            // fails as it does on the Pi, and the string "=" is dumped quoted.
            addImplicitResolver(Tag("tag:yaml.org,2002:value"), Pattern.compile("^(?:=)$"), "=")
        }
    }
}
