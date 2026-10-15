package com.moneybook.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

enum class StatsMode(val label: String) {
    WEEKLY("Weekly"),
    MONTHLY("Monthly"),
    ANNUALLY("Annually"),
    PERIOD("Period"),
    /** Not in Realbyte: every entry the app holds. */
    ALL_TIME("All Time"),
}

/** The window a Stats screen shows. [Custom] is Realbyte's "Period": a date range with both ends included. */
sealed interface StatsPeriod {
    val mode: StatsMode

    /** [start] is a week-start day. */
    data class Week(val start: LocalDate) : StatsPeriod {
        override val mode: StatsMode get() = StatsMode.WEEKLY
    }

    data class Month(val month: YearMonth) : StatsPeriod {
        override val mode: StatsMode get() = StatsMode.MONTHLY
    }

    data class Year(val year: Int) : StatsPeriod {
        override val mode: StatsMode get() = StatsMode.ANNUALLY
    }

    /** Build with [StatsPeriod.custom], which keeps [first] <= [last]. */
    data class Custom(val first: LocalDate, val last: LocalDate) : StatsPeriod {
        override val mode: StatsMode get() = StatsMode.PERIOD
    }

    /** Every entry, whatever its date. The years 1 and 9999 keep the range's text form fixed-width. */
    data object AllTime : StatsPeriod {
        override val mode: StatsMode get() = StatsMode.ALL_TIME
    }

    /** Whether ‹ › step this period; a custom period and all time stay put. */
    val movable: Boolean get() = this !is Custom && this !is AllTime

    fun range(monthStartDay: Int, weekend: WeekendShift = WeekendShift.NONE): DateRange = when (this) {
        is Week -> DateRange(start, start.plusDays(7))
        is Month -> Periods.month(month, monthStartDay, weekend)
        is Year -> Periods.year(year, monthStartDay, weekend)
        is Custom -> DateRange(first, last.plusDays(1))
        AllTime -> DateRange(LocalDate.of(1, 1, 1), LocalDate.of(9999, 12, 31))
    }

    /** [n] periods later (negative: earlier). A custom period and all time do not move. */
    fun shift(n: Long): StatsPeriod = when (this) {
        is Week -> Week(start.plusWeeks(n))
        is Month -> Month(month.plusMonths(n))
        is Year -> Year(year + n.toInt())
        is Custom, AllTime -> this
    }

    /** [count] periods ending with this one, oldest first (trend charts). */
    fun lastPeriods(count: Int): List<StatsPeriod> = (count - 1 downTo 0).map { shift(-it.toLong()) }

    fun title(): String = when (this) {
        is Week -> Formats.weekTitle(start)
        is Month -> Formats.monthTitle(month)
        is Year -> year.toString()
        is Custom -> "${Formats.shortDate(first)} ~ ${Formats.shortDate(last)}"
        AllTime -> "All time"
    }

    /** The label under this period's point on a trend chart. */
    fun axisLabel(): String = when (this) {
        is Week -> Formats.monthDayShort(start)
        is Month -> Formats.monthName(month)
        is Year -> year.toString()
        is Custom -> Formats.shortDate(first)
        AllTime -> "All time"
    }

    /** Text form for navigation routes; [decode] reverses it. */
    fun encode(): String = when (this) {
        is Week -> "W$start"
        is Month -> "M$month"
        is Year -> "Y$year"
        is Custom -> "C$first/$last"
        AllTime -> "A"
    }

    companion object {
        /** The period of [mode] that contains [today]; Period mode starts on this calendar year. */
        fun current(mode: StatsMode, today: LocalDate, monthStartDay: Int, weekStart: DayOfWeek, weekend: WeekendShift = WeekendShift.NONE): StatsPeriod = when (mode) {
            StatsMode.WEEKLY -> Week(Periods.weekStart(today, weekStart))
            StatsMode.MONTHLY -> Month(Periods.monthOf(today, monthStartDay, weekend))
            StatsMode.ANNUALLY -> Year(Periods.monthOf(today, monthStartDay, weekend).year)
            StatsMode.PERIOD -> Custom(LocalDate.of(today.year, 1, 1), LocalDate.of(today.year, 12, 31))
            StatsMode.ALL_TIME -> AllTime
        }

        fun decode(text: String): StatsPeriod {
            val body = text.substring(1)
            return when (text.first()) {
                'W' -> Week(LocalDate.parse(body))
                'M' -> Month(YearMonth.parse(body))
                'Y' -> Year(body.toInt())
                'A' -> AllTime
                'C' -> body.split('/').let { (a, b) -> custom(LocalDate.parse(a), LocalDate.parse(b)) }
                else -> throw IllegalArgumentException("Unknown stats period: $text")
            }
        }

        /** A custom period from two picked days, in either order. */
        fun custom(a: LocalDate, b: LocalDate): Custom = if (b.isBefore(a)) Custom(b, a) else Custom(a, b)
    }
}
