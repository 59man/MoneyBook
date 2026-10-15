package com.moneybook.ui.trans

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.AccountRepository
import com.moneybook.data.BudgetRepository
import com.moneybook.data.CategoryRepository
import com.moneybook.data.DefaultData
import com.moneybook.data.MemoRepository
import com.moneybook.data.Seeder
import com.moneybook.data.TimeSource
import com.moneybook.data.TxnRepository
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.domain.Totals
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.CategoryType
import com.moneybook.shared.model.TxnType.EXPENSE
import com.moneybook.shared.model.TxnType.INCOME
import com.moneybook.shared.model.TxnType.TRANSFER
import com.moneybook.testutil.FakeSettingsRepository
import com.moneybook.testutil.Fixtures
import com.moneybook.testutil.MainDispatcherRule
import com.moneybook.testutil.inMemoryDb
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransFilterTest {
    @get:Rule val main = MainDispatcherRule()
    private lateinit var db: MoneyBookDatabase
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneOffset.UTC))
    private val cash = DefaultData.accountId(AccountGroupType.CASH)
    private val card = DefaultData.accountId(AccountGroupType.CARD)
    private val food = DefaultData.categoryId(CategoryType.EXPENSE, 0)
    private val transport = DefaultData.categoryId(CategoryType.EXPENSE, 3)
    private val salary = DefaultData.categoryId(CategoryType.INCOME, 1)
    private lateinit var vm: TransViewModel

    @Before fun setUp() {
        db = inMemoryDb()
        runBlocking {
            Seeder(db, time).seedIfEmpty("CZK")
            db.categoryDao().upsert(Fixtures.category("lunch", name = "Lunch", parentId = food))
            db.txnDao().upsertAll(listOf(
                Fixtures.txn("f", EXPENSE, "2026-09-03T12:00:00.000", cash, 30_000, categoryId = food),
                Fixtures.txn("l", EXPENSE, "2026-09-04T12:00:00.000", cash, 1_000, categoryId = "lunch"),
                Fixtures.txn("b", EXPENSE, "2026-09-05T12:00:00.000", card, 10_000, categoryId = transport),
                Fixtures.txn("s", INCOME, "2026-09-06T12:00:00.000", cash, 250_000, categoryId = salary),
                Fixtures.txn("t", TRANSFER, "2026-09-07T12:00:00.000", cash, 5_000, toAccountId = card),
            ))
        }
        vm = TransViewModel(TxnRepository(db, time), FakeSettingsRepository(), time, CategoryRepository(db, time), AccountRepository(db, time), MemoRepository(db, time), BudgetRepository(db, time))
    }

    @After fun tearDown() = db.close()

    private fun ids(s: TransUiState) = s.dayGroups.flatMap { g -> g.rows.map { it.id } }

    @Test fun tickingACategoryFiltersTheDailyListAndItsTotals() = runTest {
        vm.state.first { it.dayGroups.size == 5 }
        vm.toggleCategory(food)
        val s = vm.state.first { it.filter.isActive && it.dayGroups.isNotEmpty() }
        assertEquals(listOf("l", "f"), ids(s))
        assertEquals(Totals(0, 31_000), s.summary)
        vm.toggleCategory(food)
        assertEquals(5, ids(vm.state.first { !it.filter.isActive && it.dayGroups.size == 5 }).size)
    }

    @Test fun tickingAnAccountKeepsItsEntriesAndTransfersIntoIt() = runTest {
        vm.toggleAccount(card)
        assertEquals(listOf("t", "b"), ids(vm.state.first { it.filter.isActive && it.dayGroups.isNotEmpty() }))
    }

    @Test fun theFilterAppliesToTheOtherTabsToo() = runTest {
        vm.setAllCategories(listOf(food), ticked = true)
        vm.selectTab(TransTab.MONTHLY)
        assertEquals(Totals(0, 31_000), vm.state.first { it.tab == TransTab.MONTHLY && it.monthly.isNotEmpty() }.summary)
        vm.selectTab(TransTab.TOTAL)
        assertEquals(31_000L, vm.state.first { it.tab == TransTab.TOTAL && it.total != null }.total!!.nonCardExpenses)
    }

    @Test fun optionsShowTheMonthAndTheTickedShare() = runTest {
        vm.toggleCategory(transport)
        val o = vm.filterOptions.first { it.selected.expense > 0 }
        assertEquals(Totals(250_000, 41_000), o.month)
        assertEquals(24L, o.expensePercent) // 10,000 / 41,000
        assertEquals(31_000L, o.expense.first { it.id == food }.amount)
        assertEquals(listOf("Cash", "Accounts", "Card"), o.groups.map { it.name })
        assertEquals(5_000L, o.groups.last().accounts.single().incoming)
    }
}
