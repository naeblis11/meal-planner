package com.naeblis11.mealplanner.domain

/** The ingredient and step editors' moves: static/ingredient-editor.js's and step-editor.js's rules. */
object EditorMoves {
    /**
     * step-editor.js splitStep: the text before [cursor] stays in the step and the text after it becomes a new step
     * directly below, both trimmed. With the cursor at the end that is "insert an empty step after". Null when there
     * is nothing before the cursor but something after it: the step is left alone.
     */
    fun splitStep(text: String, cursor: Int): Pair<String, String>? {
        val at = cursor.coerceIn(0, text.length)
        val before = text.substring(0, at).trim()
        val after = text.substring(at).trim()
        if (before.isEmpty() && after.isNotEmpty()) return null
        return before to after
    }

    /** Up (-1) and Down (1): the item at [index] moves by [by] places; past either end the list stays as it is. */
    fun <T> moved(items: List<T>, index: Int, by: Int): List<T> = movedTo(items, index, index + by)

    /** A drag's drop (ingredient-editor.js's insertBefore): the item at [from] goes to [to], every other keeps its order. */
    fun <T> movedTo(items: List<T>, from: Int, to: Int): List<T> {
        if (from !in items.indices || to !in items.indices || from == to) return items
        return items.toMutableList().apply { add(to, removeAt(from)) }
    }

    /**
     * Where a dragged row lands (ingredient-editor.js's dragover: before a row whose middle the pointer is above, after
     * one whose middle it is below): the number of other rows whose middle is above the dragged row's middle, moved by
     * [offset]. [middles] are every row's vertical middle, in order, as laid out before the drag.
     */
    fun dropIndex(middles: List<Float>, from: Int, offset: Float): Int {
        val dragged = middles[from] + offset
        return middles.indices.count { it != from && middles[it] < dragged }
    }
}
