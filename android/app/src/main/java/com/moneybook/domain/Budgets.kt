package com.moneybook.domain

import com.moneybook.data.db.TxnRow
import com.moneybook.shared.model.TxnType
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** The Total Budget's name, on the Total page and in Budget Setting (Realbyte "main_summary_list_total"). */
const val TOTAL_BUDGET = "Total Budget"

/** One amount row: [amount] applies from month [fromMonth] (yyyymm, or [Budgets.ALWAYS]) until a later row. */
data class BudgetRow(val fromMonth: Int, val amount: Long)

/** A budget with its live rows; [categoryId] null is the Total Budget. */
data class BudgetItem(val categoryId: String?, val name: String, val rows: List<BudgetRow>)

/** One row of Trans → Total's Budget section for one month. [synthetic]: the sum Realbyte shows when no Total Budget is set. */
data class BudgetLine(val categoryId: String?, val name: String, val amount: Long, val used: Long, val synthetic: Boolean = false) {
    val remaining: Long get() = amount - used
    val over: Boolean get() = used > amount
    val rate: Int? get() = Budgets.rate(amount, used)
}

/** Realbyte 4.12.13's monthly budgets (BudgetService/BudgetRepository), measured on realbyte_34. */
object Budgets {
    const val ALWAYS = 0

    fun key(ym: YearMonth): Int = ym.year * 100 + ym.monthValue
    fun yearMonth(key: Int): YearMonth = YearMonth.of(key / 100, key % 100)
    fun next(key: Int): Int = key(yearMonth(key).plusMonths(1))

    /** The budget month of a Trans period: its start month, or its end month when it starts on day 20 or later. */
    fun monthOf(period: DateRange): Int =
        key(YearMonth.from(if (period.start.dayOfMonth >= 20) period.lastDay else period.start))

    /** The budget month holding [today] under the Monthly Start Date. */
    fun current(today: LocalDate, startDay: Int, weekend: WeekendShift): Int =
        monthOf(Periods.month(Periods.monthOf(today, startDay, weekend), startDay, weekend))

    /** The amount for [month]: the row with the latest start at or before it; 0 when none. */
    fun amountFor(rows: List<BudgetRow>, month: Int): Long =
        rows.filter { it.fromMonth <= month }.maxByOrNull { it.fromMonth }?.amount ?: 0

    /** "Default Budget": the latest row's amount, which every later month gets. */
    fun defaultAmount(rows: List<BudgetRow>): Long = rows.maxByOrNull { it.fromMonth }?.amount ?: 0

    /** All rows after setting [month] to [amount]. Month+1 is pinned to its old amount first, so only [month] changes. */
    fun editMonth(rows: List<BudgetRow>, month: Int, amount: Long): List<BudgetRow> =
        replace(pinned(rows, next(month)), BudgetRow(month, amount))

    /**
     * All rows after setting the Default to [amount] in month [current]: month current+1 is pinned, then every row after
     * [current] that still has the old Default takes [amount]. Months set to something else by hand keep their amount.
     */
    fun editDefault(rows: List<BudgetRow>, current: Int, amount: Long): List<BudgetRow> {
        val old = defaultAmount(rows)
        return pinned(rows, next(current)).map { if (it.fromMonth > current && it.amount == old) it.copy(amount = amount) else it }
    }

    /** Whole percent, cut (Realbyte casts to int); null ("-") when there is no budget or the rate reaches 10000. */
    fun rate(amount: Long, used: Long): Int? {
        if (amount <= 0) return null
        val r = used * 100 / amount
        return if (r >= 10_000) null else r.toInt()
    }

    /** Where today sits along [range] (0..1], counting today; null outside it. */
    fun todayFraction(range: DateRange, today: LocalDate): Float? {
        if (!range.contains(today)) return null
        val length = ChronoUnit.DAYS.between(range.start, range.endExclusive)
        return (ChronoUnit.DAYS.between(range.start, today) + 1).toFloat() / length
    }

    private fun pinned(rows: List<BudgetRow>, month: Int): List<BudgetRow> =
        if (rows.any { it.fromMonth == month }) rows else rows + BudgetRow(month, amountFor(rows, month))

    private fun replace(rows: List<BudgetRow>, row: BudgetRow): List<BudgetRow> =
        (rows.filter { it.fromMonth != row.fromMonth } + row).sortedBy { it.fromMonth }
}

/** Expenses (main amounts) per top-level category; subcategories count toward their parent. */
fun usedByCategory(rows: List<TxnRow>): Map<String, Long> =
    rows.filter { it.type == TxnType.EXPENSE && it.categoryId != null }
        .groupBy { it.parentCategoryId ?: it.categoryId!! }
        .mapValues { (_, list) -> list.sumOf { it.amountMain } }

/**
 * The Total page's lines for budget [month], spending from [rows] (the period's entries). Budgets at 0 that month are left
 * out. A Total Budget counts every expense; without one, a synthetic first line sums the shown category budgets.
 */
fun budgetLines(items: List<BudgetItem>, month: Int, rows: List<TxnRow>): List<BudgetLine> {
    val used = usedByCategory(rows)
    val categories = items.filter { it.categoryId != null }
        .map { BudgetLine(it.categoryId, it.name, Budgets.amountFor(it.rows, month), used[it.categoryId] ?: 0) }
        .filter { it.amount != 0L }
    val totalAmount = items.firstOrNull { it.categoryId == null }?.let { Budgets.amountFor(it.rows, month) } ?: 0
    val total = when {
        totalAmount != 0L -> BudgetLine(null, TOTAL_BUDGET, totalAmount, totalsOf(rows).expense)
        categories.isNotEmpty() -> BudgetLine(null, TOTAL_BUDGET, categories.sumOf { it.amount }, categories.sumOf { it.used }, synthetic = true)
        else -> return emptyList()
    }
    return listOf(total) + categories
}

/** One point of budget detail's chart: a Trans month's spending in the category and its budget. */
data class BudgetYearPoint(val label: String, val month: Int, val spent: Long, val budget: Long)

/** The 12 Trans months of [year]: each one's spending in [categoryId] (subcategories included) and its budget, labelled by budget month. */
fun budgetYear(year: Int, startDay: Int, weekend: WeekendShift, rows: List<TxnRow>, categoryId: String, budget: List<BudgetRow>): List<BudgetYearPoint> =
    (1..12).map { m ->
        val period = Periods.month(YearMonth.of(year, m), startDay, weekend)
        val month = Budgets.monthOf(period)
        val spent = rows.filter { it.type == TxnType.EXPENSE && (it.parentCategoryId ?: it.categoryId) == categoryId && period.contains(it.localDate()) }
            .sumOf { it.amountMain }
        BudgetYearPoint(Formats.monthName(Budgets.yearMonth(month)), month, spent, Budgets.amountFor(budget, month))
    }
