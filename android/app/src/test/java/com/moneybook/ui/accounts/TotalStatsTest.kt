package com.moneybook.ui.accounts

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.AccountRepository
import com.moneybook.data.CurrencyRepository
import com.moneybook.data.DefaultData
import com.moneybook.data.Seeder
import com.moneybook.data.TimeSource
import com.moneybook.data.TxnRepository
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.domain.Totals
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.TxnType.EXPENSE
import com.moneybook.shared.model.TxnType.INCOME
import com.moneybook.testutil.FakeSettingsRepository
import com.moneybook.testutil.Fixtures
import com.moneybook.testutil.MainDispatcherRule
import com.moneybook.testutil.inMemoryDb
import com.moneybook.ui.theme.MoneyBookTheme
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
class TotalStatsTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val rule = createComposeRule()
    private lateinit var db: MoneyBookDatabase
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneOffset.UTC))
    private val cash = DefaultData.accountId(AccountGroupType.CASH)
    private val cashGroup = DefaultData.groupId(AccountGroupType.CASH)

    @Before fun setUp() {
        db = inMemoryDb()
        runBlocking {
            Seeder(db, time).seedIfEmpty("CZK")
            db.accountDao().upsert(db.accountDao().get(cash)!!.copy(initialBalance = 100_000))
            db.accountDao().upsertAll(listOf(
                Fixtures.account("hidden", groupId = cashGroup, initialBalance = 999_900, hidden = true),
                Fixtures.account("outside", groupId = cashGroup, initialBalance = 5_000, includeInTotals = false),
            ))
            db.txnDao().upsertAll(listOf(
                Fixtures.txn("in", INCOME, "2026-09-10T12:00:00.000", cash, 50_000),
                Fixtures.txn("out", EXPENSE, "2026-08-05T12:00:00.000", cash, 20_000),
                Fixtures.txn("h", EXPENSE, "2026-09-11T12:00:00.000", "hidden", 1_000),
            ))
        }
    }

    @After fun tearDown() = db.close()

    private fun vm() = TotalStatsViewModel(TxnRepository(db, time), AccountRepository(db, time), FakeSettingsRepository(), time, CurrencyRepository(db, FakeSettingsRepository(), time) {})

    @Test fun sixMonthsOfBalancesAndTotals() = runTest {
        val vm = vm()
        val s = vm.state.first { it.months.isNotEmpty() }
        assertEquals((4..9).map { YearMonth.of(2026, it) }, s.months)
        // Hidden and "not in totals" accounts stay out of the balance line.
        assertEquals(listOf<Long>(100_000, 100_000, 100_000, 100_000, 80_000, 130_000), s.balances)
        assertEquals(130_000L, s.balance)
        assertEquals(Totals(0, 20_000), s.totals[4])
        assertEquals(Totals(50_000, 1_000), s.totals[5])
        vm.previous()
        assertEquals(80_000L, vm.state.first { it.month == YearMonth.of(2026, 8) }.balance)
    }

    @Test fun contentShowsTheBalanceAndBothCharts() {
        val state = runBlocking { vm().state.first { it.months.isNotEmpty() } }
        rule.setContent { MoneyBookTheme(darkTheme = false) { TotalStatsContent(state, {}, {}, {}) } }
        listOf("Total Stats", "Sep 2026", "Balance").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        rule.onAllNodesWithText("1,300.00").onFirst().assertIsDisplayed()
        rule.onAllNodesWithText("Apr").onFirst().assertIsDisplayed()
    }
}
