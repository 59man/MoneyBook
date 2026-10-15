package com.moneybook.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.domain.BudgetRow
import com.moneybook.domain.Budgets
import com.moneybook.domain.TOTAL_BUDGET
import com.moneybook.shared.model.CategoryType
import com.moneybook.testutil.Fixtures
import com.moneybook.testutil.inMemoryDb
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class BudgetRepositoryTest {
    private lateinit var db: MoneyBookDatabase
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC))
    private lateinit var repo: BudgetRepository
    private val oct = 202610

    @Before fun setUp() = runTest {
        db = inMemoryDb()
        repo = BudgetRepository(db, time)
        db.categoryDao().upsertAll(
            listOf(
                Fixtures.category("food", name = "🍜 Food", sortOrder = 0),
                Fixtures.category("other", name = "Other", sortOrder = 1),
                Fixtures.category("salary", type = CategoryType.INCOME, name = "Salary"),
            ),
        )
    }

    @After fun tearDown() = db.close()

    @Test fun `add creates a budget for every month and lists it in category order`() = runTest {
        repo.add("other", 10000, oct)
        repo.add("food", 5000, oct)
        repo.add(null, 30000, oct)
        val items = repo.observe().first()
        assertEquals(listOf(null, "food", "other"), items.map { it.categoryId })
        assertEquals(listOf(TOTAL_BUDGET, "🍜 Food", "Other"), items.map { it.name })
        assertEquals(listOf(BudgetRow(Budgets.ALWAYS, 5000)), items[1].rows)
    }

    @Test fun `add on an existing budget sets the current month only`() = runTest {
        repo.add("food", 5000, oct)
        repo.add("food", 8000, oct)
        val rows = repo.observeOne("food").first()!!.rows
        assertEquals(5000, Budgets.amountFor(rows, 202609))
        assertEquals(8000, Budgets.amountFor(rows, oct))
        assertEquals(5000, Budgets.amountFor(rows, 202611))
    }

    @Test fun `month and default edits follow the rules`() = runTest {
        repo.add("other", 100, oct)
        repo.setMonth("other", oct, 150)
        repo.setDefault("other", 200, oct)
        val rows = repo.observeOne("other").first()!!.rows
        assertEquals(listOf(100L, 150, 200), listOf(202609, oct, 202611).map { Budgets.amountFor(rows, it) })
        assertEquals(200, Budgets.defaultAmount(rows))
    }

    @Test fun `delete hides the budget and re-add revives every row`() = runTest {
        repo.add("other", 100, oct)
        repo.setMonth("other", 202612, 300)
        repo.delete("other")
        assertNull(repo.observeOne("other").first())
        assertEquals(emptyList(), repo.observe().first())
        repo.add("other", 70, oct)
        val rows = repo.observeOne("other").first()!!.rows
        assertEquals(listOf(70L, 70, 70), listOf(202609, 202612, 202701).map { Budgets.amountFor(rows, it) })
        assertEquals(true, db.budgetDao().allRows().none { it.deleted })
    }

    // An edit screen left open on a deleted budget must not bring back a half budget.
    @Test fun `month and default edits of a deleted budget write nothing`() = runTest {
        repo.add("other", 100, oct)
        repo.delete("other")
        repo.setMonth("other", oct, 500)
        repo.setDefault("other", 200, oct)
        assertNull(repo.observeOne("other").first())
        assertEquals(true, db.budgetDao().allRows().all { it.deleted })
    }

    @Test fun `observe hides budgets of deleted and hidden categories`() = runTest {
        repo.add("food", 5000, oct)
        repo.add("other", 100, oct)
        val food = db.categoryDao().get("food")!!
        db.categoryDao().upsert(food.copy(deleted = true))
        val other = db.categoryDao().get("other")!!
        db.categoryDao().upsert(other.copy(hidden = true))
        assertEquals(emptyList(), repo.observe().first())
        assertNull(repo.observeOne("food").first())
    }

    @Test fun `edits mark rows dirty with deterministic ids`() = runTest {
        repo.add(null, 30000, oct)
        repo.setMonth(null, oct, 25000)
        val ids = db.budgetDao().allRows().map { it.id }.sorted()
        assertEquals(listOf("budget-total-0", "budget-total-202610", "budget-total-202611"), ids)
        assertEquals(true, db.budgetDao().allRows().all { it.dirty })
    }
}
