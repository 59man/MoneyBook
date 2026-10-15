package com.moneybook.ui.more

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.LockState
import com.moneybook.MainActivity
import com.moneybook.MoneyBookApp
import com.moneybook.data.AccountRepository
import com.moneybook.data.DefaultData
import com.moneybook.data.Seeder
import com.moneybook.data.TimeSource
import com.moneybook.data.db.AccountEntity
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.testutil.MainDispatcherRule
import com.moneybook.testutil.inMemoryDb
import com.moneybook.ui.theme.MoneyBookTheme
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/** More → Accounts → Transfer-Expense setting (notes-settings.md D). */
@RunWith(AndroidJUnit4::class)
class TransferExpenseContentTest {
    @get:Rule val rule = createComposeRule()

    private val state = TransferExpenseState(
        listOf(com.moneybook.domain.AccountGroupItem("g1", "Savings", AccountGroupType.SAVINGS, 0, listOf(
            com.moneybook.domain.AccountItem("a1", "Rainy day fund", 0, "CZK"),
            com.moneybook.domain.AccountItem("a2", "Holiday pot", 0, "CZK"),
        ))),
        ticked = setOf("a2"),
    )

    @Test fun `shows the description, the group header and a check on ticked accounts only`() {
        rule.setContent { MoneyBookTheme(darkTheme = false) { TransferExpenseContent(state, {}, { _, _ -> }) } }
        rule.onNodeWithText("Transfer-Expense setting").assertExists()
        rule.onNodeWithText("Amount transferred from the regular account to the selected account is shown as an expense", substring = true).assertExists()
        rule.onNodeWithText("The transferred amount from the selected account to the regular account will be displayed as an income.", substring = true).assertExists()
        rule.onNodeWithText("Savings").assertExists()
        rule.onNodeWithText("Rainy day fund").assertExists()
        rule.onAllNodesWithContentDescription("Ticked").assertCountEquals(1)
    }

    @Test fun `a tap toggles at once`() {
        val calls = mutableListOf<Pair<String, Boolean>>()
        rule.setContent { MoneyBookTheme(darkTheme = false) { TransferExpenseContent(state, {}, { id, on -> calls += id to on }) } }
        rule.onNodeWithText("Rainy day fund").performClick()
        rule.onNodeWithText("Holiday pot").performClick()
        assertEquals(listOf("a1" to true, "a2" to false), calls)
    }

    @Test fun `no accounts says no data`() {
        rule.setContent { MoneyBookTheme(darkTheme = false) { TransferExpenseContent(TransferExpenseState(emptyList(), emptySet()), {}, { _, _ -> }) } }
        rule.onNodeWithText("No data available.").assertExists()
        rule.onNodeWithText("Amount transferred", substring = true).assertExists()
    }

    @Test fun `the accounts screen has the row between Deleted accounts and Card expenses`() {
        var opened = false
        rule.setContent {
            MoneyBookTheme(darkTheme = false) { AccountSettingsContent(null, {}, {}, {}, {}, {}, { opened = true }, {}) }
        }
        rule.onNodeWithText("Transfer-Expense setting").performClick()
        assertTrue(opened)
    }
}

@RunWith(AndroidJUnit4::class)
class TransferExpenseViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneOffset.UTC))

    @Test fun `lists eligible accounts under their group and toggles the flag`() = runTest {
        val db = inMemoryDb()
        Seeder(db, time).seedIfEmpty("CZK")
        val savings = DefaultData.groupId(AccountGroupType.SAVINGS)
        db.accountDao().upsert(AccountEntity("fund", savings, "Rainy day fund", "CZK", 0, sortOrder = 9, updatedAt = 0))
        val repo = AccountRepository(db, time)
        val vm = TransferExpenseViewModel(repo)
        val s = vm.state.first { it != null && it.groups.any { g -> g.accounts.any { a -> a.id == "fund" } } }!!
        assertEquals(listOf("Savings"), s.groups.filter { g -> g.accounts.any { it.id == "fund" } }.map { it.name })
        assertTrue(s.groups.none { it.type == AccountGroupType.CASH })
        assertTrue(s.ticked.isEmpty())
        vm.toggle("fund", true)
        assertEquals(setOf("fund"), vm.state.first { it != null && it.ticked.isNotEmpty() }!!.ticked)
        vm.toggle("fund", false)
        assertTrue(vm.state.first { it != null && it.ticked.isEmpty() }!!.ticked.isEmpty())
        db.close()
    }
}

@RunWith(AndroidJUnit4::class)
class TransferExpenseFlowTest {
    @get:Rule(order = 0) val rule = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<MoneyBookApp>()

    @get:Rule(order = 1) val opened = object : ExternalResource() {
        override fun before() {
            rule.waitUntil(10_000) { app.container.lock.state.value == LockState.OPEN }
            rule.waitUntil(10_000) { runBlocking { app.container.db.categoryDao().all().isNotEmpty() } }
            runBlocking {
                app.container.db.accountDao().upsert(
                    AccountEntity("fund", DefaultData.groupId(AccountGroupType.SAVINGS), "Rainy day fund", "CZK", 0, sortOrder = 9, updatedAt = 0),
                )
            }
            rule.waitForIdle()
        }
    }

    private fun waitFor(text: String) = rule.waitUntil(10_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test fun `More, Accounts, Transfer-Expense setting ticks and unticks an account`() {
        rule.onNodeWithText("More").performClick()
        rule.onAllNodesWithText("Accounts").onFirst().performClick()
        waitFor("Transfer-Expense setting")
        rule.onNodeWithText("Transfer-Expense setting").performClick()
        waitFor("Rainy day fund")
        rule.onNodeWithText("Rainy day fund").performClick()
        rule.waitUntil(10_000) { runBlocking { app.container.accounts.get("fund")!!.transferExpense } }
        rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("Ticked").fetchSemanticsNodes().isNotEmpty() } // the list caught up
        rule.onNodeWithText("Rainy day fund").performClick()
        rule.waitUntil(10_000) { runBlocking { !app.container.accounts.get("fund")!!.transferExpense } }
    }
}
