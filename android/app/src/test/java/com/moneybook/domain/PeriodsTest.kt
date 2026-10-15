package com.moneybook.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeriodsTest {
    private fun d(s: String) = LocalDate.parse(s)

    @Test fun `calendar month with start day 1`() {
        val r = Periods.month(YearMonth.of(2026, 9), 1)
        assertEquals(DateRange(d("2026-09-01"), d("2026-10-01")), r)
        assertEquals(d("2026-09-30"), r.lastDay)
        assertTrue(r.contains(d("2026-09-30")))
        assertFalse(r.contains(d("2026-10-01")))
    }

    @Test fun `month start day 25 runs into next month`() {
        assertEquals(DateRange(d("2026-09-25"), d("2026-10-25")), Periods.month(YearMonth.of(2026, 9), 25))
    }

    @Test fun `month start day clamps to short months`() {
        assertEquals(DateRange(d("2026-02-28"), d("2026-03-31")), Periods.month(YearMonth.of(2026, 2), 31))
        assertEquals(DateRange(d("2026-01-31"), d("2026-02-28")), Periods.month(YearMonth.of(2026, 1), 31))
    }

    @Test fun `month of a date respects start day`() {
        assertEquals(YearMonth.of(2026, 8), Periods.monthOf(d("2026-09-24"), 25))
        assertEquals(YearMonth.of(2026, 9), Periods.monthOf(d("2026-09-25"), 25))
        assertEquals(YearMonth.of(2026, 2), Periods.monthOf(d("2026-02-28"), 31))
    }

    @Test fun `year range`() {
        assertEquals(DateRange(d("2026-01-01"), d("2027-01-01")), Periods.year(2026, 1))
    }

    @Test fun `weeks overlapping September 2026 match Realbyte`() {
        val weeks = Periods.weeksOverlapping(Periods.month(YearMonth.of(2026, 9), 1), DayOfWeek.SUNDAY)
        assertEquals(
            listOf("2026-09-27", "2026-09-20", "2026-09-13", "2026-09-06", "2026-08-30").map(::d),
            weeks.map { it.start },
        )
        assertTrue(weeks.all { it.endExclusive == it.start.plusDays(7) })
    }

    @Test fun `week start monday`() {
        assertEquals(d("2026-09-21"), Periods.weekStart(d("2026-09-26"), DayOfWeek.MONDAY))
        assertEquals(d("2026-09-26"), Periods.weekStart(d("2026-09-26"), DayOfWeek.SATURDAY))
    }
}
