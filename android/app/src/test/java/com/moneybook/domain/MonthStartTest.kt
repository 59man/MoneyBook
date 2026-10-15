package com.moneybook.domain

import com.moneybook.data.db.TxnRow
import com.moneybook.domain.WeekendShift.FOLLOWING_MONDAY
import com.moneybook.domain.WeekendShift.PREVIOUS_FRIDAY
import com.moneybook.shared.model.TxnType
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Test

class MonthStartTest {
    private fun d(text: String) = LocalDate.parse(text)

    // 2026-08-01 is a Saturday, 2026-11-01 a Sunday, 2026-10-01 a Thursday.

    @Test fun `a start day on a weekend moves to the Friday before`() {
        assertEquals(DateRange(d("2026-07-31"), d("2026-09-01")), Periods.month(YearMonth.of(2026, 8), 1, PREVIOUS_FRIDAY))
        assertEquals(DateRange(d("2026-10-01"), d("2026-10-30")), Periods.month(YearMonth.of(2026, 10), 1, PREVIOUS_FRIDAY))
        assertEquals(YearMonth.of(2026, 11), Periods.monthOf(d("2026-10-31"), 1, PREVIOUS_FRIDAY))
        assertEquals(YearMonth.of(2026, 10), Periods.monthOf(d("2026-10-29"), 1, PREVIOUS_FRIDAY))
        assertEquals(YearMonth.of(2026, 8), Periods.monthOf(d("2026-07-31"), 1, PREVIOUS_FRIDAY))
    }

    @Test fun `or to the Monday after`() {
        assertEquals(DateRange(d("2026-08-03"), d("2026-09-01")), Periods.month(YearMonth.of(2026, 8), 1, FOLLOWING_MONDAY))
        assertEquals(DateRange(d("2026-10-01"), d("2026-11-02")), Periods.month(YearMonth.of(2026, 10), 1, FOLLOWING_MONDAY))
        assertEquals(YearMonth.of(2026, 10), Periods.monthOf(d("2026-11-01"), 1, FOLLOWING_MONDAY))
        assertEquals(YearMonth.of(2026, 7), Periods.monthOf(d("2026-08-02"), 1, FOLLOWING_MONDAY))
    }

    @Test fun `no changes keeps today's behaviour and years shift too`() {
        assertEquals(Periods.month(YearMonth.of(2026, 8), 1), Periods.month(YearMonth.of(2026, 8), 1, WeekendShift.NONE))
        // 2026-01-03 is a Saturday and 2027-01-03 a Sunday.
        assertEquals(DateRange(d("2026-01-02"), d("2027-01-01")), Periods.year(2026, 3, PREVIOUS_FRIDAY))
        assertEquals(DateRange(d("2026-08-03"), d("2026-09-01")), StatsPeriod.Month(YearMonth.of(2026, 8)).range(1, FOLLOWING_MONDAY))
    }

    @Test fun `every day of a year belongs to exactly the month whose range holds it`() {
        for (weekend in WeekendShift.entries) for (startDay in listOf(1, 15, 29, 31)) {
            var day = d("2026-01-01")
            while (day.year < 2028) {
                val ym = Periods.monthOf(day, startDay, weekend)
                assertEquals("$day $startDay $weekend", true, Periods.month(ym, startDay, weekend).contains(day))
                day = day.plusDays(1)
            }
        }
    }

    private fun row(id: String, time: String) = TxnRow(
        id = id, type = TxnType.EXPENSE, dateTime = "2026-10-06T$time:00.000", amount = 100, toAmount = null, amountMain = 100,
        currency = "CZK", fee = 0, note = "", description = "", accountId = "a", accountName = "Cash", toAccountId = null,
        toAccountName = null, categoryId = null, categoryName = null, parentCategoryName = null,
    )

    @Test fun `a day's entries can be listed oldest first`() {
        val rows = listOf(row("noon", "12:00"), row("morning", "08:00"), row("evening", "20:00"))
        assertEquals(listOf("evening", "noon", "morning"), groupByDay(rows).single().rows.map { it.id })
        assertEquals(listOf("morning", "noon", "evening"), groupByDay(rows, ascending = true).single().rows.map { it.id })
    }
}
