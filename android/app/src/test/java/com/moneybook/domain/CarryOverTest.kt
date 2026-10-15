package com.moneybook.domain

import com.moneybook.data.db.TxnRow
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.TxnType
import com.moneybook.shared.model.TxnType.EXPENSE
import com.moneybook.shared.model.TxnType.INCOME
import com.moneybook.shared.model.TxnType.TRANSFER
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarryOverTest {
    private fun row(
        id: String, type: TxnType, dateTime: String, amount: Long, fromTe: Boolean = false, toTe: Boolean = false,
        toCard: Boolean = false,
    ) = TxnRow(
        id = id, type = type, dateTime = dateTime, amount = amount, toAmount = null, amountMain = amount,
        currency = "CZK", fee = 0, note = "", description = "", accountId = "a", accountName = "a",
        toAccountId = if (type == TRANSFER) "b" else null, toAccountName = null, categoryId = null, categoryName = null, parentCategoryName = null,
        toAccountGroupType = if (toCard) AccountGroupType.CARD else null, fromTransferExpense = fromTe, toTransferExpense = toTe,
    )

    private val start = LocalDate.of(2026, 10, 1)

    @Test fun `value is income minus expense before the period`() {
        val v = carryOver(listOf(row("i", INCOME, "2026-08-01T08:00:00.000", 1_000), row("e", EXPENSE, "2026-09-01T08:00:00.000", 400)))
        assertEquals(600L, v)
        assertEquals(-400L, carryOver(listOf(row("e", EXPENSE, "2026-09-01T08:00:00.000", 400))))
        assertEquals(0L, carryOver(emptyList()))
    }

    @Test fun `value follows Transfer-Expense and card mode B`() {
        val toSavings = row("t", TRANSFER, "2026-09-01T08:00:00.000", 500, toTe = true)
        assertEquals(-500L, carryOver(listOf(toSavings)))
        val plain = row("p", TRANSFER, "2026-09-01T08:00:00.000", 500)
        assertEquals(0L, carryOver(listOf(plain)))
        assertEquals(0L, carryOver(listOf(plain), CardExpenseMode.AT_THE_TIME))
        val toCard = row("c", TRANSFER, "2026-09-01T08:00:00.000", 300, toCard = true)
        assertEquals(0L, carryOver(listOf(toCard), CardExpenseMode.AT_THE_TIME))
        assertEquals(-300L, carryOver(listOf(toCard), CardExpenseMode.LUMP_SUM))
    }

    @Test fun `positive value is an income row on the first day`() {
        val days = withCarryOver(emptyList(), start, 700, "CZK", appendLast = true)
        assertEquals(1, days.size)
        val g = days.single()
        assertEquals(start, g.date)
        assertEquals(Totals(700, 0), g.totals)
        val r = g.rows.single()
        assertEquals(CARRY_OVER_ID, r.id)
        assertTrue(r.isCarryOver)
        assertEquals(INCOME, r.type)
        assertEquals(700L, r.amountMain)
        assertEquals("CZK", r.currency)
    }

    @Test fun `negative value is an expense row with the absolute amount`() {
        val g = withCarryOver(emptyList(), start, -334, "CZK", appendLast = true).single()
        assertEquals(Totals(0, 334), g.totals)
        assertEquals(EXPENSE, g.rows.single().type)
        assertEquals(334L, g.rows.single().amountMain)
    }

    @Test fun `zero is an empty income row that adds nothing`() {
        val g = withCarryOver(emptyList(), start, 0, "CZK", appendLast = true).single()
        assertEquals(Totals(0, 0), g.totals)
        assertEquals(0L, g.rows.single().amountMain)
    }

    @Test fun `an existing first day gets the row last or first and its totals grow`() {
        val real = row("x", EXPENSE, "2026-10-01T09:00:00.000", 50)
        val days = groupByDay(listOf(real))
        val last = withCarryOver(days, start, 200, "CZK", appendLast = true).single()
        assertEquals(listOf("x", CARRY_OVER_ID), last.rows.map { it.id })
        assertEquals(Totals(200, 50), last.totals)
        val first = withCarryOver(days, start, 200, "CZK", appendLast = false).single()
        assertEquals(listOf(CARRY_OVER_ID, "x"), first.rows.map { it.id })
    }

    @Test fun `a missing first day is created in newest-first order`() {
        val days = groupByDay(listOf(row("a", EXPENSE, "2026-10-05T09:00:00.000", 50), row("b", EXPENSE, "2026-10-03T09:00:00.000", 50)))
        val out = withCarryOver(days, start, 10, "CZK", appendLast = true)
        assertEquals(listOf(5, 3, 1), out.map { it.date.dayOfMonth })
    }

    @Test fun `calendar cell of the first day includes the value`() {
        val period = Periods.month(java.time.YearMonth.of(2026, 10), 1)
        val cells = buildCalendar(period, java.time.DayOfWeek.SUNDAY, LocalDate.of(2026, 10, 10), emptyList())
        val out = cells.withCarryOver(start, -334)
        assertEquals(Totals(0, 334), out.single { it.date == start }.totals)
        assertEquals(Totals(), out.single { it.date == start.plusDays(1) }.totals)
        assertFalse(cells.withCarryOver(start, 0).any { it.totals != Totals() })
    }
}
