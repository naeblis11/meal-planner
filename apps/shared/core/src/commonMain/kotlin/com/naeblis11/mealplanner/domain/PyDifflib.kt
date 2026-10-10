package com.naeblis11.mealplanner.domain

/**
 * difflib.SequenceMatcher(None, a, b).ratio(): no junk function, autojunk on, as voice.match_recipe uses it.
 * Python compares code points; the strings this is given are recipe names normalised to [a-z0-9 ], so comparing
 * chars is the same.
 */
object PyDifflib {
    fun ratio(a: String, b: String): Double {
        val matches = matchingBlocks(a, b).sumOf { it.size }
        val length = a.length + b.length
        return if (length > 0) 2.0 * matches / length else 1.0
    }

    private class Block(val i: Int, val j: Int, val size: Int)

    // get_matching_blocks without the final merge of adjacent blocks, which leaves their total size unchanged.
    private fun matchingBlocks(a: String, b: String): List<Block> {
        val b2j = HashMap<Char, MutableList<Int>>()
        b.forEachIndexed { j, c -> b2j.getOrPut(c) { mutableListOf() }.add(j) }
        // autojunk: in a b of 200 or more, an element making up more than 1% of it (plus one) is "popular" and not indexed.
        if (b.length >= 200) {
            val ntest = b.length / 100 + 1
            b2j.entries.removeIf { it.value.size > ntest }
        }
        val blocks = mutableListOf<Block>()
        val queue = ArrayDeque<IntArray>()
        queue.addLast(intArrayOf(0, a.length, 0, b.length))
        while (queue.isNotEmpty()) {
            val (alo, ahi, blo, bhi) = queue.removeLast()
            val match = longestMatch(a, b, b2j, alo, ahi, blo, bhi)
            if (match.size > 0) {
                blocks += match
                if (alo < match.i && blo < match.j) queue.addLast(intArrayOf(alo, match.i, blo, match.j))
                if (match.i + match.size < ahi && match.j + match.size < bhi) {
                    queue.addLast(intArrayOf(match.i + match.size, ahi, match.j + match.size, bhi))
                }
            }
        }
        return blocks
    }

    // find_longest_match. With no junk only its first two widening loops can apply; they also reach across popular elements.
    private fun longestMatch(a: String, b: String, b2j: Map<Char, List<Int>>, alo: Int, ahi: Int, blo: Int, bhi: Int): Block {
        var besti = alo
        var bestj = blo
        var bestSize = 0
        var j2len = HashMap<Int, Int>()
        for (i in alo until ahi) {
            val newJ2len = HashMap<Int, Int>()
            for (j in b2j[a[i]] ?: emptyList()) {
                if (j < blo) continue
                if (j >= bhi) break
                val k = (j2len[j - 1] ?: 0) + 1
                newJ2len[j] = k
                if (k > bestSize) {
                    besti = i - k + 1
                    bestj = j - k + 1
                    bestSize = k
                }
            }
            j2len = newJ2len
        }
        while (besti > alo && bestj > blo && a[besti - 1] == b[bestj - 1]) {
            besti--
            bestj--
            bestSize++
        }
        while (besti + bestSize < ahi && bestj + bestSize < bhi && a[besti + bestSize] == b[bestj + bestSize]) bestSize++
        return Block(besti, bestj, bestSize)
    }
}
