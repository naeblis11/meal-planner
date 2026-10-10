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
     * the top when there is none. The value is YAML-encoded on one line, so special characters
     * can't break the file and a later patch never leaves a wrapped fragment behind. A byte order
     * mark stays first, and an inserted line uses the file's own line ending.
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
            line + (if ("\r\n" in body) "\r\n" else "\n") + body
        }
        return bom + patched
    }
}
