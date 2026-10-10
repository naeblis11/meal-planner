package com.naeblis11.mealplanner.domain

/** The cookbook a recipe came from, as app.py `_book_name` reads ORF `source_book`. */
object Books {
    /** One display string for [sourceBookJson] (the index's JSON column), or null. ORF lets it be a title or a list of them. */
    fun name(sourceBookJson: String?): String? {
        var value = JsonTree.decode(sourceBookJson)
        if (value is List<*>) {
            value = value.map { Py.strip(Py.str(it)) }.filter { it.isNotEmpty() }.joinToString(", ")
        }
        if (value == null || value is Map<*, *>) return null
        return Py.strip(Py.str(value)).ifEmpty { null }
    }
}
