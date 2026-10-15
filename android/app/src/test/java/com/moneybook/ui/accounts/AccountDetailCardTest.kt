package com.moneybook.ui.accounts

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.AccountRepository
import com.moneybook.data.DefaultData
import com.moneybook.data.Seeder
import com.moneybook.data.TimeSource
import com.moneybook.data.TxnRepository
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.domain.AccountPeriodSummary
import com.moneybook.domain.DateRange
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.TxnType
import com.moneybook.testutil.FakeSettingsRepository
import com.moneybook.testutil.Fixtures
import com.moneybook.testutil.MainDispatcherRule
import com.moneybook.testutil.inMemoryDb
import com.moneybook.ui.theme.MoneyBookTheme
import com.moneybook.ui.trans.DailyTab
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Realbyte's probe on realbyte_34, in MoneyBook: a card with S 20 / P 15, charges and two payments. */
@RunWith(AndroidJUnit4::class)
class AccountDetailCardTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val rule = createComposeRule()
    private lateinit var db: MoneyBookDatabase
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-10-07T20:00:00Z"), ZoneOffset.UTC))
    private val card = DefaultData.accountId(AccountGroupType.CARD)
    private val bank = DefaultData.accountId(AccountGroupType.ACCOUNTS)
    private val cash = DefaultData.accountId(AccountGroupType.CASH)

    @Before fun setUp() {
        db = inMemoryDb()
        runBlocking {
            Seeder(db, time).seedIfEmpty("CZK")
            db.accountDao().upsert(db.accountDao().get(card)!!.copy(statementDay = 20, paymentDay = 15, paymentAccountId = bank))
            db.txnDao().upsertAll(listOf(
                Fixtures.txn("aug", TxnType.EXPENSE, "2026-08-05T19:54:00.000", card, 400),
                Fixtures.txn("sep", TxnType.EXPENSE, "2026-09-10T19:53:00.000", card, 3_000),
                Fixtures.txn("oct", TxnType.EXPENSE, "2026-10-07T19:52:00.000", card, 5_000),
                Fixtures.txn("pay4", TxnType.TRANSFER, "2026-10-07T19:54:00.000", bank, 400, toAccountId = card),
                Fixtures.txn("pay40", TxnType.TRANSFER, "2026-10-07T19:55:00.000", bank, 4_000, toAccountId = card),
            ))
        }
    }

    @After fun tearDown() = db.close()

    private fun vm(id: String = card) = AccountDetailViewModel(id, TxnRepository(db, time), AccountRepository(db, time), FakeSettingsRepository(), time)

    @Test fun `daily shows the statement period, its sums and running balances`() = runTest {
        val s = vm().state.first { it.isCard && it.dayGroups.isNotEmpty() }
        assertEquals(DateRange(LocalDate.of(2026, 9, 20), LocalDate.of(2026, 10, 20)), s.period)
        assertEquals(AccountPeriodSummary(4_400, 5_000), s.summary)
        assertEquals(-4_000L, s.balance)
        assertEquals(-4_000L, s.balances["pay40"])
        assertEquals(-8_400L, s.balances["oct"])
    }

    /** Realbyte's account detail sums each day as the account's deposits and withdrawals, transfers included. */
    @Test fun `a day's header counts the account's deposits and withdrawals`() = runTest {
        val s = vm().state.first { it.isCard && it.dayGroups.isNotEmpty() }
        val oct7 = s.dayGroups.single { it.date == LocalDate.of(2026, 10, 7) }
        assertEquals(4_400L, oct7.totals.income)
        assertEquals(5_000L, oct7.totals.expense)
    }

    @Test fun `pay offers Balance Payable from the payment account`() = runTest {
        // owed 4,000 with 5,000 charged this period: nothing is payable yet.
        val s = vm().state.first { it.pay != null }
        assertEquals(PayPreset(bank, 0), s.pay)
        assertNull(vm(cash).state.first { it.name.isNotEmpty() }.pay)
    }

    @Test fun `monthly lists twelve statement months, December first`() = runTest {
        val vm = vm()
        vm.state.first { it.isCard }
        vm.selectTab(DetailTab.MONTHLY)
        val s = vm.state.first { it.tab == DetailTab.MONTHLY && it.lines.isNotEmpty() }
        assertEquals(12, s.lines.size)
        assertEquals("Dec", s.lines.first().title)
        val oct = s.lines.single { it.month == YearMonth.of(2026, 10) }.line
        assertEquals(-4_000L, oct.endBalance)
        assertEquals(-600L, oct.total)
        vm.open(s.lines.single { it.month == YearMonth.of(2026, 9) })
        assertEquals(YearMonth.of(2026, 9), vm.state.first { it.tab == DetailTab.DAILY && it.month == YearMonth.of(2026, 9) }.month)
    }

    @Test fun `annually without entries shows the current year`() = runTest {
        val vm = vm(cash)
        vm.selectTab(DetailTab.ANNUALLY)
        val s = vm.state.first { it.tab == DetailTab.ANNUALLY && it.lines.isNotEmpty() }
        assertEquals(listOf("2026"), s.lines.map { it.title })
    }

    @Test fun `screen shows the statement, the balance line and Pay`() {
        val s = runBlocking { vm().state.first { it.pay != null && it.dayGroups.isNotEmpty() } }
        var paid: PayPreset? = null
        rule.setContent { MoneyBookTheme(darkTheme = false) { AccountDetailContent(s, {}, {}, {}, onAdd = {}, onPay = { paid = it }) } }
        rule.onNodeWithText("Statement", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("9.20.26  ~  10.19.26", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("Pay").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(PayPreset(bank, 0), paid)
    }

    @Test fun `entries show the balance after them`() {
        val s = runBlocking { vm().state.first { it.dayGroups.isNotEmpty() } }
        rule.setContent { MoneyBookTheme(darkTheme = false) { DailyTab(s.dayGroups, s.currency, {}, s.balances) } }
        rule.onNodeWithText("(Balance  -40.00)", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("(-80.00)", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("(-84.00)", useUnmergedTree = true).assertExists()
    }
}
