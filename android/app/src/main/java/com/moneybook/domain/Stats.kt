package com.moneybook.domain

import com.moneybook.data.db.TxnRow
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.TxnType

/** Slice key for entries without a category when the type has no "Other" category. */
const val NO_CATEGORY = "none"

/** A category the stats can fall back to: the type's "Other". */
data class CategoryRef(val id: String, val name: String)

/** One top-level category's part of a period's income or expenses: a pie slice and a Stats list row. */
data class CategorySlice(
    /** The top-level category id, the fallback's id, or [NO_CATEGORY]. */
    val key: String,
    val name: String,
    val amount: Long,
    /**
     * The sum of the slices with a positive amount; [amount] / [total] is this slice's share. A category
     * whose entries net to zero or less (refunds) has no share: it is listed but not drawn.
     */
    val total: Long,
) {
    private val counted: Boolean get() = amount > 0 && total > 0

    /** Share of the total, 0..1, for drawing only. The labels below round exactly on the integers. */
    val share: Double get() = if (counted) amount.toDouble() / total else 0.0

    /** Realbyte's pie label, one decimal, rounded half up: "28.5 %". */
    val percentLabel: String get() {
        val tenths = if (counted) (amount * 1000 + total / 2) / total else 0
        return "${tenths / 10}.${tenths % 10} %"
    }

    /** Realbyte's list chip, a whole percent rounded half up: "28%". */
    val percentChip: String get() = "${if (counted) (amount * 100 + total / 2) / total else 0}%"
}

/** Prefix of a Stats slice key for a Transfer-Expense transfer: `group:<account group id>`. */
const val GROUP_SLICE = "group:"

/** The category slice of [this]: its top-level category, or [fallback] / [NO_CATEGORY] without one. */
private fun TxnRow.categoryKey(fallback: CategoryRef?): String =
    if (categoryId == null) fallback?.id ?: NO_CATEGORY else parentCategoryId ?: categoryId ?: NO_CATEGORY

/**
 * The slice [this] entry counts toward in Stats' [type] list, or null when it is not part of it. A transfer that
 * counts as an expense belongs to its To account's group (`group:<id>`), one that counts as income to its From group.
 */
fun TxnRow.sliceKey(type: TxnType, fallback: CategoryRef?): String? = when {
    this.type == TxnType.TRANSFER -> if (countedAs() == type) GROUP_SLICE + (if (type == TxnType.EXPENSE) toAccountGroupId else accountGroupId).orEmpty() else null
    this.type == type -> categoryKey(fallback)
    else -> null
}

private fun TxnRow.sliceName(type: TxnType, fallback: CategoryRef?): String = when {
    this.type == TxnType.TRANSFER -> (if (type == TxnType.EXPENSE) toAccountGroupName else accountGroupName).orEmpty()
    categoryId == null -> fallback?.name ?: "(No category)"
    else -> parentCategoryName ?: categoryName.orEmpty()
}

/**
 * Entries of [type] summed per top-level category, largest first, with ties ordered by name.
 * Subcategories count toward their parent. Entries without a category count toward [fallback]
 * ("Other"), or toward a "(No category)" slice when there is none. Transfers that count as [type]
 * (Transfer-Expense accounts) add one slice per account group.
 */
fun categorySlices(rows: List<TxnRow>, type: TxnType, fallback: CategoryRef? = null): List<CategorySlice> {
    val sums = LinkedHashMap<String, Long>()
    val names = HashMap<String, String>()
    for (r in rows) {
        val key = r.sliceKey(type, fallback) ?: continue
        sums[key] = (sums[key] ?: 0L) + r.amountMain
        names.putIfAbsent(key, r.sliceName(type, fallback))
    }
    return toSlices(sums, names)
}

private fun toSlices(sums: Map<String, Long>, names: Map<String, String>): List<CategorySlice> {
    if (sums.values.all { it == 0L }) return emptyList()
    val total = sums.values.filter { it > 0 }.sum()
    return sums.filterValues { it != 0L }.map { (key, amount) -> CategorySlice(key, names.getValue(key), amount, total) }
        .sortedWith(compareByDescending<CategorySlice> { it.amount }.thenBy { it.name })
}

/**
 * Category detail with Subcategory ON: the [type] entries of one top-level category (a Stats slice's [rows]) summed per
 * subcategory, largest first. Entries on the category itself form a slice named after it. Empty unless at least two
 * different subcategories have entries (Realbyte shows no breakdown for fewer).
 */
fun subcategorySlices(rows: List<TxnRow>, type: TxnType): List<CategorySlice> {
    val mine = rows.filter { it.type == type && it.categoryId != null }
    if (mine.filter { it.parentCategoryId != null }.map { it.categoryId }.distinct().size < 2) return emptyList()
    val sums = LinkedHashMap<String, Long>()
    val names = HashMap<String, String>()
    for (r in mine) {
        val id = r.categoryId ?: continue
        sums[id] = (sums[id] ?: 0L) + r.amountMain
        names.putIfAbsent(id, r.categoryName.orEmpty())
    }
    return toSlices(sums, names)
}

/** Sum of the [type] entries that fall in each range (trend charts). */
fun periodSums(rows: List<TxnRow>, ranges: List<DateRange>, type: TxnType): List<Long> =
    ranges.map { range -> rows.filter { it.countedAs() == type && range.contains(it.localDate()) }.sumOf { it.amountMain } }

/** Income and expenses per range (Total Stats bars). */
fun totalsPerRange(rows: List<TxnRow>, ranges: List<DateRange>): List<Totals> =
    ranges.map { range -> totalsOf(rows.filter { range.contains(it.localDate()) }) }

/** The Accounts card on Trans → Total. */
data class TotalBreakdown(
    /** This period's expenses as a whole percent of the previous period's; null when that had none. */
    val comparedPercent: Long?,
    /** Expenses paid from accounts outside Card groups ("Expenses (Cash, Accounts)"). */
    val nonCardExpenses: Long,
    /** Expenses paid from Card-group accounts. */
    val cardExpenses: Long,
    /** Transfers from non-card accounts into a different group type ("Transfer (Cash, Accounts→ )"). */
    val nonCardTransfers: Long,
)

/** [mode] applies to Compared Expenses only, so it matches the Trans summary above it. */
fun totalBreakdown(
    current: List<TxnRow>,
    previous: List<TxnRow>,
    mode: CardExpenseMode = CardExpenseMode.AT_THE_TIME,
): TotalBreakdown {
    var card = 0L
    var nonCard = 0L
    var transfers = 0L
    for (r in current) when (r.type) {
        TxnType.EXPENSE -> if (r.accountGroupType == AccountGroupType.CARD) card += r.amountMain else nonCard += r.amountMain
        TxnType.TRANSFER ->
            if (r.accountGroupType != AccountGroupType.CARD && r.toAccountGroupType != r.accountGroupType) transfers += r.amountMain
        TxnType.INCOME -> Unit
    }
    val last = totalsOf(previous, mode).expense
    val compared = if (last == 0L) null else (totalsOf(current, mode).expense * 100 + last / 2) / last
    return TotalBreakdown(compared, nonCard, card, transfers)
}
