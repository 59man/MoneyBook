package com.moneybook.domain

import com.moneybook.data.db.NoteUse
import com.moneybook.data.db.TxnRow
import com.moneybook.shared.model.TxnType

/** Search's text and filter panel. Empty fields mean "any". */
data class SearchFilter(
    val text: String = "",
    val range: DateRange? = null,
    val accountId: String? = null,
    /** A top-level category; its subcategories' entries match too. */
    val categoryId: String? = null,
    /** Inclusive bounds on the main-currency amount, minor units. */
    val min: Long? = null,
    val max: Long? = null,
) {
    val isEmpty: Boolean
        get() = text.isBlank() && range == null && accountId == null && categoryId == null && min == null && max == null

    fun matches(row: TxnRow): Boolean {
        val t = text.trim()
        if (t.isNotEmpty() && !row.note.contains(t, ignoreCase = true) && !row.description.contains(t, ignoreCase = true)) return false
        if (range != null && !range.contains(row.localDate())) return false
        if (accountId != null && row.accountId != accountId && row.toAccountId != accountId) return false
        if (categoryId != null && row.categoryId != categoryId && row.parentCategoryId != categoryId) return false
        if (min != null && row.amountMain < min) return false
        if (max != null && row.amountMain > max) return false
        return true
    }
}

/** The rows [filter] matches, in the given order. An empty filter finds nothing, as in Realbyte. */
fun search(rows: List<TxnRow>, filter: SearchFilter): List<TxnRow> = if (filter.isEmpty) emptyList() else rows.filter(filter::matches)

/** The summary above Search's results. */
data class SearchTotals(val income: Long = 0, val expense: Long = 0, val transfer: Long = 0)

/** Income and expense follow [countedAs] (mode A); [SearchTotals.transfer] is the transfers that count as neither. */
fun searchTotals(rows: List<TxnRow>): SearchTotals {
    val totals = totalsOf(rows)
    return SearchTotals(totals.income, totals.expense, rows.filter { it.type == TxnType.TRANSFER && it.countedAs() == null }.sumOf { it.amountMain })
}

/**
 * Up to [limit] earlier notes that contain [typed] (ignoring case), the most used first, then the most
 * recent. A note equal to what is typed is left out: picking it would change nothing.
 */
fun rankSuggestions(uses: List<NoteUse>, typed: String, limit: Int = 5): List<String> {
    val t = typed.trim()
    if (t.isEmpty()) return emptyList()
    return uses.filter { it.note.contains(t, ignoreCase = true) && !it.note.equals(t, ignoreCase = true) }
        .sortedWith(compareByDescending<NoteUse> { it.uses }.thenByDescending { it.lastUsed })
        .take(limit)
        .map { it.note }
}
