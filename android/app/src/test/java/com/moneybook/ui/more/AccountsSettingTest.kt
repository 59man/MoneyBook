package com.moneybook.ui.more

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.LockState
import com.moneybook.MainActivity
import com.moneybook.MoneyBookApp
import com.moneybook.data.DefaultData
import com.moneybook.data.db.AccountEntity
import com.moneybook.domain.AccountGroupItem
import com.moneybook.domain.AccountItem
import com.moneybook.domain.AccountsOverview
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.ArchiveState
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.ui.accounts.AccountsActions
import com.moneybook.ui.accounts.AccountsUiState
import com.moneybook.ui.theme.MoneyBookTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/** More → Accounts → Accounts Setting, as measured on Realbyte (notes-settings.md B). */
@RunWith(AndroidJUnit4::class)
class AccountsSettingContentTest {
    @get:Rule val rule = createComposeRule()

    private val cash = AccountItem("cash", "Cash wallet", 1_000, "CZK")
    private val bank = AccountItem("bank", "Old bank", 0, "CZK", hidden = true)
    private val state = AccountsUiState(
        CurrencySpec.of("CZK"),
        AccountsOverview(1_000, 0, emptyList()),
        AccountsOverview(1_000, 0, listOf(
            AccountGroupItem("g1", "Cash", AccountGroupType.CASH, 1_000, listOf(cash)),
            AccountGroupItem("g2", "Accounts", AccountGroupType.ACCOUNTS, 0, listOf(bank)),
        )),
    )

    private fun show(onOpen: (String) -> Unit = {}, onAdd: () -> Unit = {}, actions: AccountsActions = AccountsActions()) =
        rule.setContent { MoneyBookTheme(darkTheme = false) { AccountsSettingContent(state, {}, onOpen, onAdd, actions) } }

    @Test fun `lists the groups and every account including hidden ones, and a tap opens the account`() {
        var opened: String? = null
        show(onOpen = { opened = it })
        rule.onNodeWithText("Accounts Setting").assertExists()
        rule.onNodeWithText("Cash").assertExists() // group header
        rule.onNodeWithText("Accounts").assertExists() // group header
        rule.onNodeWithText("Cash wallet").assertExists()
        rule.onNodeWithText("Old bank").performClick() // hidden rows are listed (dimmed) and open too
        assertEquals("bank", opened)
    }

    @Test fun `the plus adds`() {
        var added = false
        show(onAdd = { added = true })
        rule.onNodeWithContentDescription("Add").performClick()
        assertTrue(added)
    }

    @Test fun `the menu has the three modes in Realbyte's order`() {
        show()
        rule.onNodeWithContentDescription("Menu").performClick()
        val items = listOf("Modify Orders", "Show/Hide", "Delete").map { rule.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }
        assertEquals(items.sorted(), items)
        rule.onNodeWithText("Add").assertDoesNotExist()
    }

    @Test fun `delete mode - the minus arms the row and the trash box deletes at once`() {
        var deleted: String? = null
        show(actions = AccountsActions(onDelete = { deleted = it }))
        rule.onNodeWithContentDescription("Menu").performClick()
        rule.onNodeWithText("Delete").performClick()
        rule.onNodeWithText("Delete").assertExists() // the mode's title
        rule.onNodeWithContentDescription("Trash Cash wallet").assertDoesNotExist()
        rule.onNodeWithContentDescription("Delete Cash wallet").performClick()
        assertEquals(null, deleted)
        rule.onNodeWithContentDescription("Trash Cash wallet").performClick()
        assertEquals("cash", deleted)
    }

    @Test fun `show-hide toggles at once`() {
        var hidden: Pair<String, Boolean>? = null
        show(actions = AccountsActions(onSetHidden = { id, h -> hidden = id to h }))
        rule.onNodeWithContentDescription("Menu").performClick()
        rule.onNodeWithText("Show/Hide").performClick()
        rule.onNodeWithContentDescription("Show Old bank").performClick()
        assertEquals("bank" to false, hidden)
    }
}

@RunWith(AndroidJUnit4::class)
class AccountsSettingFlowTest {
    @get:Rule(order = 0) val rule = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<MoneyBookApp>()

    @get:Rule(order = 1) val opened = object : ExternalResource() {
        override fun before() {
            rule.waitUntil(10_000) { app.container.lock.state.value == LockState.OPEN }
            rule.waitUntil(10_000) { runBlocking { app.container.db.categoryDao().all().isNotEmpty() } }
            runBlocking {
                app.container.db.accountDao().upsert(
                    AccountEntity("savings", DefaultData.groupId(AccountGroupType.ACCOUNTS), "Rainy day fund", "CZK", 0, sortOrder = 9, updatedAt = 0),
                )
            }
            rule.waitForIdle()
        }
    }

    private fun waitFor(text: String) = rule.waitUntil(10_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun openAccountsSetting() {
        rule.onNodeWithText("More").performClick()
        rule.onAllNodesWithText("Accounts").onFirst().performClick()
        waitFor("Accounts Setting")
        rule.onNodeWithText("Accounts Setting").performClick()
        waitFor("Rainy day fund")
    }

    @Test fun `More - Accounts lists its rows in order`() {
        rule.onNodeWithText("More").performClick()
        rule.onAllNodesWithText("Accounts").onFirst().performClick()
        waitFor("Card expenses display config")
        val tops = listOf("Account Group", "Accounts Setting", "Deleted account group", "Deleted accounts", "Card expenses display config")
            .map { rule.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }
        assertEquals(tops.sorted(), tops)
    }

    @Test fun `Account Info's trash asks first, and Yes deletes and returns to Accounts Setting`() {
        openAccountsSetting()
        rule.onNodeWithText("Rainy day fund").performClick()
        waitFor("Account Info")
        rule.onNodeWithContentDescription("Delete").performClick()
        rule.onNodeWithText("Are you sure you want to delete?").assertExists()
        rule.onNodeWithText("No").performClick()
        rule.onNodeWithText("Are you sure you want to delete?").assertDoesNotExist()
        rule.onNodeWithContentDescription("Delete").performClick()
        rule.onNodeWithText("Yes").performClick()
        waitFor("Accounts Setting")
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Rainy day fund").fetchSemanticsNodes().isEmpty() }
        assertEquals(ArchiveState.DELETED, runBlocking { app.container.accounts.get("savings")!!.state })
    }

    @Test fun `delete mode - minus then trash removes the row and the account moves to the deleted state`() {
        openAccountsSetting()
        rule.onNodeWithContentDescription("Menu").performClick()
        rule.onNodeWithText("Delete").performClick()
        rule.onNodeWithContentDescription("Delete Rainy day fund").performClick()
        rule.onNodeWithContentDescription("Trash Rainy day fund").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Rainy day fund").fetchSemanticsNodes().isEmpty() }
        assertEquals(ArchiveState.DELETED, runBlocking { app.container.accounts.get("savings")!!.state })
    }
}
