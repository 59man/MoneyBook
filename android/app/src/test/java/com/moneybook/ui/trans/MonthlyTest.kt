package com.moneybook.ui.trans

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.AccountRepository
import com.moneybook.data.BudgetRepository
import com.moneybook.data.CategoryRepository
import com.moneybook.data.MemoRepository
import com.moneybook.data.Seeder
import com.moneybook.data.TimeSource
import com.moneybook.data.TxnRepository
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.domain.Totals
import com.moneybook.domain.buildMonthly
import com.moneybook.shared.model.TxnType.EXPENSE
import com.moneybook.shared.model.TxnType.INCOME
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.testutil.FakeSettingsRepository
import com.moneybook.testutil.Fixtures
import com.moneybook.testutil.MainDispatcherRule
import com.moneybook.testutil.inMemoryDb
import com.moneybook.ui.theme.MoneyBookTheme
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
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

@RunWith(AndroidJUnit4::class)
class MonthlyTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val rule = createComposeRule()
    private lateinit var db: MoneyBookDatabase
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneOffset.UTC))

    @Before fun setUp() {
        db = inMemoryDb()
        runBlocking {
            Seeder(db, time).seedIfEmpty("CZK")
            val cash = db.accountDao().accounts().first().id
            db.txnDao().upsertAll(listOf(
                Fixtures.txn("sep", EXPENSE, "2026-09-24T10:00:00.000", cash, 471_500),
                Fixtures.txn("aug", INCOME, "2026-08-10T10:00:00.000", cash, 4_650_000),
                Fixtures.txn("y25", EXPENSE, "2025-03-10T10:00:00.000", cash, 100),
            ))
        }
    }

    @After fun tearDown() = db.close()

    @Test fun monthlyStateShowsYearTotalsExpandsCurrentMonthAndStepsYears() = runTest {
        val vm = TransViewModel(TxnRepository(db, time), FakeSettingsRepository(), time, CategoryRepository(db, time), AccountRepository(db, time), MemoRepository(db, time), BudgetRepository(db, time))
        vm.selectTab(TransTab.MONTHLY)
        val s = vm.state.first { it.tab == TransTab.MONTHLY && it.monthly.isNotEmpty() }
        assertEquals("2026", s.title)
        assertEquals(Totals(4_650_000, 471_500), s.summary)
        assertEquals(YearMonth.of(2026, 9), s.monthly.first().month)
        assertEquals(5, s.monthly.first().weeks.size)
        vm.toggleMonth(YearMonth.of(2026, 9))
        assertTrue(vm.state.first { it.monthly.isNotEmpty() && it.monthly.first().weeks.isEmpty() }.monthly.all { it.weeks.isEmpty() })
        vm.previous()
        val y25 = vm.state.first { it.title == "2025" && it.monthly.isNotEmpty() }
        assertEquals(12, y25.monthly.size)
        assertEquals(Totals(0, 100), y25.summary)
    }

    @Test fun monthlyRowsShowRangesAmountsAndCurrentWeekTotal() {
        val rows = runBlocking {
            TxnRepository(db, time).observeRange(com.moneybook.domain.monthlyQueryRange(2026, 1, DayOfWeek.SUNDAY)).first()
        }
        val months = buildMonthly(2026, 1, DayOfWeek.SUNDAY, LocalDate.of(2026, 9, 26), YearMonth.of(2026, 9), rows)
        var toggled: YearMonth? = null
        rule.setContent {
            MoneyBookTheme(darkTheme = false) { MonthlyTab(months, CurrencySpec.of("CZK")) { toggled = it } }
        }
        listOf("Sep", "09.01  ~  09.30", "09.20  ~  09.26", "Total -4,715.00Kč", "Aug").forEach {
            rule.onNodeWithText(it).assertIsDisplayed()
        }
        // August has only income, so its income column and its total show the same text.
        rule.onAllNodesWithText("46,500.00Kč", useUnmergedTree = true).assertCountEquals(2)
        rule.onNodeWithText("Aug").performClick()
        assertEquals(YearMonth.of(2026, 8), toggled)
    }
}
