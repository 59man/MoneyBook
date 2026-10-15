package com.moneybook.data

import androidx.room.withTransaction
import com.moneybook.data.db.BudgetEntity
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.domain.BudgetItem
import com.moneybook.domain.BudgetRow
import com.moneybook.domain.Budgets
import com.moneybook.domain.TOTAL_BUDGET
import com.moneybook.shared.model.CategoryType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** Budget Setting's data: budgets as [BudgetItem]s, and Realbyte's add / month / Default / delete, one transaction each. */
class BudgetRepository(private val db: MoneyBookDatabase, private val time: TimeSource) {
    private val dao get() = db.budgetDao()

    /** Live budgets: the Total Budget first, then categories in Expenses order. Deleted or hidden categories are left out. */
    fun observe(): Flow<List<BudgetItem>> =
        combine(dao.observeLive(), db.categoryDao().observeTop(CategoryType.EXPENSE.name)) { rows, categories ->
            val byCategory = rows.groupBy { it.categoryId }
            val total = byCategory[null]?.let { listOf(BudgetItem(null, TOTAL_BUDGET, it.toRows())) }.orEmpty()
            total + categories.filter { !it.hidden }.mapNotNull { c -> byCategory[c.id]?.let { BudgetItem(c.id, c.name, it.toRows()) } }
        }

    fun observeOne(categoryId: String?): Flow<BudgetItem?> = observe().map { items -> items.firstOrNull { it.categoryId == categoryId } }

    /** Add Budget: a new budget for every month; an existing one gets [amount] for month [current]; a deleted one comes back. */
    suspend fun add(categoryId: String?, amount: Long, current: Int) = db.withTransaction {
        val existing = dao.forCategory(categoryId)
        val live = existing.filter { !it.deleted }
        val rows = when {
            existing.isEmpty() -> listOf(BudgetRow(Budgets.ALWAYS, amount))
            live.isNotEmpty() -> Budgets.editMonth(live.toRows(), current, amount)
            else -> existing.map { BudgetRow(it.fromMonth, amount) }
        }
        write(categoryId, rows, existing)
    }

    /** Month and Default edits do nothing once the budget is deleted (an edit screen may still be open). */
    suspend fun setMonth(categoryId: String?, month: Int, amount: Long) = db.withTransaction {
        val existing = dao.forCategory(categoryId)
        val live = existing.filter { !it.deleted }
        if (live.isNotEmpty()) write(categoryId, Budgets.editMonth(live.toRows(), month, amount), existing)
    }

    suspend fun setDefault(categoryId: String?, amount: Long, current: Int) = db.withTransaction {
        val existing = dao.forCategory(categoryId)
        val live = existing.filter { !it.deleted }
        if (live.isNotEmpty()) write(categoryId, Budgets.editDefault(live.toRows(), current, amount), existing)
    }

    suspend fun delete(categoryId: String?) = dao.softDelete(categoryId, time.millis())

    /** Upserts the rows that are new, changed or revived. */
    private suspend fun write(categoryId: String?, rows: List<BudgetRow>, existing: List<BudgetEntity>) {
        val now = time.millis()
        val old = existing.associateBy { it.fromMonth }
        val changed = rows.filter { r -> old[r.fromMonth].let { it == null || it.deleted || it.amount != r.amount } }
        dao.upsertAll(changed.map { BudgetEntity(BudgetEntity.idFor(categoryId, it.fromMonth), categoryId, it.fromMonth, it.amount, now) })
    }

    private fun List<BudgetEntity>.toRows() = map { BudgetRow(it.fromMonth, it.amount) }.sortedBy { it.fromMonth }
}
