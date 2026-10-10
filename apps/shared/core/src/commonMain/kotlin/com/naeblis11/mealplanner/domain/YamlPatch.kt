package com.naeblis11.mealplanner.domain

/**
 * recipe_sync.py's `patch_yaml_field`: changes one top-level `field: value` line of a recipe
 * file and leaves every other byte alone (comments, layout, key order), where a re-dump would
 * rewrite the whole file. The desktop uses it to give a hand-written file its recipe_uuid.
 */
object YamlPatch {
    private const val BOM = "\uFEFF"

    /**
     * Replaces the first top-level `[field]:` line with `field: <value>`, or inserts that line at
     * the top of the document when there is none. The value is YAML-encoded on one line, so special
     * characters can't break the file and a later patch never leaves a wrapped fragment behind. A
     * byte order mark stays first, and an inserted line uses the file's own line ending.
     *
     * "The top of the document" is below any `%YAML` / `%TAG` directive lines and below a `---`
     * document-start marker (which blank and comment lines may precede), since a line above either
     * would make a second document and the file would no longer load. A leading comment with no
     * marker after it stays below the inserted line.
     */
    fun patchField(rawYaml: String, field: String, value: Any?): String {
        val bom = if (rawYaml.startsWith(BOM)) BOM else ""
        val body = rawYaml.substring(bom.length)
        val line = RecipeYaml.dump(linkedMapOf<Any?, Any?>(field to value)).trimEnd('\r', '\n')
        val existing = Regex("^" + Regex.escape(field) + ":[^\\r\\n]*", RegexOption.MULTILINE).find(body)
        val patched = if (existing != null) {
            // replaceRange, not Regex.replace: a backslash or `$` in the value stays literal.
            body.replaceRange(existing.range, line)
        } else {
            val ending = if ("\r\n" in body) "\r\n" else "\n"
            val head = body.substring(0, documentStart(body))
            // A marker that ends the text has no line break of its own to put the new line after.
            val join = if (head.isNotEmpty() && !head.endsWith("\n")) ending else ""
            head + join + line + ending + body.substring(head.length)
        }
        return bom + patched
    }

    /**
     * The offset just past the leading directive lines and, when the first line after them that
     * is neither blank nor a comment is a `---` marker, past that marker; 0 for a plain document.
     */
    private fun documentStart(body: String): Int {
        var directivesEnd = 0
        var at = 0
        for (match in LINE.findAll(body)) {
            val text = match.groupValues[1]
            val next = match.range.last + 1
            when {
                // A directive only counts while nothing but directives has come before it.
                at == directivesEnd && text.startsWith("%") -> {
                    at = next
                    directivesEnd = next
                }
                text == "---" || text.startsWith("--- ") || text.startsWith("---\t") -> return next
                text.isBlank() || text.trimStart().startsWith("#") -> at = next
                else -> return directivesEnd
            }
        }
        return directivesEnd
    }

    // One line and its ending, if any; the ending is left out of group 1.
    private val LINE = Regex("([^\\r\\n]*)(?:\\r\\n|\\n|\\r|$)")
}
