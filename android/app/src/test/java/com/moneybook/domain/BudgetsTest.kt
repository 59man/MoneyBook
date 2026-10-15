package com.moneybook.domain

import com.moneybook.data.db.TxnRow
import com.moneybook.shared.model.TxnType
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BudgetsTest {
    private fun shown(rows: List<BudgetRow>) =
        listOf(202609, 202610, 202611, 202612, 202701).map { Budgets.amountFor(rows, it) } + Budgets.defaultAmount(rows)

    @Test fun `a new budget applies to every month`() {
        val rows = listOf(BudgetRow(Budgets.ALWAYS, 100))
        assertEquals(100, Budgets.amountFor(rows, 201901))
        assertEquals(100, Budgets.defaultAmount(rows))
    }

    @Test fun `the measured edit table`() {
        var rows = listOf(BudgetRow(0, 100))
        rows = Budgets.editMonth(rows, 202610, 150)
        assertEquals(listOf(100L, 150, 100, 100, 100, 100), shown(rows))
        rows = Budgets.editDefault(rows, 202610, 200)
        assertEquals(listOf(100L, 150, 200, 200, 200, 200), shown(rows))
        rows = Budgets.editMonth(rows, 202612, 300)
        assertEquals(listOf(100L, 150, 200, 300, 200, 200), shown(rows))
        rows = Budgets.editDefault(rows, 202610, 250)
        assertEquals(listOf(100L, 150, 250, 300, 250, 250), shown(rows))
    }

    @Test fun `month edit keeps the following months`() {
        val rows = Budgets.editMonth(listOf(BudgetRow(0, 100), BudgetRow(202611, 70)), 202610, 5)
        assertEquals(listOf(100L, 5, 70, 70, 70, 70), shown(rows))
        // December was not pinned before: editing it pins January to what it had.
        assertEquals(70, Budgets.amountFor(Budgets.editMonth(rows, 202612, 1), 202701))
    }

    @Test fun `editing the same month twice replaces its row`() {
        val rows = Budgets.editMonth(Budgets.editMonth(listOf(BudgetRow(0, 100)), 202610, 150), 202610, 120)
        assertEquals(1, rows.count { it.fromMonth == 202610 })
        assertEquals(120, Budgets.amountFor(rows, 202610))
    }

    @Test fun `budget month follows day 20`() {
        assertEquals(202610, Budgets.monthOf(Periods.month(YearMonth.of(2026, 10), 1)))
        assertEquals(202610, Budgets.monthOf(Periods.month(YearMonth.of(2026, 10), 19))) // 10/19–11/18
        assertEquals(202610, Budgets.monthOf(Periods.month(YearMonth.of(2026, 9), 25))) // 9/25–10/24
        assertEquals(202610, Budgets.current(LocalDate.of(2026, 10, 8), 25, WeekendShift.NONE))
    }

    @Test fun `rate truncates and gives up on huge ratios`() {
        assertEquals(71, Budgets.rate(30000, 21400))
        assertEquals(214, Budgets.rate(10000, 21400))
        assertEquals(0, Budgets.rate(5000, 0))
        assertNull(Budgets.rate(0, 100))
        assertNull(Budgets.rate(1, 100))
    }

    @Test fun `today fraction counts today`() {
        val oct = Periods.month(YearMonth.of(2026, 10), 1)
        assertEquals(8f / 31f, Budgets.todayFraction(oct, LocalDate.of(2026, 10, 8)))
        assertEquals(1f, Budgets.todayFraction(oct, LocalDate.of(2026, 10, 31)))
        assertNull(Budgets.todayFraction(oct, LocalDate.of(2026, 11, 1)))
    }

    private fun expense(id: String, category: String?, amount: Long, parent: String? = null) = TxnRow(
        id = id, type = TxnType.EXPENSE, dateTime = "2026-10-05T12:00:00.000", amount = amount, toAmount = null,
        amountMain = amount, currency = "USD", fee = 0, note = "", description = "", accountId = "a", accountName = "Cash",
        toAccountId = null, toAccountName = null, categoryId = category, categoryName = category,
        parentCategoryName = parent, parentCategoryId = parent,
    )

    private val rows = listOf(expense("1", "other", 21400), expense("2", "sub", 1000, parent = "food"), expense("3", "fun", 500))

    @Test fun `a user total counts every expense`() {
        val items = listOf(
            BudgetItem(null, TOTAL_BUDGET, listOf(BudgetRow(0, 30000))),
            BudgetItem("food", "Food", listOf(BudgetRow(0, 5000))),
            BudgetItem("other", "Other", listOf(BudgetRow(0, 10000))),
        )
        val lines = budgetLines(items, 202610, rows)
        assertEquals(listOf(null, "food", "other"), lines.map { it.categoryId })
        assertEquals(22900, lines[0].used)
        assertEquals(1000, lines[1].used) // the subcategory counts toward Food
        assertEquals(-11400, lines[2].remaining)
        assertEquals(false, lines[0].synthetic)
    }

    @Test fun `without a user total the first line sums the shown budgets`() {
        val items = listOf(
            BudgetItem("food", "Food", listOf(BudgetRow(0, 5000))),
            BudgetItem("other", "Other", listOf(BudgetRow(0, 10000), BudgetRow(202610, 0))), // 0 in October: hidden
            BudgetItem("fun", "Fun", listOf(BudgetRow(0, 2000))),
        )
        val lines = budgetLines(items, 202610, rows)
        assertEquals(listOf(null, "food", "fun"), lines.map { it.categoryId })
        assertEquals(BudgetLine(null, TOTAL_BUDGET, 7000, 1500, synthetic = true), lines[0])
    }

    @Test fun `a zero total falls back to the synthetic one, and nothing gives no lines`() {
        val zeroTotal = BudgetItem(null, TOTAL_BUDGET, listOf(BudgetRow(0, 0)))
        assertEquals(emptyList(), budgetLines(listOf(zeroTotal), 202610, rows))
        val lines = budgetLines(listOf(zeroTotal, BudgetItem("fun", "Fun", listOf(BudgetRow(0, 2000)))), 202610, rows)
        assertEquals(true, lines[0].synthetic)
    }

    @Test fun `the year chart sums the category per month against its budget`() {
        val rows = listOf(
            expense("9", "other", 33000).copy(dateTime = "2026-09-10T12:00:00.000"),
            expense("10", "other", 21400),
            expense("x", "food", 999),
        )
        val points = budgetYear(2026, 1, WeekendShift.NONE, rows, "other", listOf(BudgetRow(0, 10000), BudgetRow(202610, 15000)))
        assertEquals(12, points.size)
        assertEquals("Jan", points[0].label)
        assertEquals(33000, points[8].spent)
        assertEquals(21400, points[9].spent)
        assertEquals(listOf(10000L, 15000, 15000), points.slice(8..10).map { it.budget })
    }

    @Test fun `a Transfer-Expense transfer counts in the Total Budget but not in a category budget`() {
        val transfer = expense("t", null, 4_000).copy(
            type = TxnType.TRANSFER, toAccountId = "save", toAccountName = "Save", toTransferExpense = true,
        )
        val items = listOf(BudgetItem(null, TOTAL_BUDGET, listOf(BudgetRow(0, 30000))), BudgetItem("other", "Other", listOf(BudgetRow(0, 10000))))
        val lines = budgetLines(items, 202610, rows + transfer)
        assertEquals(26_900, lines[0].used)
        assertEquals(21_400, lines[1].used)
    }
}
