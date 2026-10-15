package com.moneybook.ui.trans

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
import com.moneybook.domain.Periods
import com.moneybook.domain.Totals
import com.moneybook.domain.buildCalendar
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalendarTest {
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
                Fixtures.txn("aug30", EXPENSE, "2026-08-30T10:00:00.000", cash, 91_200),
                Fixtures.txn("sep1", INCOME, "2026-09-01T10:00:00.000", cash, 5_230_000),
                Fixtures.txn("sep1b", EXPENSE, "2026-09-01T11:00:00.000", cash, 640_000),
            ))
        }
    }

    @After fun tearDown() = db.close()

    @Test fun calendarStateCoversGridButSummaryOnlyTheMonth() = runTest {
        val vm = TransViewModel(TxnRepository(db, time), FakeSettingsRepository(), time, CategoryRepository(db, time), AccountRepository(db, time), MemoRepository(db, time), BudgetRepository(db, time))
        vm.selectTab(TransTab.CALENDAR)
        val s = vm.state.first { it.tab == TransTab.CALENDAR && it.calendar.isNotEmpty() }
        assertEquals(42, s.calendar.size)
        assertEquals(91_200L, s.calendar.first().totals.expense)
        assertEquals(Totals(5_230_000, 640_000), s.summary)
        assertEquals(2, s.calendarDays.getValue(LocalDate.of(2026, 9, 1)).rows.size)
        assertEquals("Sep 2026", s.title)
    }

    @Test fun calendarShowsFirstOfMonthLabelAndAmountsAndReportsClicks() {
        val period = Periods.month(YearMonth.of(2026, 9), 1)
        val rows = runBlocking { TxnRepository(db, time).observeRange(com.moneybook.domain.calendarGrid(period, DayOfWeek.SUNDAY)).first() }
        val cells = buildCalendar(period, DayOfWeek.SUNDAY, LocalDate.of(2026, 9, 26), rows)
        var clicked: LocalDate? = null
        rule.setContent {
            MoneyBookTheme(darkTheme = false) { CalendarTab(cells, DayOfWeek.SUNDAY, CurrencySpec.of("CZK")) { clicked = it } }
        }
        listOf("Sun", "Sat", "9.1", "52,300.00", "6,400.00", "45,900.00", "912.00").forEach {
            rule.onNodeWithText(it).assertIsDisplayed()
        }
        rule.onNodeWithText("9.1").performClick()
        assertEquals(LocalDate.of(2026, 9, 1), clicked)
    }
}
