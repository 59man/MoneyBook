package com.moneybook.domain

import com.moneybook.data.db.DbTime
import com.moneybook.data.db.TxnRow
import com.moneybook.shared.model.TxnType
import java.time.LocalDate

/** Id of the synthetic Carry-over row; it is never stored. */
const val CARRY_OVER_ID = "carry-over"

val TxnRow.isCarryOver: Boolean get() = id == CARRY_OVER_ID

/** Configuration → Carry-over Setting: everything earned minus spent before the period, counted like Trans's totals. */
fun carryOver(rowsBefore: List<TxnRow>, mode: CardExpenseMode = CardExpenseMode.AT_THE_TIME): Long = totalsOf(rowsBefore, mode).total

/** The Carry-over's share of a header: nothing when [value] is null (off or filtered). */
fun carryTotals(value: Long?) = when {
    value == null -> Totals()
    value > 0 -> Totals(income = value)
    else -> Totals(expense = -value)
}

private fun carryRow(date: LocalDate, value: Long, currency: String) = TxnRow(
    id = CARRY_OVER_ID, type = if (value < 0) TxnType.EXPENSE else TxnType.INCOME, dateTime = DbTime.startOf(date),
    amount = kotlin.math.abs(value), toAmount = null, amountMain = kotlin.math.abs(value), currency = currency, fee = 0,
    note = "", description = "", accountId = "", accountName = "", toAccountId = null, toAccountName = null,
    categoryId = null, categoryName = null, parentCategoryName = null,
)

/**
 * Adds the Carry-over row to the period's first day, creating the day when it has no entries (days stay newest first).
 * Daily puts the row last in the day ([appendLast]), Calendar's day list first. The day's totals include it.
 */
fun withCarryOver(days: List<DayGroup>, periodStart: LocalDate, value: Long, currency: String, appendLast: Boolean): List<DayGroup> {
    val row = carryRow(periodStart, value, currency)
    val existing = days.firstOrNull { it.date == periodStart }
    val merged = if (existing == null) {
        DayGroup(periodStart, carryTotals(value), listOf(row))
    } else {
        DayGroup(periodStart, existing.totals + carryTotals(value), if (appendLast) existing.rows + row else listOf(row) + existing.rows)
    }
    return (days.filter { it.date != periodStart } + merged).sortedByDescending { it.date }
}

/** The calendar cell of the period's first day includes the carry-over. */
fun List<CalendarCell>.withCarryOver(periodStart: LocalDate, value: Long): List<CalendarCell> =
    map { if (it.date == periodStart) it.copy(totals = it.totals + carryTotals(value)) else it }
