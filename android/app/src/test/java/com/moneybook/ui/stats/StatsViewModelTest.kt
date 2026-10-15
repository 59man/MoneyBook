package com.moneybook.ui.stats

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.AppSettings
import com.moneybook.data.CategoryRepository
import com.moneybook.data.DefaultData
import com.moneybook.data.Seeder
import com.moneybook.data.TimeSource
import com.moneybook.data.TxnRepository
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.domain.StatsMode
import com.moneybook.domain.StatsPeriod
import com.moneybook.domain.Totals
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.CategoryType
import com.moneybook.shared.model.TxnType.EXPENSE
import com.moneybook.shared.model.TxnType.INCOME
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
class StatsViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private lateinit var db: MoneyBookDatabase
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneOffset.UTC))
    private val cash = DefaultData.accountId(AccountGroupType.CASH)
    private val food = DefaultData.categoryId(CategoryType.EXPENSE, 0)
    private val transport = DefaultData.categoryId(CategoryType.EXPENSE, 3)
    private val otherExpense = DefaultData.categoryId(CategoryType.EXPENSE, 11)
    private val salary = DefaultData.categoryId(CategoryType.INCOME, 1)

    @Before fun setUp() {
        db = inMemoryDb()
        runBlocking {
            Seeder(db, time).seedIfEmpty("CZK")
            db.txnDao().upsertAll(listOf(
                Fixtures.txn("f1", EXPENSE, "2026-09-03T12:00:00.000", cash, 30_000, categoryId = food),
                Fixtures.txn("t1", EXPENSE, "2026-09-20T12:00:00.000", cash, 10_000, categoryId = transport),
                Fixtures.txn("x", EXPENSE, "2026-09-12T12:00:00.000", cash, 2_000),
                Fixtures.txn("s1", INCOME, "2026-09-10T12:00:00.000", cash, 250_000, categoryId = salary),
                Fixtures.txn("f0", EXPENSE, "2026-08-15T12:00:00.000", cash, 5_000, categoryId = food),
            ))
        }
    }

    @After fun tearDown() = db.close()

    private fun vm(settings: AppSettings = AppSettings("CZK")) =
        StatsViewModel(TxnRepository(db, time), CategoryRepository(db, time), FakeSettingsRepository(settings), time)

    @Test fun opensOnThisMonthsExpensesByCategory() = runTest {
        val s = vm().state.first { it.slices.isNotEmpty() }
        assertEquals(StatsPeriod.Month(YearMonth.of(2026, 9)), s.period)
        assertEquals(EXPENSE, s.type)
        assertEquals(Totals(250_000, 42_000), s.totals)
        assertEquals(listOf("🍜 Food" to 30_000L, "🚖 Transport" to 10_000L, "Other" to 2_000L), s.slices.map { it.name to it.amount })
        assertEquals(otherExpense, s.slices.last().key) // the entry without a category counts as "Other"
    }

    @Test fun incomeTabAndOtherPeriods() = runTest {
        val vm = vm()
        vm.state.first { it.slices.isNotEmpty() }
        vm.selectType(INCOME)
        assertEquals(listOf("💰 Salary"), vm.state.first { it.type == INCOME }.slices.map { it.name })
        vm.selectType(EXPENSE)
        vm.selectMode(StatsMode.ANNUALLY)
        assertEquals(47_000L, vm.state.first { it.period == StatsPeriod.Year(2026) && it.type == EXPENSE }.totals.expense)
        vm.selectMode(StatsMode.MONTHLY)
        vm.state.first { it.period == StatsPeriod.Month(YearMonth.of(2026, 9)) }
        vm.previous()
        assertEquals(5_000L, vm.state.first { it.period == StatsPeriod.Month(YearMonth.of(2026, 8)) }.totals.expense)
        vm.next()
        vm.state.first { it.period == StatsPeriod.Month(YearMonth.of(2026, 9)) }
        vm.setCustom(LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 3)) // picked backwards
        val custom = vm.state.first { it.period is StatsPeriod.Custom }
        assertEquals(StatsPeriod.Custom(LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 20)), custom.period)
        assertEquals(42_000L, custom.totals.expense) // both end days are included
    }

    @Test fun monthsFollowTheMonthlyStartDate() = runTest {
        val s = vm(AppSettings("CZK", monthStartDay = 5)).state.first { it.slices.isNotEmpty() }
        // Sep 26 falls in the month Sep 5 – Oct 4, so the Sep 3 entry belongs to the August month.
        assertEquals(StatsPeriod.Month(YearMonth.of(2026, 9)), s.period)
        assertEquals(12_000L, s.totals.expense)
    }

    @Test fun allTimeCountsEveryEntry() = runTest {
        val vm = vm()
        vm.state.first { it.slices.isNotEmpty() }
        vm.selectMode(StatsMode.ALL_TIME)
        val s = vm.state.first { it.period == StatsPeriod.AllTime && it.slices.isNotEmpty() }
        assertEquals(Totals(250_000, 47_000), s.totals)
        assertEquals("🍜 Food" to 35_000L, s.slices.first().let { it.name to it.amount })
        vm.previous()
        assertEquals(StatsPeriod.AllTime, vm.state.value.period)
    }
}
