package com.moneybook.domain

import com.moneybook.data.db.TxnRow
import com.moneybook.shared.model.TxnType
import java.time.LocalDate
import java.time.YearMonth

/** Account detail's periods: a card's statement periods, or the months under the Monthly Start Date. */
class StatementPeriods(private val settlement: Int?, private val startDay: Int, private val weekend: WeekendShift) {
    fun month(ym: YearMonth): DateRange =
        if (settlement != null) CardStatement.ofMonth(ym, settlement) else Periods.month(ym, startDay, weekend)

    fun year(year: Int): DateRange = DateRange(month(YearMonth.of(year, 1)).start, month(YearMonth.of(year, 12)).endExclusive)

    fun monthOf(date: LocalDate): YearMonth {
        var ym = YearMonth.from(date)
        while (date.isBefore(month(ym).start)) ym = ym.minusMonths(1)
        while (!date.isBefore(month(ym).endExclusive)) ym = ym.plusMonths(1)
        return ym
    }
}

fun txnEffect(row: TxnRow, accountId: String): Long = when (row.type) {
    TxnType.INCOME -> if (row.accountId == accountId) row.accountSide else 0
    TxnType.EXPENSE -> if (row.accountId == accountId) -row.accountSide else 0
    TxnType.TRANSFER ->
        (if (row.accountId == accountId) -(row.accountSide + row.fee) else 0) +
            (if (row.toAccountId == accountId) row.toAmount ?: row.amount else 0)
}

/** The balance just after each row; [endBalance] is the balance after the newest one. */
fun runningBalances(rows: List<TxnRow>, accountId: String, endBalance: Long): Map<String, Long> {
    val newestFirst = rows.withIndex().sortedWith(compareByDescending<IndexedValue<TxnRow>> { it.value.dateTime }.thenBy { it.index })
    var balance = endBalance
    val out = HashMap<String, Long>(rows.size)
    for ((_, row) in newestFirst) {
        out[row.id] = balance
        balance -= txnEffect(row, accountId)
    }
    return out
}

data class PeriodLine(val range: DateRange, val deposit: Long, val withdrawal: Long, val endBalance: Long) {
    val total: Long get() = deposit - withdrawal
}

fun periodLines(ranges: List<DateRange>, rows: List<TxnRow>, accountId: String, endBalance: Long): List<PeriodLine> {
    val sums = ranges.map { r -> accountPeriodSummary(accountId, rows.filter { r.contains(it.localDate()) }) }
    var balance = endBalance
    val ends = LongArray(ranges.size)
    for (i in ranges.indices.reversed()) {
        ends[i] = balance
        balance -= sums[i].total
    }
    return ranges.indices.map { PeriodLine(ranges[it], sums[it].deposit, sums[it].withdrawal, ends[it]) }
}
