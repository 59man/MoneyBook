package com.moneybook.domain

import com.moneybook.data.db.TxnRow
import com.moneybook.shared.model.TxnType
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountStatementTest {
    private fun d(s: String) = LocalDate.parse(s)
    private fun r(a: String, lastDay: String) = DateRange(d(a), d(lastDay).plusDays(1))
    private fun row(id: String, type: TxnType, dateTime: String, amount: Long, account: String = "card", to: String? = null) = TxnRow(
        id = id, type = type, dateTime = dateTime, amount = amount, toAmount = null, amountMain = amount, currency = "CZK", fee = 0,
        note = "", description = "", accountId = account, accountName = account, toAccountId = to, toAccountName = to,
        categoryId = null, categoryName = null, parentCategoryName = null,
    )

    // Realbyte's card detail after the probe: S 20, charges Aug 5 / Sep 10 / Oct 7, payments 4.00 and 40.00 on Oct 7.
    private val rows = listOf(
        row("pay40", TxnType.TRANSFER, "2026-10-07T19:55:00.000", 4_000, account = "bank", to = "card"),
        row("pay4", TxnType.TRANSFER, "2026-10-07T19:54:00.000", 400, account = "bank", to = "card"),
        row("oct", TxnType.EXPENSE, "2026-10-07T19:52:00.000", 5_000),
        row("sep", TxnType.EXPENSE, "2026-09-10T19:53:00.000", 3_000),
        row("aug", TxnType.EXPENSE, "2026-08-05T19:54:00.000", 400),
    )
    private val card = StatementPeriods(20, 1, WeekendShift.NONE)

    @Test fun `card periods`() {
        assertEquals(r("2026-09-20", "2026-10-19"), card.month(YearMonth.of(2026, 10)))
        assertEquals(r("2025-12-20", "2026-12-19"), card.year(2026))
        assertEquals(YearMonth.of(2026, 11), card.monthOf(d("2026-10-25")))
        assertEquals(YearMonth.of(2026, 10), card.monthOf(d("2026-10-07")))
    }

    @Test fun `cash periods follow the Monthly Start Date`() {
        val cash = StatementPeriods(null, 25, WeekendShift.NONE)
        assertEquals(Periods.month(YearMonth.of(2026, 10), 25, WeekendShift.NONE), cash.month(YearMonth.of(2026, 10)))
        assertEquals(YearMonth.of(2026, 10), cash.monthOf(cash.month(YearMonth.of(2026, 10)).start))
    }

    @Test fun `running balances newest first`() {
        val b = runningBalances(rows, "card", endBalance = -4_000)
        assertEquals(-4_000L, b["pay40"])
        assertEquals(-8_000L, b["pay4"])
        assertEquals(-8_400L, b["oct"])
        assertEquals(-3_400L, b["sep"])
        assertEquals(-400L, b["aug"])
    }

    @Test fun `same minute keeps the list order`() {
        val a = row("a", TxnType.EXPENSE, "2026-10-07T10:00:00.000", 100)
        val b = row("b", TxnType.EXPENSE, "2026-10-07T10:00:00.000", 200)
        val balances = runningBalances(listOf(a, b), "card", endBalance = -300)
        assertEquals(-300L, balances["a"])
        assertEquals(-200L, balances["b"])
    }

    @Test fun `period lines as on Realbyte's Monthly tab`() {
        val ranges = (8..12).map { card.month(YearMonth.of(2026, it)) }
        val lines = periodLines(ranges, rows, "card", endBalance = -4_000)
        assertEquals(PeriodLine(ranges[0], 0, 400, -400), lines[0])
        assertEquals(PeriodLine(ranges[1], 0, 3_000, -3_400), lines[1])
        assertEquals(PeriodLine(ranges[2], 4_400, 5_000, -4_000), lines[2])
        assertEquals(-600L, lines[2].total)
        assertEquals(PeriodLine(ranges[4], 0, 0, -4_000), lines[4])
    }
}
