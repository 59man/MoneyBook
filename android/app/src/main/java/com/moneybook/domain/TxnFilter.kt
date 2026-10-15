package com.moneybook.domain

import com.moneybook.data.db.AccountBalanceRow
import com.moneybook.data.db.CategoryEntity
import com.moneybook.data.db.TxnRow
import com.moneybook.shared.model.TxnType

/** Trans → filter: the ticked top-level categories and accounts. An empty set does not restrict. */
data class TxnFilter(val categories: Set<String> = emptySet(), val accounts: Set<String> = emptySet()) {
    val isActive: Boolean get() = categories.isNotEmpty() || accounts.isNotEmpty()

    /**
     * With categories ticked, an income or expense entry passes when its top-level category is ticked;
     * transfers have no category and drop out. With accounts ticked, an entry passes when it leaves or
     * enters a ticked account.
     */
    fun matches(row: TxnRow): Boolean {
        if (categories.isNotEmpty()) {
            if (row.type == TxnType.TRANSFER) return false
            val top = row.parentCategoryId ?: row.categoryId ?: return false
            if (top !in categories) return false
        }
        return accounts.isEmpty() || row.accountId in accounts || row.toAccountId in accounts
    }

    fun toggleCategory(id: String) = copy(categories = categories.toggle(id))
    fun toggleAccount(id: String) = copy(accounts = accounts.toggle(id))
    fun withCategories(ids: Collection<String>, ticked: Boolean) = copy(categories = if (ticked) categories + ids else categories - ids.toSet())
    fun withAccounts(ids: Collection<String>, ticked: Boolean) = copy(accounts = if (ticked) accounts + ids else accounts - ids.toSet())
}

private fun Set<String>.toggle(id: String) = if (id in this) this - id else this + id

/** A category line of the filter panel with the month's amount (subcategories included). */
data class FilterCategory(val id: String, val name: String, val amount: Long)

/** An account line: income plus incoming transfers, and expenses plus outgoing transfers, in its currency. */
data class FilterAccount(val id: String, val name: String, val incoming: Long, val outgoing: Long)

data class FilterGroup(val name: String, val accounts: List<FilterAccount>)

data class FilterOptions(
    val income: List<FilterCategory> = emptyList(),
    val expense: List<FilterCategory> = emptyList(),
    val groups: List<FilterGroup> = emptyList(),
    /** The whole month: the amounts under the rings and "Total". */
    val month: Totals = Totals(),
    /** The part of the month the filter keeps; zero while no filter is set. */
    val selected: Totals = Totals(),
) {
    val incomePercent: Long get() = percent(selected.income, month.income)
    val expensePercent: Long get() = percent(selected.expense, month.expense)
}

private fun percent(part: Long, whole: Long): Long = if (whole <= 0 || part <= 0) 0 else (part * 100 + whole / 2) / whole

/** The filter panel's lists for one month of [rows] (unfiltered): visible categories, then visible accounts by group. */
fun filterOptions(
    rows: List<TxnRow>,
    incomeCategories: List<CategoryEntity>,
    expenseCategories: List<CategoryEntity>,
    balances: List<AccountBalanceRow>,
    filter: TxnFilter,
): FilterOptions {
    fun sums(type: TxnType): Map<String?, Long> =
        rows.filter { it.type == type }.groupBy { it.parentCategoryId ?: it.categoryId }.mapValues { (_, r) -> r.sumOf { it.amountMain } }
    val incomeSums = sums(TxnType.INCOME)
    val expenseSums = sums(TxnType.EXPENSE)
    val groups = balances.filter { !it.hidden }.groupBy { it.groupId }.values.map { accounts ->
        FilterGroup(
            accounts.first().groupName,
            accounts.map { a ->
                FilterAccount(
                    id = a.id, name = a.name,
                    incoming = rows.sumOf { r ->
                        when {
                            r.type == TxnType.INCOME && r.accountId == a.id -> r.amount
                            r.type == TxnType.TRANSFER && r.toAccountId == a.id -> r.toAmount ?: r.amount
                            else -> 0L
                        }
                    },
                    outgoing = rows.filter { it.type != TxnType.INCOME && it.accountId == a.id }.sumOf { it.amount },
                )
            },
        )
    }
    return FilterOptions(
        income = incomeCategories.map { FilterCategory(it.id, it.name, incomeSums[it.id] ?: 0) },
        expense = expenseCategories.map { FilterCategory(it.id, it.name, expenseSums[it.id] ?: 0) },
        groups = groups,
        month = totalsOf(rows),
        selected = if (filter.isActive) totalsOf(rows.filter(filter::matches)) else Totals(),
    )
}
