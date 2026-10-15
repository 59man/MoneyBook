package com.moneybook.domain

import com.moneybook.data.db.AccountBalanceRow
import com.moneybook.data.db.CategoryEntity
import com.moneybook.data.db.TxnRow
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.CategoryType
import com.moneybook.shared.model.TxnType
import com.moneybook.shared.model.TxnType.EXPENSE
import com.moneybook.shared.model.TxnType.INCOME
import com.moneybook.shared.model.TxnType.TRANSFER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TxnFilterTest {
    private fun row(
        id: String, type: TxnType, amount: Long, account: String = "cash", to: String? = null,
        category: String? = null, parent: String? = null, toAmount: Long? = null,
    ) = TxnRow(
        id = id, type = type, dateTime = "2026-09-10T12:00:00.000", amount = amount, toAmount = toAmount, amountMain = amount,
        currency = "CZK", fee = 0, note = "", description = "", accountId = account, accountName = account, toAccountId = to,
        toAccountName = to, categoryId = category, categoryName = category, parentCategoryName = parent, parentCategoryId = parent,
    )

    private val rows = listOf(
        row("food", EXPENSE, 30_000, category = "food"),
        row("lunch", EXPENSE, 1_000, category = "lunch", parent = "food"),
        row("bus", EXPENSE, 10_000, account = "card", category = "transport"),
        row("pay", INCOME, 250_000, category = "salary"),
        row("move", TRANSFER, 5_000, account = "cash", to = "card"),
        row("none", EXPENSE, 700),
    )

    private fun ids(f: TxnFilter) = rows.filter(f::matches).map { it.id }

    @Test fun `no filter keeps everything`() {
        assertFalse(TxnFilter().isActive)
        assertEquals(rows.map { it.id }, ids(TxnFilter()))
    }

    @Test fun `a parent category keeps its subcategories and drops transfers`() {
        assertEquals(listOf("food", "lunch"), ids(TxnFilter(categories = setOf("food"))))
        assertEquals(listOf("food", "lunch", "pay"), ids(TxnFilter(categories = setOf("food", "salary"))))
    }

    @Test fun `an account keeps entries from or into it`() {
        assertEquals(listOf("bus", "move"), ids(TxnFilter(accounts = setOf("card"))))
    }

    @Test fun `categories and accounts must both match`() {
        assertEquals(listOf("food", "lunch"), ids(TxnFilter(categories = setOf("food"), accounts = setOf("cash"))))
        assertEquals(emptyList<String>(), ids(TxnFilter(categories = setOf("food"), accounts = setOf("card"))))
    }

    @Test fun `toggling and all add and remove ids`() {
        val f = TxnFilter().toggleCategory("food").toggleAccount("cash")
        assertTrue(f.isActive)
        assertEquals(TxnFilter(), f.toggleCategory("food").toggleAccount("cash"))
        assertEquals(setOf("a", "b", "food"), f.withCategories(listOf("a", "b"), ticked = true).categories)
        assertEquals(emptySet<String>(), f.withAccounts(listOf("cash", "x"), ticked = false).accounts)
    }

    @Test fun `options list category amounts, account flows and the ticked share`() {
        val cats = listOf(
            CategoryEntity(id = "food", type = CategoryType.EXPENSE, name = "🍜 Food", sortOrder = 0, updatedAt = 0),
            CategoryEntity(id = "transport", type = CategoryType.EXPENSE, name = "🚖 Transport", sortOrder = 1, updatedAt = 0),
            CategoryEntity(id = "pets", type = CategoryType.EXPENSE, name = "🐶 Pets", sortOrder = 2, updatedAt = 0),
        )
        val income = listOf(CategoryEntity(id = "salary", type = CategoryType.INCOME, name = "💰 Salary", sortOrder = 0, updatedAt = 0))
        fun balance(id: String, group: String, type: AccountGroupType, hidden: Boolean = false) = AccountBalanceRow(
            id = id, groupId = group, groupName = group, groupType = type, groupSortOrder = 0, name = id, currency = "CZK",
            includeInTotals = true, hidden = hidden, balance = 0,
        )
        val balances = listOf(
            balance("cash", "Cash", AccountGroupType.CASH), balance("card", "Card", AccountGroupType.CARD),
            balance("old", "Card", AccountGroupType.CARD, hidden = true),
        )
        val o = filterOptions(rows, income, cats, balances, TxnFilter(categories = setOf("food")))
        assertEquals(listOf(31_000L, 10_000L, 0L), o.expense.map { it.amount })
        assertEquals(listOf("💰 Salary" to 250_000L), o.income.map { it.name to it.amount })
        assertEquals(listOf("Cash", "Card"), o.groups.map { it.name })
        assertEquals(FilterAccount("cash", "cash", incoming = 250_000, outgoing = 36_700), o.groups[0].accounts.single())
        assertEquals(FilterAccount("card", "card", incoming = 5_000, outgoing = 10_000), o.groups[1].accounts.single())
        assertEquals(Totals(250_000, 41_700), o.month)
        assertEquals(Totals(0, 31_000), o.selected)
        assertEquals(0L, o.incomePercent)
        assertEquals(74L, o.expensePercent) // 31,000 / 41,700
        assertEquals(Totals(), filterOptions(rows, income, cats, balances, TxnFilter()).selected)
    }
}
