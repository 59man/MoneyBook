package com.moneybook.domain

import com.moneybook.data.db.TxnRow
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.TxnType
import com.moneybook.shared.model.TxnType.EXPENSE
import com.moneybook.shared.model.TxnType.INCOME
import com.moneybook.shared.model.TxnType.TRANSFER
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatsTest {
    private fun row(
        id: String, type: TxnType, date: String, amount: Long,
        categoryId: String? = null, category: String? = null, parentId: String? = null, parent: String? = null,
        group: AccountGroupType = AccountGroupType.CASH, toGroup: AccountGroupType? = null, amountMain: Long = amount,
    ) = TxnRow(
        id = id, type = type, dateTime = "${date}T12:00:00.000", amount = amount, toAmount = null, amountMain = amountMain,
        currency = "CZK", fee = 0, note = "", description = "", accountId = "a", accountName = "A",
        toAccountId = null, toAccountName = null, categoryId = categoryId, categoryName = category,
        parentCategoryName = parent, parentCategoryId = parentId, accountGroupType = group, toAccountGroupType = toGroup,
    )

    @Test fun `subcategory slices need two subcategories and keep entries on the parent as their own slice`() {
        val lunch = row("2", EXPENSE, "2026-09-02", 20_000, "lunch", "Lunch", parentId = "food", parent = "Food")
        val dinner = row("3", EXPENSE, "2026-09-03", 5_000, "dinner", "Dinner", parentId = "food", parent = "Food")
        val direct = row("1", EXPENSE, "2026-09-01", 10_000, "food", "Food")
        assertEquals(emptyList<CategorySlice>(), subcategorySlices(listOf(direct, lunch), EXPENSE))
        val slices = subcategorySlices(listOf(direct, lunch, dinner), EXPENSE)
        assertEquals(listOf("lunch" to 20_000L, "food" to 10_000L, "dinner" to 5_000L), slices.map { it.key to it.amount })
        assertEquals(listOf("Lunch", "Food", "Dinner"), slices.map { it.name })
    }

    @Test fun `slices are per top category, largest first, transfers and other types left out`() {
        val rows = listOf(
            row("1", EXPENSE, "2026-09-01", 10_000, "food", "🍜 Food"),
            row("2", EXPENSE, "2026-09-02", 20_000, "lunch", "Lunch", parentId = "food", parent = "🍜 Food"),
            row("3", EXPENSE, "2026-09-03", 10_000, "tr", "🚖 Transport"),
            row("4", INCOME, "2026-09-04", 99_000, "sal", "💰 Salary"),
            row("5", TRANSFER, "2026-09-05", 55_000),
        )
        val slices = categorySlices(rows, EXPENSE)
        assertEquals(listOf("food" to 30_000L, "tr" to 10_000L), slices.map { it.key to it.amount })
        assertEquals(listOf("🍜 Food", "🚖 Transport"), slices.map { it.name })
        assertEquals(0.75, slices[0].share, 1e-9)
        assertEquals(listOf("sal"), categorySlices(rows, INCOME).map { it.key })
    }

    @Test fun `ties are ordered by name and an empty period has no slices`() {
        val rows = listOf(row("1", EXPENSE, "2026-09-01", 500, "b", "Beauty"), row("2", EXPENSE, "2026-09-01", 500, "a", "Apparel"))
        assertEquals(listOf("Apparel", "Beauty"), categorySlices(rows, EXPENSE).map { it.name })
        assertEquals(emptyList<CategorySlice>(), categorySlices(emptyList(), EXPENSE))
        assertEquals(emptyList<CategorySlice>(), categorySlices(listOf(row("i", INCOME, "2026-09-01", 1_000, "s", "S")), EXPENSE))
    }

    @Test fun `subcategories roll up and missing categories fall back`() {
        val rows = listOf(
            row("1", EXPENSE, "2026-09-01", 1_000),
            row("2", EXPENSE, "2026-09-01", 2_000, "other", "Other"),
            row("3", EXPENSE, "2026-09-01", 4_000, "sub", "Sub", parentId = "gone", parent = "Deleted parent"),
        )
        val withOther = categorySlices(rows, EXPENSE, CategoryRef("other", "Other"))
        assertEquals(listOf("gone" to 4_000L, "other" to 3_000L), withOther.map { it.key to it.amount })
        assertEquals("Deleted parent", withOther[0].name)
        val without = categorySlices(rows, EXPENSE)
        assertEquals(1_000L, without.single { it.key == NO_CATEGORY }.amount)
        assertEquals("(No category)", without.single { it.key == NO_CATEGORY }.name)
        assertEquals("other", rows[0].sliceKey(EXPENSE, CategoryRef("other", "Other")))
        assertEquals("gone", rows[2].sliceKey(EXPENSE, null))
    }

    @Test fun `chips round to whole percents and labels keep one decimal`() {
        val rows = listOf(
            row("1", EXPENSE, "2026-09-01", 2_847, "a", "A"),
            row("2", EXPENSE, "2026-09-01", 7_150, "b", "B"),
            row("3", EXPENSE, "2026-09-01", 3, "c", "C"),
        )
        val byKey = categorySlices(rows, EXPENSE).associateBy { it.key }
        assertEquals("28.5 %", byKey.getValue("a").percentLabel)
        assertEquals("28%", byKey.getValue("a").percentChip)
        assertEquals("72%", byKey.getValue("b").percentChip)
        assertEquals("0%", byKey.getValue("c").percentChip)
        assertEquals("0.0 %", byKey.getValue("c").percentLabel)
    }

    @Test fun `foreign currency entries count by their main currency amount`() {
        val rows = listOf(row("eur", EXPENSE, "2026-09-01", 100, "a", "A", amountMain = 2_500), row("czk", EXPENSE, "2026-09-01", 500, "a", "A"))
        assertEquals(3_000L, categorySlices(rows, EXPENSE).single().amount)
        val range = DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1))
        assertEquals(listOf(3_000L), periodSums(rows, listOf(range), EXPENSE))
    }

    @Test fun `period sums and totals bucket by day ranges`() {
        val rows = listOf(
            row("a", EXPENSE, "2026-08-31", 100), row("b", EXPENSE, "2026-09-01", 200),
            row("c", INCOME, "2026-09-15", 1_000), row("d", EXPENSE, "2026-09-30", 300),
        )
        val aug = DateRange(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1))
        val sep = DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1))
        assertEquals(listOf(100L, 500L), periodSums(rows, listOf(aug, sep), EXPENSE))
        assertEquals(listOf(Totals(0, 100), Totals(1_000, 500)), totalsPerRange(rows, listOf(aug, sep)))
    }

    @Test fun `total breakdown splits card and non card accounts and compares months`() {
        val current = listOf(
            row("1", EXPENSE, "2026-09-01", 30_000),
            row("2", EXPENSE, "2026-09-02", 10_000, group = AccountGroupType.CARD),
            row("3", TRANSFER, "2026-09-03", 5_000, toGroup = AccountGroupType.CARD),
            row("4", TRANSFER, "2026-09-04", 2_000, toGroup = AccountGroupType.ACCOUNTS),
            row("5", TRANSFER, "2026-09-05", 700, toGroup = AccountGroupType.CASH),
            row("6", TRANSFER, "2026-09-06", 900, group = AccountGroupType.CARD, toGroup = AccountGroupType.CASH),
            row("7", INCOME, "2026-09-07", 90_000),
        )
        val previous = listOf(row("p", EXPENSE, "2026-08-10", 30_000))
        assertEquals(TotalBreakdown(133, 30_000, 10_000, 7_000), totalBreakdown(current, previous))
        assertNull(totalBreakdown(current, emptyList()).comparedPercent)
        assertEquals(0L, totalBreakdown(emptyList(), previous).comparedPercent)
        // Lump sum: the card expense leaves, the 5,000 transfer into the card counts (30,000 + 5,000 of 30,000).
        assertEquals(117L, totalBreakdown(current, previous, CardExpenseMode.LUMP_SUM).comparedPercent)
    }

    @Test fun `a category that nets negative stays in the list but out of the pie`() {
        val rows = listOf(
            row("1", EXPENSE, "2026-09-01", 1_000, "food", "🍜 Food"),
            row("2", EXPENSE, "2026-09-02", -200, "cl", "🧥 Clothing"), // a refund with no purchase this month
        )
        val slices = categorySlices(rows, EXPENSE)
        assertEquals(listOf("food" to 1_000L, "cl" to -200L), slices.map { it.key to it.amount })
        assertEquals(1.0, slices[0].share, 1e-9)
        assertEquals("100.0 %", slices[0].percentLabel)
        assertEquals("100%", slices[0].percentChip)
        assertEquals(0.0, slices[1].share, 1e-9)
        assertEquals("0.0 %", slices[1].percentLabel)
        assertEquals("0%", slices[1].percentChip)
        // Only refunds: the rows are still listed, with nothing to draw.
        val onlyRefund = categorySlices(listOf(row("3", EXPENSE, "2026-09-03", -500, "cl", "🧥 Clothing")), EXPENSE)
        assertEquals(listOf(-500L), onlyRefund.map { it.amount })
        assertEquals(listOf("0%"), onlyRefund.map { it.percentChip })
    }

    private fun exchange(id: String, from: Boolean, to: Boolean, amount: Long) = row(id, TRANSFER, "2026-09-06", amount).copy(
        fromTransferExpense = from, toTransferExpense = to, accountGroupId = "g-cash", accountGroupName = "Cash",
        toAccountGroupId = "g-save", toAccountGroupName = "EuroSave", toAccountName = "EuroSave 1", toAccountId = "save",
    )

    @Test fun `a Transfer-Expense transfer is a slice named after its group, sorted with the categories`() {
        val rows = listOf(
            row("1", EXPENSE, "2026-09-01", 10_000, "food", "Food"),
            exchange("t", from = false, to = true, amount = 30_000),
            exchange("plain", from = false, to = false, amount = 99_000),
            exchange("out", from = true, to = false, amount = 7_000),
        )
        val expense = categorySlices(rows, EXPENSE)
        assertEquals(listOf("group:g-save" to 30_000L, "food" to 10_000L), expense.map { it.key to it.amount })
        assertEquals(listOf("EuroSave", "Food"), expense.map { it.name })
        assertEquals("75%", expense[0].percentChip)
        // Income keys on the From group.
        val income = categorySlices(rows, INCOME)
        assertEquals(listOf("group:g-cash" to 7_000L), income.map { it.key to it.amount })
        assertEquals("Cash", income.single().name)
    }

    @Test fun `a group slice picks its transfers, and periodSums follow what is counted`() {
        val t = exchange("t", from = false, to = true, amount = 30_000)
        val rows = listOf(row("1", EXPENSE, "2026-09-01", 10_000, "food", "Food"), t)
        assertEquals("group:g-save", t.sliceKey(EXPENSE, null))
        assertNull(t.sliceKey(INCOME, null))
        assertEquals("food", rows[0].sliceKey(EXPENSE, null))
        val mine = rows.filter { it.sliceKey(EXPENSE, null) == "group:g-save" }
        assertEquals(listOf("t"), mine.map { it.id })
        val sept = DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1))
        assertEquals(listOf(30_000L), periodSums(mine, listOf(sept), EXPENSE))
        assertEquals(listOf(0L), periodSums(mine, listOf(sept), INCOME))
    }
}
