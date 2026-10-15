package com.moneybook.ui.trans

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.AccountRepository
import com.moneybook.data.AppSettings
import com.moneybook.data.BudgetRepository
import com.moneybook.data.CategoryRepository
import com.moneybook.data.DefaultData
import com.moneybook.data.MemoRepository
import com.moneybook.data.Seeder
import com.moneybook.data.TimeSource
import com.moneybook.data.TxnRepository
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.domain.CARRY_OVER_ID
import com.moneybook.domain.Totals
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.TxnType.EXPENSE
import com.moneybook.shared.model.TxnType.INCOME
import com.moneybook.testutil.FakeSettingsRepository
import com.moneybook.testutil.Fixtures
import com.moneybook.testutil.MainDispatcherRule
import com.moneybook.testutil.inMemoryDb
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Configuration → Carry-over Setting: the synthetic row in Daily and Calendar only (notes-settings.md E). */
@RunWith(AndroidJUnit4::class)
class TransCarryOverTest {
    @get:Rule val main = MainDispatcherRule()
    private lateinit var db: MoneyBookDatabase
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneOffset.UTC))
    private val cash = DefaultData.accountId(AccountGroupType.CASH)
    private val settings = FakeSettingsRepository(AppSettings("CZK", carryOver = true))
    private lateinit var vm: TransViewModel
    private val sep1 = LocalDate.of(2026, 9, 1)

    @Before fun setUp() {
        db = inMemoryDb()
        runBlocking {
            Seeder(db, time).seedIfEmpty("CZK")
            db.txnDao().upsertAll(listOf(
                Fixtures.txn("pay", INCOME, "2026-08-10T09:00:00.000", cash, 10_000),
                Fixtures.txn("old", EXPENSE, "2026-08-20T09:00:00.000", cash, 3_000),
                Fixtures.txn("a", EXPENSE, "2026-09-26T18:40:00.000", cash, 500),
            ))
        }
        vm = TransViewModel(TxnRepository(db, time), settings, time, CategoryRepository(db, time), AccountRepository(db, time), MemoRepository(db, time), BudgetRepository(db, time))
    }

    @After fun tearDown() = db.close()

    @Test fun dailyAddsTheRowToTheFirstDayAndTheHeader() = runTest {
        val s = vm.state.first { it.dayGroups.size == 2 }
        assertEquals(Totals(7_000, 500), s.summary)
        val first = s.dayGroups.last()
        assertEquals(sep1, first.date)
        assertEquals(listOf(CARRY_OVER_ID), first.rows.map { it.id })
        assertEquals(Totals(7_000, 0), first.totals)
        assertEquals(7_000L, first.rows.single().amountMain)
    }

    @Test fun offLeavesDailyAsItWas() = runTest {
        settings.state.value = settings.state.value.copy(carryOver = false)
        val s = vm.state.first { it.dayGroups.size == 1 }
        assertEquals(Totals(0, 500), s.summary)
        assertTrue(s.dayGroups.none { g -> g.rows.any { it.id == CARRY_OVER_ID } })
    }

    @Test fun aFilterHidesIt() = runTest {
        vm.state.first { it.dayGroups.size == 2 }
        vm.toggleAccount(cash)
        val s = vm.state.first { it.filter.isActive && it.dayGroups.isNotEmpty() }
        assertEquals(Totals(0, 500), s.summary)
        assertEquals(1, s.dayGroups.size)
    }

    @Test fun calendarShowsItInTheFirstCellDayListAndHeader() = runTest {
        vm.selectTab(TransTab.CALENDAR)
        val s = vm.state.first { it.tab == TransTab.CALENDAR && it.calendar.isNotEmpty() && it.summary.income > 0 }
        assertEquals(Totals(7_000, 500), s.summary)
        assertEquals(Totals(7_000, 0), s.calendar.single { it.date == sep1 }.totals)
        assertEquals(listOf(CARRY_OVER_ID), s.calendarDays.getValue(sep1).rows.map { it.id })
    }

    @Test fun monthlyAndTotalAreUnchanged() = runTest {
        vm.selectTab(TransTab.MONTHLY)
        val m = vm.state.first { it.tab == TransTab.MONTHLY && it.monthly.isNotEmpty() }
        assertEquals(Totals(10_000, 3_500), m.summary)
        assertEquals(Totals(0, 500), m.monthly.first().totals)
        vm.selectTab(TransTab.TOTAL)
        val t = vm.state.first { it.tab == TransTab.TOTAL && it.total != null }
        assertEquals(Totals(0, 500), t.summary)
    }
}
