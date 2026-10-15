package com.moneybook.domain

import com.moneybook.data.db.AccountBalanceRow
import com.moneybook.data.db.TxnRow
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.TxnType
import com.moneybook.shared.model.TxnType.EXPENSE
import com.moneybook.shared.model.TxnType.INCOME
import com.moneybook.shared.model.TxnType.TRANSFER
import com.moneybook.shared.money.Fx
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AggregationsTest {
    private fun row(
        id: String, type: TxnType, dateTime: String, amount: Long,
        accountId: String = "cash", toAccountId: String? = null, fee: Long = 0,
    ) = TxnRow(
        id = id, type = type, dateTime = dateTime, amount = amount, toAmount = null, amountMain = amount,
        currency = "CZK", fee = fee, note = "", description = "", accountId = accountId, accountName = accountId,
        toAccountId = toAccountId, toAccountName = toAccountId, categoryId = null, categoryName = null, parentCategoryName = null,
    )

    private val sept = Periods.month(YearMonth.of(2026, 9), 1)
    private val today = LocalDate.of(2026, 9, 26)

    @Test fun `totals ignore transfers`() {
        val t = totalsOf(listOf(
            row("i", INCOME, "2026-09-01T08:00:00.000", 5_000),
            row("e", EXPENSE, "2026-09-01T09:00:00.000", 2_000),
            row("t", TRANSFER, "2026-09-01T10:00:00.000", 1_000, toAccountId = "bank"),
        ))
        assertEquals(Totals(5_000, 2_000), t)
        assertEquals(3_000L, t.total)
    }

    @Test fun `groups by day newest first`() {
        val groups = groupByDay(listOf(
            row("a", EXPENSE, "2026-09-26T18:40:00.000", 52_500),
            row("b", EXPENSE, "2026-09-25T16:50:00.000", 1_500),
            row("c", EXPENSE, "2026-09-25T12:00:00.000", 18_600),
        ))
        assertEquals(listOf(LocalDate.of(2026, 9, 26), LocalDate.of(2026, 9, 25)), groups.map { it.date })
        assertEquals(Totals(0, 20_100), groups[1].totals)
        assertEquals(listOf("b", "c"), groups[1].rows.map { it.id })
    }

    @Test fun `calendar grid for September 2026 starts on Sunday Aug 30 with 42 cells`() {
        val cells = buildCalendar(sept, DayOfWeek.SUNDAY, today, listOf(
            row("x", EXPENSE, "2026-08-30T10:00:00.000", 91_200),
            row("y", INCOME, "2026-09-01T10:00:00.000", 5_230_000),
        ))
        assertEquals(42, cells.size)
        assertEquals(LocalDate.of(2026, 8, 30), cells.first().date)
        assertEquals(LocalDate.of(2026, 10, 10), cells.last().date)
        assertFalse(cells.first().inPeriod)
        assertEquals(91_200L, cells.first().totals.expense)
        assertEquals(5_230_000L, cells[2].totals.income)
        assertTrue(cells.single { it.isToday }.date == today)
        assertEquals(DateRange(LocalDate.of(2026, 8, 30), LocalDate.of(2026, 10, 11)), calendarGrid(sept, DayOfWeek.SUNDAY))
    }

    @Test fun `monthly lists months up to the current one and expands weeks`() {
        val rows = listOf(
            row("aug30", EXPENSE, "2026-08-30T10:00:00.000", 91_200),
            row("sep1", INCOME, "2026-09-01T10:00:00.000", 5_230_000),
            row("sep24", EXPENSE, "2026-09-24T10:00:00.000", 125_600),
            row("jan", EXPENSE, "2026-01-10T10:00:00.000", 100),
        )
        val months = buildMonthly(2026, 1, DayOfWeek.SUNDAY, today, YearMonth.of(2026, 9), rows)
        assertEquals((9 downTo 1).map { YearMonth.of(2026, it) }, months.map { it.month })
        val sep = months.first()
        assertEquals(Totals(5_230_000, 125_600), sep.totals)
        assertEquals(5, sep.weeks.size)
        val firstWeek = sep.weeks.last()
        assertEquals(LocalDate.of(2026, 8, 30), firstWeek.range.start)
        assertEquals(Totals(5_230_000, 91_200), firstWeek.totals)
        assertEquals(LocalDate.of(2026, 9, 20), sep.weeks.single { it.isCurrent }.range.start)
        assertTrue(months.drop(1).all { it.weeks.isEmpty() })
        assertEquals(Totals(0, 100), months.last().totals)
    }

    @Test fun `past years list all twelve months`() {
        assertEquals(12, buildMonthly(2025, 1, DayOfWeek.SUNDAY, today, null, emptyList()).size)
    }

    @Test fun `monthly query range covers edge weeks`() {
        val r = monthlyQueryRange(2026, 1, DayOfWeek.SUNDAY)
        assertEquals(LocalDate.of(2025, 12, 28), r.start)
        assertEquals(LocalDate.of(2027, 1, 3), r.endExclusive)
    }

    private fun balance(id: String, group: String, groupType: AccountGroupType, balance: Long, hidden: Boolean = false, include: Boolean = true) =
        AccountBalanceRow(id, group, group, groupType, 0, id, "CZK", include, hidden, balance)

    @Test fun `accounts overview splits assets and liabilities`() {
        val o = buildAccountsOverview(listOf(
            balance("cash", "Cash", AccountGroupType.CASH, 41_280_000),
            balance("acc", "Accounts", AccountGroupType.ACCOUNTS, 0),
            balance("card", "Card", AccountGroupType.CARD, -5_000),
            balance("secret", "Cash", AccountGroupType.CASH, 999, hidden = true),
            balance("loan", "Loan", AccountGroupType.LOAN, -1_000_000, include = false),
        ))
        assertEquals(41_280_000L, o.assets)
        assertEquals(5_000L, o.liabilities)
        assertEquals(41_275_000L, o.total)
        assertEquals(listOf("Cash", "Accounts", "Card", "Loan"), o.groups.map { it.name })
        assertEquals(listOf("cash"), o.groups.first().accounts.map { it.id })
    }

    @Test fun `account period summary counts transfers both ways`() {
        val s = accountPeriodSummary("cash", listOf(
            row("i", INCOME, "2026-09-01T08:00:00.000", 1_000),
            row("e", EXPENSE, "2026-09-02T08:00:00.000", 300),
            row("out", TRANSFER, "2026-09-03T08:00:00.000", 200, toAccountId = "bank", fee = 10),
            row("in", TRANSFER, "2026-09-04T08:00:00.000", 50, accountId = "bank", toAccountId = "cash"),
        ))
        assertEquals(AccountPeriodSummary(deposit = 1_050, withdrawal = 510), s)
        assertEquals(540L, s.total)
    }

    @Test fun hiddenAccountsAreLeftOutOfTotalsButCanBeListed() {
        val rows = listOf(
            balance("a", "Cash", AccountGroupType.CASH, 1_000),
            balance("h", "Cash", AccountGroupType.CASH, 500, hidden = true),
        )
        assertEquals(listOf("a"), buildAccountsOverview(rows).groups.flatMap { it.accounts }.map { it.id })
        val all = buildAccountsOverview(rows, includeHidden = true)
        assertEquals(listOf("a", "h"), all.groups.flatMap { it.accounts }.map { it.id })
        assertTrue(all.groups.single().accounts.single { it.id == "h" }.hidden)
        assertEquals(1_000L, all.assets)
        assertEquals(1_000L, all.groups.single().total)
    }

    @Test fun `a card account gets its payable and outstanding`() {
        val card = AccountBalanceRow(
            "c1", "g-card", "Card", AccountGroupType.CARD, 2, "Card", "CZK", true, false, -8_400, statementDay = 1, paymentDay = 15,
        )
        val charges = mapOf("c1" to listOf(
            CardCharge(LocalDate.parse("2026-08-05"), 400), CardCharge(LocalDate.parse("2026-09-10"), 3_000),
            CardCharge(LocalDate.parse("2026-10-07"), 5_000),
        ))
        val o = buildAccountsOverview(listOf(card), charges = charges, today = LocalDate.parse("2026-10-07"))
        assertEquals(CardStatus(3_400, 5_000, 400), o.groups.single().accounts.single().card)
        assertEquals(3_400L, o.groups.single().cardPayable)
        assertEquals(5_000L, o.groups.single().cardOutstanding)
        assertEquals(8_400L, o.liabilities)
    }

    @Test fun `foreign accounts keep their balance and count at the current rate`() {
        val fx = Fx("USD", mapOf("EUR" to BigDecimal("1.2")))
        val rows = listOf(
            AccountBalanceRow("cash", "g-cash", "Cash", AccountGroupType.CASH, 0, "Cash", "USD", true, false, 50_900),
            AccountBalanceRow("eu", "g-sav", "Savings", AccountGroupType.SAVINGS, 1, "EuroSave", "EUR", true, false, 14_545),
            AccountBalanceRow("ec", "g-card", "Card", AccountGroupType.CARD, 2, "EuroCard", "EUR", true, false, -1_000, statementDay = 1, paymentDay = 1),
        )
        val charges = mapOf("ec" to listOf(CardCharge(LocalDate.parse("2026-09-10"), 1_000)))
        val o = buildAccountsOverview(rows, charges = charges, today = LocalDate.parse("2026-10-07"), toMain = fx::toMain)
        assertEquals(50_900L + 17_454L, o.assets)
        assertEquals(1_200L, o.liabilities)
        assertEquals(17_454L, o.groups[1].total)
        assertEquals(14_545L, o.groups[1].accounts.single().balance)
        assertEquals(1_200L, o.groups[2].cardPayable)
    }

    @Test fun `a card without days uses 1 and 1 and other accounts get no status`() {
        val o = buildAccountsOverview(
            listOf(balance("card", "Card", AccountGroupType.CARD, 0), balance("cash", "Cash", AccountGroupType.CASH, 100)),
            today = LocalDate.parse("2026-10-07"),
        )
        assertEquals(CardStatus(0, 0, 0), o.groups[0].accounts.single().card)
        assertEquals(null, o.groups[1].accounts.single().card)
    }

    @Test fun `lump sum counts card payments instead of card spending`() {
        val rows = listOf(
            row("card", EXPENSE, "2026-10-07T10:00:00.000", 5_000).copy(accountGroupType = AccountGroupType.CARD),
            row("pay", TRANSFER, "2026-10-07T11:00:00.000", 4_400, toAccountId = "card")
                .copy(accountGroupType = AccountGroupType.ACCOUNTS, toAccountGroupType = AccountGroupType.CARD),
            row("cash", EXPENSE, "2026-10-07T12:00:00.000", 7_000).copy(accountGroupType = AccountGroupType.CASH),
            row("in", INCOME, "2026-10-07T13:00:00.000", 1_000),
        )
        assertEquals(Totals(1_000, 12_000), totalsOf(rows))
        assertEquals(Totals(1_000, 11_400), totalsOf(rows, CardExpenseMode.LUMP_SUM))
        assertEquals(Totals(1_000, 11_400), groupByDay(rows, mode = CardExpenseMode.LUMP_SUM).single().totals)
    }

    private fun transfer(f: Boolean, t: Boolean, fromCard: Boolean, toCard: Boolean, id: String = "x", date: String = "2026-10-07") =
        row(id, TRANSFER, "${date}T10:00:00.000", 1_000, toAccountId = "to").copy(
            fromTransferExpense = f, toTransferExpense = t,
            accountGroupType = if (fromCard) AccountGroupType.CARD else AccountGroupType.CASH,
            toAccountGroupType = if (toCard) AccountGroupType.CARD else AccountGroupType.ACCOUNTS,
        )

    @Test fun `transfer expense table`() {
        val n: TxnType? = null
        val e: TxnType? = EXPENSE
        val i: TxnType? = INCOME
        // Rows: F, T. Columns: (fromCard, toCard) = 00, 01, 10, 11.
        val a = mapOf((false to false) to listOf(n, n, n, n), (false to true) to listOf(e, e, e, e), (true to false) to listOf(i, i, i, i), (true to true) to listOf(n, n, n, n))
        val b = mapOf((false to false) to listOf(n, e, n, n), (false to true) to listOf(e, e, n, n), (true to false) to listOf(i, n, i, n), (true to true) to listOf(n, n, n, n))
        val cards = listOf(false to false, false to true, true to false, true to true)
        for ((mode, table) in listOf(CardExpenseMode.AT_THE_TIME to a, CardExpenseMode.LUMP_SUM to b)) for ((ft, expected) in table) {
            cards.forEachIndexed { k, (fromCard, toCard) ->
                assertEquals("$mode F=${ft.first} T=${ft.second} fromCard=$fromCard toCard=$toCard", expected[k], transfer(ft.first, ft.second, fromCard, toCard).countedAs(mode))
            }
        }
    }

    @Test fun `a transfer into a Transfer-Expense account counts as an expense and out of one as income`() {
        val rows = listOf(
            transfer(f = false, t = true, fromCard = false, toCard = false, id = "in"),
            transfer(f = true, t = false, fromCard = false, toCard = false, id = "out"),
            transfer(f = false, t = false, fromCard = false, toCard = false, id = "plain"),
            row("e", EXPENSE, "2026-10-07T11:00:00.000", 300),
        )
        assertEquals(Totals(1_000, 1_300), totalsOf(rows))
        assertEquals(Totals(1_000, 1_300), groupByDay(rows).single().totals)
        val oct = Periods.month(YearMonth.of(2026, 10), 1)
        val cell = buildCalendar(oct, DayOfWeek.MONDAY, LocalDate.of(2026, 10, 8), rows).single { it.date == LocalDate.of(2026, 10, 7) }
        assertEquals(Totals(1_000, 1_300), cell.totals)
        val monthly = buildMonthly(2026, 1, DayOfWeek.MONDAY, LocalDate.of(2026, 10, 8), null, rows)
        assertEquals(Totals(1_000, 1_300), monthly.first { it.month == YearMonth.of(2026, 10) }.totals)
    }
}
