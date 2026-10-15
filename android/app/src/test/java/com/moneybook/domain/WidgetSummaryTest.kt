package com.moneybook.domain

import com.moneybook.data.db.TxnRow
import com.moneybook.shared.model.TxnType
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetSummaryTest {
    private fun d(s: String) = LocalDate.parse(s)
    private fun range(a: String, b: String) = DateRange(d(a), d(b))
    private fun row(id: String, type: TxnType, date: String, amount: Long) = TxnRow(
        id = id, type = type, dateTime = "${date}T09:00:00.000", amount = amount, toAmount = null, amountMain = amount,
        currency = "CZK", fee = 0, note = "", description = "", accountId = "cash", accountName = "Cash",
        toAccountId = null, toAccountName = null, categoryId = null, categoryName = null, parentCategoryName = null,
    )

    @Test fun `realbyte compares this month so far with the same days of last month`() {
        val r = momRanges(d("2026-10-07"), startDay = 1)
        assertEquals(range("2026-10-01", "2026-11-01"), r.month)
        assertEquals(range("2026-10-01", "2026-10-08"), r.soFar)
        assertEquals(range("2026-09-01", "2026-09-08"), r.previous)
    }

    @Test fun `start day 25 compares thirteen days with thirteen days`() {
        val r = momRanges(d("2026-10-07"), startDay = 25)
        assertEquals(range("2026-09-25", "2026-10-25"), r.month)
        assertEquals(range("2026-09-25", "2026-10-08"), r.soFar)
        assertEquals(range("2026-08-25", "2026-09-07"), r.previous)
    }

    @Test fun `a weekend start moved to Friday starts the month on that Friday`() {
        // 2026-10-25 is a Sunday, so October's month starts on Friday 2026-10-23.
        val r = momRanges(d("2026-10-24"), startDay = 25, weekend = WeekendShift.PREVIOUS_FRIDAY)
        assertEquals(d("2026-10-23"), r.month.start)
        assertEquals(range("2026-10-23", "2026-10-25"), r.soFar)
        assertEquals(range("2026-09-25", "2026-09-27"), r.previous)
    }

    @Test fun `the previous span never runs past the end of a shorter month`() {
        assertEquals(range("2026-11-01", "2026-12-01"), momRanges(d("2026-12-31"), startDay = 1).previous)
    }

    @Test fun `the change is rounded half up and colored by what is good for the user`() {
        assertEquals(Mom(-5_000, 50, good = true), mom(current = 5_000, previous = 10_000, upIsGood = false))
        // Capture 55: expenses 28,903.00 against 18,997.00.
        assertEquals(Mom(990_600, 52, good = false), mom(current = 2_890_300, previous = 1_899_700, upIsGood = false))
        assertEquals(Mom(50_000, 25, good = true), mom(current = 250_000, previous = 200_000, upIsGood = true))
        assertEquals(Mom(-7, 88, good = false), mom(current = 1, previous = 8, upIsGood = true))
    }

    @Test fun `no change has no arrow, and a month after nothing has no percentage`() {
        assertEquals(Mom(0, null, null), mom(0, 0, upIsGood = true))
        assertEquals(Mom(0, null, null), mom(300, 300, upIsGood = false))
        assertEquals(Mom(500, null, good = true), mom(500, 0, upIsGood = true))
    }

    @Test fun `this month counts later entries but the change only counts up to today`() {
        val rows = listOf(
            row("a", TxnType.EXPENSE, "2026-09-03", 10_000), row("b", TxnType.EXPENSE, "2026-09-20", 20_000),
            row("c", TxnType.EXPENSE, "2026-10-05", 5_000), row("d", TxnType.EXPENSE, "2026-10-20", 7_000),
            row("t", TxnType.TRANSFER, "2026-10-02", 99_000),
        )
        val s = widgetSummary(rows, momRanges(d("2026-10-07"), startDay = 1))
        assertEquals(Totals(income = 0, expense = 12_000), s.month)
        assertEquals(-12_000L, s.month.total)
        assertEquals(Mom(-5_000, 50, good = true), s.expense)
        assertEquals(Mom(0, null, null), s.income)
    }

    @Test fun `the widget counts a Transfer-Expense transfer as an expense`() {
        val transfer = row("t", TxnType.TRANSFER, "2026-10-03", 4_000).copy(toAccountId = "save", toTransferExpense = true)
        val s = widgetSummary(listOf(row("e", TxnType.EXPENSE, "2026-10-02", 1_000), transfer), momRanges(d("2026-10-07"), 1))
        assertEquals(Totals(0, 5_000), s.month)
    }
}
