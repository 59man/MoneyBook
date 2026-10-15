package com.moneybook.domain

import com.moneybook.data.db.TxnRow
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * The summary widget's spans (Realbyte's "This Month" and "vs. MoM", measured on its widget): the whole current
 * month, the part of it up to and including today, and as many days from the previous month's start, never past
 * that month's end.
 */
data class MomRanges(val month: DateRange, val soFar: DateRange, val previous: DateRange)

/** A change vs. last month: [percent] is null when last month was 0, [good] null when nothing changed. */
data class Mom(val delta: Long, val percent: Int?, val good: Boolean?)

/** This Month's totals, and the month-so-far change of income and of expenses. */
data class WidgetSummary(val month: Totals, val income: Mom, val expense: Mom)

fun momRanges(today: LocalDate, startDay: Int, weekend: WeekendShift = WeekendShift.NONE): MomRanges {
    val ym = Periods.monthOf(today, startDay, weekend)
    val month = Periods.month(ym, startDay, weekend)
    val previous = Periods.month(ym.minusMonths(1), startDay, weekend)
    val days = ChronoUnit.DAYS.between(month.start, today) + 1
    val previousEnd = minOf(previous.start.plusDays(days), previous.endExclusive)
    return MomRanges(month, DateRange(month.start, today.plusDays(1)), DateRange(previous.start, previousEnd))
}

/** [upIsGood]: income going up is good, expenses going up bad. The percentage is of [previous], rounded half up. */
fun mom(current: Long, previous: Long, upIsGood: Boolean): Mom {
    val delta = current - previous
    if (delta == 0L) return Mom(0, null, null)
    val percent = if (previous == 0L) null else ((200 * abs(delta) + previous) / (2 * previous)).toInt()
    return Mom(delta, percent, (delta > 0) == upIsGood)
}

/** [rows] must cover ranges.previous.start until ranges.month.endExclusive. Transfers count as neither. */
fun widgetSummary(rows: List<TxnRow>, ranges: MomRanges, mode: CardExpenseMode = CardExpenseMode.AT_THE_TIME): WidgetSummary {
    fun totals(range: DateRange) = totalsOf(rows.filter { range.contains(it.localDate()) }, mode)
    val now = totals(ranges.soFar)
    val before = totals(ranges.previous)
    return WidgetSummary(
        totals(ranges.month),
        income = mom(now.income, before.income, upIsGood = true),
        expense = mom(now.expense, before.expense, upIsGood = false),
    )
}
