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
import com.moneybook.domain.BudgetLine
import com.moneybook.domain.DateRange
import com.moneybook.domain.TOTAL_BUDGET
import com.moneybook.domain.TotalBreakdown
import com.moneybook.domain.Totals
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.TxnType.EXPENSE
import com.moneybook.shared.model.TxnType.INCOME
import com.moneybook.shared.model.TxnType.TRANSFER
import com.moneybook.testutil.FakeSettingsRepository
import com.moneybook.testutil.Fixtures
import com.moneybook.testutil.MainDispatcherRule
import com.moneybook.testutil.inMemoryDb
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
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
class TransViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private lateinit var db: MoneyBookDatabase
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneOffset.UTC))
    private lateinit var vm: TransViewModel

    @Before fun setUp() {
        db = inMemoryDb()
        runBlocking {
            Seeder(db, time).seedIfEmpty("CZK")
            val cash = db.accountDao().accounts().first().id
            db.txnDao().upsertAll(listOf(
                Fixtures.txn("a", EXPENSE, "2026-09-26T18:40:00.000", cash, 52_500),
                Fixtures.txn("b", EXPENSE, "2026-09-25T16:50:00.000", cash, 1_500),
                Fixtures.txn("c", EXPENSE, "2026-09-25T12:00:00.000", cash, 18_600),
                Fixtures.txn("d", INCOME, "2026-08-10T09:00:00.000", cash, 4_650_000),
            ))
        }
        vm = TransViewModel(TxnRepository(db, time), FakeSettingsRepository(), time, CategoryRepository(db, time), AccountRepository(db, time), MemoRepository(db, time), BudgetRepository(db, time))
    }

    @After fun tearDown() = db.close()

    @Test fun dailyShowsCurrentMonthGroupedByDay() = runTest {
        val s = vm.state.first { it.dayGroups.size == 2 }
        assertEquals("Sep 2026", s.title)
        assertEquals(Totals(0, 72_600), s.summary)
        assertEquals(listOf(LocalDate.of(2026, 9, 26), LocalDate.of(2026, 9, 25)), s.dayGroups.map { it.date })
        assertEquals("CZK", s.currency.code)
    }

    @Test fun previousAndNextChangeMonth() = runTest {
        vm.state.first { it.dayGroups.isNotEmpty() }
        vm.previous()
        val aug = vm.state.first { it.month == YearMonth.of(2026, 8) && it.dayGroups.isNotEmpty() }
        assertEquals(Totals(4_650_000, 0), aug.summary)
        vm.next()
        vm.next()
        val oct = vm.state.first { it.month == YearMonth.of(2026, 10) }
        assertEquals(emptyList<Any>(), oct.dayGroups)
    }

    @Test fun totalTabSplitsExpensesByAccountKindAndComparesWithLastMonth() = runTest {
        val cash = DefaultData.accountId(AccountGroupType.CASH)
        val card = DefaultData.accountId(AccountGroupType.CARD)
        val bank = DefaultData.accountId(AccountGroupType.ACCOUNTS)
        db.txnDao().upsertAll(listOf(
            Fixtures.txn("k1", EXPENSE, "2026-09-05T10:00:00.000", card, 10_000),
            Fixtures.txn("k2", TRANSFER, "2026-09-06T10:00:00.000", cash, 5_000, toAccountId = card),
            Fixtures.txn("k3", TRANSFER, "2026-09-07T10:00:00.000", card, 1_000, toAccountId = cash),
            Fixtures.txn("k4", TRANSFER, "2026-09-08T10:00:00.000", cash, 2_000, toAccountId = bank),
            Fixtures.txn("k5", EXPENSE, "2026-08-05T10:00:00.000", cash, 60_300),
        ))
        vm.selectTab(TransTab.TOTAL)
        val s = vm.state.first { it.tab == TransTab.TOTAL && it.total != null }
        assertEquals(Totals(0, 82_600), s.summary)
        // 82,600 / 60,300 = 137 %; the card-to-cash transfer is not counted.
        assertEquals(TotalBreakdown(137, 72_600, 10_000, 7_000), s.total)
        assertEquals(DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1)), s.totalRange)
        assertEquals("Sep 2026", s.title)
    }

    @Test fun totalShowsBudgetsIgnoringTheFilter() = runTest {
        val cash = db.accountDao().accounts().first().id
        db.categoryDao().upsert(Fixtures.category("other", name = "Other", sortOrder = 99))
        db.txnDao().upsert(Fixtures.txn("o", EXPENSE, "2026-09-20T10:00:00.000", cash, 21_400, categoryId = "other"))
        BudgetRepository(db, time).add("other", 10_000, 202609)
        vm.selectTab(TransTab.TOTAL)
        val expected = listOf(
            BudgetLine(null, TOTAL_BUDGET, 10_000, 21_400, synthetic = true),
            BudgetLine("other", "Other", 10_000, 21_400),
        )
        val s = vm.state.first { it.tab == TransTab.TOTAL && it.budgets.isNotEmpty() }
        assertEquals(expected, s.budgets)
        assertEquals(26f / 30f, s.budgetToday)
        vm.toggleCategory("other")
        val filtered = vm.state.first { it.filter.isActive && it.tab == TransTab.TOTAL && it.total != null }
        assertEquals(expected, filtered.budgets)
    }
}
