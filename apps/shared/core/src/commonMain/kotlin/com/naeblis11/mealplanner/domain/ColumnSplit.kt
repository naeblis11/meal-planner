package com.naeblis11.mealplanner.domain

/** Side-by-side columns for a wide screen: the shopping list's and the pantry's aisle cards. */
object ColumnSplit {
    /**
     * [items] in their order, cut into at most [columns] runs of near-even total [weight]. Reading down the first
     * column and then the next keeps the list's order, as on a printed list.
     *
     * The cut is greedy, one column at a time: a column takes items until the next one would overshoot that column's
     * share of the weight still left by more than stopping would fall short of it (a tie takes the item), then the
     * rest is shared among the remaining columns. It is not the globally most even cut: an early column may end up a
     * little heavier than a later one. Every run has at least one item, so there are fewer runs than [columns] when
     * there are fewer items.
     */
    fun <T> contiguous(items: List<T>, columns: Int, weight: (T) -> Int): List<List<T>> {
        require(columns >= 1) { "columns must be at least 1: $columns" }
        if (items.isEmpty()) return emptyList()
        val count = minOf(columns, items.size)
        val weights = items.map { weight(it).coerceAtLeast(1) }
        var left = weights.sum()
        val runs = mutableListOf<List<T>>()
        var start = 0
        for (column in 0 until count) {
            val columnsLeft = count - column
            if (columnsLeft == 1) {
                runs += items.subList(start, items.size).toList()
                break
            }
            val target = left.toDouble() / columnsLeft
            var end = start
            var sum = 0
            // At least one item. Stop where going on would overshoot the target by more than stopping falls short,
            // and leave one item for each column still to come.
            while (end < items.size - (columnsLeft - 1)) {
                val next = weights[end]
                if (end > start && sum + next - target > target - sum) break
                sum += next
                end++
            }
            runs += items.subList(start, end).toList()
            left -= sum
            start = end
        }
        return runs
    }
}
