package com.moneybook.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Test

class StatsPeriodTest {
    private val today = LocalDate.of(2026, 9, 26) // a Saturday

    private fun current(mode: StatsMode, startDay: Int = 1, weekStart: DayOfWeek = DayOfWeek.SUNDAY) =
        StatsPeriod.current(mode, today, startDay, weekStart)

    @Test fun `current periods follow the start day settings`() {
        assertEquals(StatsPeriod.Week(LocalDate.of(2026, 9, 20)), current(StatsMode.WEEKLY))
        assertEquals(StatsPeriod.Week(LocalDate.of(2026, 9, 21)), current(StatsMode.WEEKLY, weekStart = DayOfWeek.MONDAY))
        assertEquals(StatsPeriod.Month(YearMonth.of(2026, 9)), current(StatsMode.MONTHLY))
        // With months starting on the 27th, Sep 26 still belongs to the month labelled August.
        assertEquals(StatsPeriod.Month(YearMonth.of(2026, 8)), current(StatsMode.MONTHLY, startDay = 27))
        assertEquals(StatsPeriod.Year(2026), current(StatsMode.ANNUALLY))
        assertEquals(StatsPeriod.Custom(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)), current(StatsMode.PERIOD))
    }

    @Test fun `ranges honour the month start day and include the custom end day`() {
        assertEquals(DateRange(LocalDate.of(2026, 9, 5), LocalDate.of(2026, 10, 5)), StatsPeriod.Month(YearMonth.of(2026, 9)).range(5))
        assertEquals(DateRange(LocalDate.of(2026, 1, 5), LocalDate.of(2027, 1, 5)), StatsPeriod.Year(2026).range(5))
        assertEquals(DateRange(LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 27)), StatsPeriod.Week(LocalDate.of(2026, 9, 20)).range(1))
        val oneDay = StatsPeriod.custom(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 1))
        assertEquals(DateRange(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 2)), oneDay.range(1))
    }

    @Test fun `a custom period picked backwards is put in order`() {
        assertEquals(
            StatsPeriod.Custom(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30)),
            StatsPeriod.custom(LocalDate.of(2026, 6, 30), LocalDate.of(2026, 1, 1)),
        )
    }

    @Test fun `shifting crosses year boundaries and leaves custom periods alone`() {
        assertEquals(StatsPeriod.Month(YearMonth.of(2027, 1)), StatsPeriod.Month(YearMonth.of(2026, 12)).shift(1))
        assertEquals(StatsPeriod.Week(LocalDate.of(2025, 12, 28)), StatsPeriod.Week(LocalDate.of(2026, 1, 4)).shift(-1))
        assertEquals(StatsPeriod.Year(2025), StatsPeriod.Year(2026).shift(-1))
        val custom = StatsPeriod.custom(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1))
        assertEquals(custom, custom.shift(1))
    }

    @Test fun `last periods run oldest first and end with this one`() {
        assertEquals(
            listOf(7, 8, 9).map { StatsPeriod.Month(YearMonth.of(2026, it)) },
            StatsPeriod.Month(YearMonth.of(2026, 9)).lastPeriods(3),
        )
    }

    @Test fun `titles and axis labels match Realbyte`() {
        assertEquals("9.20 ~ 9.26", StatsPeriod.Week(LocalDate.of(2026, 9, 20)).title())
        assertEquals("Sep 2026", StatsPeriod.Month(YearMonth.of(2026, 9)).title())
        assertEquals("2026", StatsPeriod.Year(2026).title())
        assertEquals("1/1/26 ~ 12/31/26", StatsPeriod.custom(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)).title())
        assertEquals("Feb", StatsPeriod.Month(YearMonth.of(2026, 2)).axisLabel())
        assertEquals("9.20", StatsPeriod.Week(LocalDate.of(2026, 9, 20)).axisLabel())
        assertEquals("2025", StatsPeriod.Year(2025).axisLabel())
        assertEquals("9.1.26  ~  9.30.26", Formats.rangeWithYear(DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1))))
    }

    @Test fun `periods survive the route encoding`() {
        listOf(
            StatsPeriod.Week(LocalDate.of(2026, 9, 20)),
            StatsPeriod.Month(YearMonth.of(2026, 9)),
            StatsPeriod.Year(2026),
            StatsPeriod.custom(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)),
        ).forEach { assertEquals(it, StatsPeriod.decode(it.encode())) }
    }

    @Test fun `all time covers every date, does not move and round-trips`() {
        val all = current(StatsMode.ALL_TIME)
        assertEquals(StatsPeriod.AllTime, all)
        assertEquals(DateRange(LocalDate.of(1, 1, 1), LocalDate.of(9999, 12, 31)), all.range(5))
        assertEquals(all, all.shift(3))
        assertEquals("All time", all.title())
        assertEquals(false, all.movable)
        assertEquals(true, StatsPeriod.Month(YearMonth.of(2026, 9)).movable)
        assertEquals(false, StatsPeriod.custom(today, today).movable)
        assertEquals(all, StatsPeriod.decode(all.encode()))
    }
}
