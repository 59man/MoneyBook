package com.moneybook.shared.importer

import java.text.Normalizer

data class CategoryOption(val id: String, val name: String)

/** Suggests an existing category for a name from another app ("Transportation" → "🚖 Transport"). */
object CategoryMatcher {
    private const val THRESHOLD = 0.75

    /** The best-scoring option at or above the threshold; on a tie, the earlier option. */
    fun suggest(name: String, options: List<CategoryOption>): CategoryOption? {
        var best: CategoryOption? = null
        var bestScore = THRESHOLD
        for (option in options) {
            val score = similarity(name, option.name)
            if (score > bestScore || (best == null && score >= THRESHOLD)) {
                best = option
                bestScore = score
            }
        }
        return best
    }

    /** Letters and digits only, without accents or emoji, lower case, single spaces: "🧘🏼 Health/Fitness" → "health fitness". */
    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    fun similarity(a: String, b: String): Double {
        val x = normalize(a)
        val y = normalize(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        val (short, long) = if (x.length <= y.length) x to y else y to x
        if (short.length >= 4 && long.startsWith(short)) return 0.9
        return 1.0 - levenshtein(x, y).toDouble() / long.length
    }

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }
}
