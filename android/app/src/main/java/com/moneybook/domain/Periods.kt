package com.moneybook.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

data class DateRange(val start: LocalDate, val endExclusive: LocalDate) {
    val lastDay: LocalDate get() = endExclusive.minusDays(1)
    fun contains(date: LocalDate): Boolean = !date.isBefore(start) && date.isBefore(endExclusive)
}

/** Configuration → Monthly Start Date → "If monthly start date is weekend". */
enum class WeekendShift(val label: String) {
    NONE("No changes"),
    PREVIOUS_FRIDAY("Previous Friday"),
    FOLLOWING_MONDAY("Following Monday"),
}

object Periods {
    /**
     * The "month" labelled [ym] starts on [startDay] (clamped to the month's length), like Realbyte's Monthly
     * Start Date, moved off a weekend by [weekend].
     */
    fun month(ym: YearMonth, startDay: Int, weekend: WeekendShift = WeekendShift.NONE): DateRange =
        DateRange(startOf(ym, startDay, weekend), startOf(ym.plusMonths(1), startDay, weekend))

    fun year(year: Int, startDay: Int, weekend: WeekendShift = WeekendShift.NONE): DateRange =
        DateRange(startOf(YearMonth.of(year, 1), startDay, weekend), startOf(YearMonth.of(year + 1, 1), startDay, weekend))

    fun monthOf(date: LocalDate, startDay: Int, weekend: WeekendShift = WeekendShift.NONE): YearMonth {
        val ym = YearMonth.from(date)
        // A shifted start can fall in a neighbouring calendar month (start day 31 moved to the Monday after
        // lands two months on), so later months are checked first.
        return listOf(ym.plusMonths(1), ym, ym.minusMonths(1)).firstOrNull { !date.isBefore(startOf(it, startDay, weekend)) }
            ?: ym.minusMonths(2)
    }

    fun weekStart(date: LocalDate, weekStart: DayOfWeek): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(weekStart))

    /** Full weeks touching [range], newest first (Realbyte's Monthly tab order). */
    fun weeksOverlapping(range: DateRange, weekStart: DayOfWeek): List<DateRange> {
        val weeks = ArrayList<DateRange>()
        var start = weekStart(range.start, weekStart)
        while (start.isBefore(range.endExclusive)) {
            weeks += DateRange(start, start.plusDays(7))
            start = start.plusDays(7)
        }
        return weeks.reversed()
    }

    private fun startOf(ym: YearMonth, startDay: Int, weekend: WeekendShift): LocalDate {
        val day = ym.atDay(startDay.coerceIn(1, ym.lengthOfMonth()))
        return when (weekend) {
            WeekendShift.NONE -> day
            WeekendShift.PREVIOUS_FRIDAY -> when (day.dayOfWeek) {
                DayOfWeek.SATURDAY -> day.minusDays(1)
                DayOfWeek.SUNDAY -> day.minusDays(2)
                else -> day
            }
            WeekendShift.FOLLOWING_MONDAY -> when (day.dayOfWeek) {
                DayOfWeek.SATURDAY -> day.plusDays(2)
                DayOfWeek.SUNDAY -> day.plusDays(1)
                else -> day
            }
        }
    }
}
