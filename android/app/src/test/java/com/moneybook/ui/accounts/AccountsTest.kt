package com.moneybook.ui.accounts

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.AccountRepository
import com.moneybook.data.CurrencyRepository
import com.moneybook.data.DefaultData
import com.moneybook.data.Seeder
import com.moneybook.data.TimeSource
import com.moneybook.data.TxnRepository
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.domain.AccountGroupItem
import com.moneybook.domain.AccountItem
import com.moneybook.domain.AccountPeriodSummary
import com.moneybook.domain.AccountsOverview
import com.moneybook.domain.DateRange
import com.moneybook.domain.buildAccountsOverview
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.TxnType
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.testutil.FakeSettingsRepository
import com.moneybook.testutil.Fixtures
import com.moneybook.testutil.MainDispatcherRule
import com.moneybook.testutil.inMemoryDb
import com.moneybook.ui.theme.MoneyBookTheme
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

@RunWith(AndroidJUnit4::class)
class AccountsTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val rule = createComposeRule()
    private lateinit var db: MoneyBookDatabase
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneOffset.UTC))
    private val cash = DefaultData.accountId(AccountGroupType.CASH)
    private val bank = DefaultData.accountId(AccountGroupType.ACCOUNTS)

    @Before fun setUp() {
        db = inMemoryDb()
        runBlocking {
            Seeder(db, time).seedIfEmpty("CZK")
            db.accountDao().upsert(db.accountDao().get(cash)!!.copy(initialBalance = 40_000_000))
            db.txnDao().upsertAll(listOf(
                Fixtures.txn("i", TxnType.INCOME, "2026-09-01T10:00:00.000", cash, 5_230_000),
                Fixtures.txn("e", TxnType.EXPENSE, "2026-09-02T10:00:00.000", cash, 2_915_000),
                Fixtures.txn("t", TxnType.TRANSFER, "2026-09-03T10:00:00.000", cash, 100_000, toAccountId = bank),
                Fixtures.txn("old", TxnType.EXPENSE, "2026-08-02T10:00:00.000", cash, 5_000),
            ))
        }
    }

    @After fun tearDown() = db.close()

    @Test fun overviewShowsBalances() = runTest {
        val vm = AccountsViewModel(AccountRepository(db, time), CurrencyRepository(db, FakeSettingsRepository(), time) {}, time)
        val s = vm.state.first { it.overview.groups.isNotEmpty() }
        val expectedCash = 40_000_000L + 5_230_000 - 2_915_000 - 100_000 - 5_000
        assertEquals(expectedCash + 100_000, s.overview.assets)
        assertEquals(listOf("Cash", "Accounts", "Card"), s.overview.groups.map { it.name })
        assertEquals(expectedCash, s.overview.groups.first().accounts.single().balance)
    }

    @Test fun detailSummarisesTheMonthAndBalanceAtMonthEnd() = runTest {
        val vm = AccountDetailViewModel(cash, TxnRepository(db, time), AccountRepository(db, time), FakeSettingsRepository(), time)
        val s = vm.state.first { it.name.isNotEmpty() && it.dayGroups.isNotEmpty() }
        assertEquals("Cash", s.name)
        assertEquals(AccountPeriodSummary(deposit = 5_230_000, withdrawal = 3_015_000), s.summary)
        assertEquals(40_000_000L + 5_230_000 - 2_915_000 - 100_000 - 5_000, s.balance)
        vm.previous()
        val aug = vm.state.first { it.title == "Aug 2026" && it.dayGroups.isNotEmpty() }
        assertEquals(40_000_000L - 5_000, aug.balance)
    }

    @Test fun accountsContentListsGroupsAndOpensAccount() {
        val state = runBlocking {
            AccountsViewModel(AccountRepository(db, time), CurrencyRepository(db, FakeSettingsRepository(), time) {}, time).state.first { it.overview.groups.isNotEmpty() }
        }
        var opened: String? = null
        rule.setContent { MoneyBookTheme(darkTheme = false) { AccountsContent(state, onOpenAccount = { opened = it }, onAddAccount = null) } }
        listOf("Assets", "Liabilities", "Total", "423,100.00").forEach { rule.onAllNodesWithText(it).onFirst().assertIsDisplayed() }
        rule.onAllNodesWithText("Cash")[1].performClick()
        assertEquals(cash, opened)
    }

    @Test fun hiddenAccountLeavesTotalsButNotEntries() = runTest {
        val repo = AccountRepository(db, time)
        repo.setHidden(cash, true)
        val vm = AccountsViewModel(repo, CurrencyRepository(db, FakeSettingsRepository(), time) {}, time)
        val s = vm.state.first { it.withHidden.groups.isNotEmpty() }
        assertTrue(s.overview.groups.flatMap { it.accounts }.none { it.id == cash })
        assertEquals(100_000L, s.overview.assets)
        assertTrue(s.withHidden.groups.flatMap { it.accounts }.single { it.id == cash }.hidden)
        val rows = TxnRepository(db, time).observeRange(DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1))).first()
        assertEquals(3, rows.count { it.accountId == cash })
        repo.setHidden(cash, false)
        vm.state.first { st -> st.overview.groups.flatMap { it.accounts }.any { it.id == cash } }
    }

    @Test fun reorderSortsAccountsWithinTheirGroup() = runTest {
        val repo = AccountRepository(db, time)
        repo.save(Fixtures.account("rev", groupId = DefaultData.groupId(AccountGroupType.ACCOUNTS), name = "Revolut", sortOrder = 5))
        repo.reorder(listOf("rev", bank))
        val names = buildAccountsOverview(repo.observeBalances().first()).groups
            .single { it.type == AccountGroupType.ACCOUNTS }.accounts.map { it.name }
        assertEquals(listOf("Revolut", "Accounts"), names)
    }

    @Test fun menuModesDriveTheirActions() {
        val cashItem = AccountItem(cash, "Cash", 1_000, "CZK")
        val hiddenBank = AccountItem(bank, "Bank", 0, "CZK", hidden = true)
        val group = AccountGroupItem("g", "Cash", AccountGroupType.CASH, 1_000, listOf(cashItem))
        val state = AccountsUiState(
            CurrencySpec.of("CZK"),
            AccountsOverview(1_000, 0, listOf(group)),
            AccountsOverview(1_000, 0, listOf(group.copy(accounts = listOf(cashItem, hiddenBank)))),
        )
        var hidden: Pair<String, Boolean>? = null
        var deleted: String? = null
        rule.setContent {
            MoneyBookTheme(darkTheme = false) {
                AccountsContent(state, {}, {}, AccountsActions(onSetHidden = { id, h -> hidden = id to h }, onDelete = { deleted = it }))
            }
        }
        rule.onNodeWithContentDescription("Menu").performClick()
        rule.onNodeWithText("Show/Hide").performClick()
        rule.onNodeWithContentDescription("Show Bank").performClick()
        assertEquals(bank to false, hidden)
        rule.onNodeWithContentDescription("Done").performClick()
        rule.onNodeWithContentDescription("Menu").performClick()
        rule.onNodeWithText("Delete").performClick()
        rule.onNodeWithContentDescription("Delete Cash").performClick()
        assertEquals(null, deleted) // the minus only arms the row
        rule.onNodeWithContentDescription("Trash Cash").performClick()
        assertEquals(cash, deleted)
    }

    @Test fun chartButtonOpensTotalStats() {
        var opened = false
        rule.setContent { MoneyBookTheme(darkTheme = false) { AccountsContent(AccountsUiState(), {}, {}, onOpenTotalStats = { opened = true }) } }
        rule.onNodeWithContentDescription("Total Stats").performClick()
        assertTrue(opened)
    }
}
