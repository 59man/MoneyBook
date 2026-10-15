package com.moneybook.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatsTest {
    private val sat = LocalDate.of(2026, 9, 26)

    @Test fun `strings match Realbyte captures`() {
        assertEquals("Sep 2026", Formats.monthTitle(YearMonth.of(2026, 9)))
        assertEquals("Sep", Formats.monthName(YearMonth.of(2026, 9)))
        assertEquals("Sat", Formats.weekday(sat))
        assertEquals("09.2026", Formats.dayHeaderDate(sat))
        assertEquals("9/26/26 (Sat)", Formats.editDate(sat))
        assertEquals("19:44", Formats.time(LocalTime.of(19, 44, 59)))
        assertEquals("9.1", Formats.calendarDay(LocalDate.of(2026, 9, 1)))
        assertEquals("26", Formats.calendarDay(sat))
        assertEquals("09.01  ~  09.30", Formats.rangeShort(DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1))))
        assertEquals("Sun", Formats.weekdayShort(DayOfWeek.SUNDAY))
    }

    @Test fun `weekday headers start at the configured day`() {
        assertEquals(DayOfWeek.SUNDAY, Formats.weekdays(DayOfWeek.SUNDAY).first())
        assertEquals(DayOfWeek.SATURDAY, Formats.weekdays(DayOfWeek.SUNDAY).last())
        assertEquals(DayOfWeek.SUNDAY, Formats.weekdays(DayOfWeek.MONDAY).last())
    }

    @Test fun `sync time is month day year and 24 hour clock`() {
        val ms = java.time.ZonedDateTime.of(2026, 10, 5, 9, 7, 0, 0, java.time.ZoneId.of("Europe/Prague")).toInstant().toEpochMilli()
        assertEquals("Oct 5, 2026 09:07", Formats.syncTime(ms, java.time.ZoneId.of("Europe/Prague")))
        assertEquals("Oct 5, 2026 07:07", Formats.syncTime(ms, java.time.ZoneOffset.UTC))
    }
}
