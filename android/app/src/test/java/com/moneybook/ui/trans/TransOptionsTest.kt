package com.moneybook.ui.trans

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.AccountRepository
import com.moneybook.data.AppSettings
import com.moneybook.data.BudgetRepository
import com.moneybook.data.CategoryRepository
import com.moneybook.data.DefaultData
import com.moneybook.data.MemoRepository
import com.moneybook.data.Seeder
import com.moneybook.data.StartScreen
import com.moneybook.data.SwipeMode
import com.moneybook.data.TimeInput
import com.moneybook.data.TimeSource
import com.moneybook.data.TxnRepository
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.domain.WeekendShift
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.TxnType.EXPENSE
import com.moneybook.testutil.FakeSettingsRepository
import com.moneybook.testutil.Fixtures
import com.moneybook.testutil.MainDispatcherRule
import com.moneybook.testutil.inMemoryDb
import com.moneybook.ui.accounts.AccountDetailViewModel
import java.time.Clock
import java.time.Instant
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
class TransOptionsTest {
    @get:Rule val main = MainDispatcherRule()
    private lateinit var db: MoneyBookDatabase
    // 2026-09-26 is a Saturday; 2026-08-01 is a Saturday too, so with "Previous Friday" August starts on Jul 31.
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneOffset.UTC))
    private val cash = DefaultData.accountId(AccountGroupType.CASH)

    @Before fun setUp() {
        db = inMemoryDb()
        runBlocking {
            Seeder(db, time).seedIfEmpty("CZK")
            db.txnDao().upsertAll(listOf(
                Fixtures.txn("jul31", EXPENSE, "2026-07-31T12:00:00.000", cash, 100),
                Fixtures.txn("evening", EXPENSE, "2026-09-10T20:00:00.000", cash, 100),
                Fixtures.txn("morning", EXPENSE, "2026-09-10T08:00:00.000", cash, 100),
            ))
        }
    }

    @After fun tearDown() = db.close()

    private fun vm(s: AppSettings) = TransViewModel(
        TxnRepository(db, time), FakeSettingsRepository(s), time, CategoryRepository(db, time), AccountRepository(db, time), MemoRepository(db, time), BudgetRepository(db, time),
    )

    @Test fun opensOnTheStartDayMonthAndTheStartScreen() = runTest {
        val s = vm(AppSettings("CZK", monthStartDay = 27, startScreen = StartScreen.CALENDAR))
            .state.first { it.tab == TransTab.CALENDAR && it.month == YearMonth.of(2026, 8) }
        assertEquals(YearMonth.of(2026, 8), s.month) // Sep 26 is before the 27th, so it still belongs to "August"
    }

    @Test fun theWeekendRuleMovesTheMonthsRange() = runTest {
        val vm = vm(AppSettings("CZK", monthStartWeekend = WeekendShift.PREVIOUS_FRIDAY))
        vm.state.first { it.month == YearMonth.of(2026, 9) && it.dayGroups.isNotEmpty() }
        vm.previous()
        val august = vm.state.first { it.month == YearMonth.of(2026, 8) && it.dayGroups.isNotEmpty() }
        assertEquals(listOf("jul31"), august.dayGroups.flatMap { g -> g.rows.map { it.id } })
    }

    @Test fun ascendingListsADayOldestFirst() = runTest {
        val s = vm(AppSettings("CZK", timeInput = TimeInput.INPUT_ASC)).state.first { it.dayGroups.isNotEmpty() }
        assertEquals(listOf("morning", "evening"), s.dayGroups.single().rows.map { it.id })
    }

    @Test fun swipeAndNoteButtonReachTheStateAndTabsStepWithoutWrapping() = runTest {
        val vm = vm(AppSettings("CZK", swipe = SwipeMode.TAB, noteButton = false))
        val s = vm.state.first { it.swipe == SwipeMode.TAB }
        assertEquals(false, s.noteButton)
        vm.nextTab()
        vm.state.first { it.tab == TransTab.CALENDAR }
        vm.previousTab()
        vm.previousTab()
        assertEquals(TransTab.DAILY, vm.state.first { it.tab == TransTab.DAILY }.tab)
    }

    @Test fun accountDetailOpensOnTheStartDayMonth() = runTest {
        val detail = AccountDetailViewModel(cash, TxnRepository(db, time), AccountRepository(db, time), FakeSettingsRepository(AppSettings("CZK", monthStartDay = 27)), time)
        assertEquals(YearMonth.of(2026, 8), detail.state.first { it.month == YearMonth.of(2026, 8) }.month)
    }

    @Test fun changingTheStartDayMovesToTheMonthThatHoldsToday() = runTest {
        val settings = FakeSettingsRepository(AppSettings("CZK"))
        val vm = TransViewModel(
            TxnRepository(db, time), settings, time, CategoryRepository(db, time), AccountRepository(db, time), MemoRepository(db, time), BudgetRepository(db, time),
        )
        vm.state.first { it.month == YearMonth.of(2026, 9) }
        settings.replace(AppSettings("CZK", monthStartDay = 27))
        // Sep 26 is before the 27th, so today now belongs to "August".
        assertEquals(YearMonth.of(2026, 8), vm.state.first { it.month == YearMonth.of(2026, 8) }.month)
    }
}
