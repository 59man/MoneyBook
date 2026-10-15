package com.moneybook.ui.more

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.LockState
import com.moneybook.MainActivity
import com.moneybook.MoneyBookApp
import com.moneybook.data.DefaultData
import com.moneybook.data.db.AccountEntity
import com.moneybook.data.db.AccountGroupEntity
import com.moneybook.data.db.DeletedGroupRow
import com.moneybook.domain.AccountGroupItem
import com.moneybook.domain.AccountItem
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.ArchiveState
import com.moneybook.ui.accounts.AccountDetailContent
import com.moneybook.ui.accounts.AccountDetailState
import com.moneybook.ui.theme.MoneyBookTheme
import java.time.YearMonth
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/** More → Accounts → Deleted accounts / Deleted account group and the Account Group list (notes-settings.md B, C). */
@RunWith(AndroidJUnit4::class)
class DeletedScreensContentTest {
    @get:Rule val rule = createComposeRule()

    private val groups = listOf(
        AccountGroupItem("g1", "Savings", AccountGroupType.SAVINGS, 0, listOf(AccountItem("a1", "Rainy day fund", 0, "CZK"))),
    )

    @Test fun `deleted accounts - a row opens the detail and the minus then the trash purges`() {
        var opened: String? = null
        var purged: String? = null
        rule.setContent { MoneyBookTheme(darkTheme = false) { DeletedAccountsContent(groups, {}, { opened = it }, { purged = it }) } }
        rule.onNodeWithText("Deleted accounts").assertExists()
        rule.onNodeWithText("Savings").assertExists() // group header
        rule.onNodeWithText("Rainy day fund").performClick()
        assertEquals("a1", opened)
        rule.onNodeWithContentDescription("Trash Rainy day fund").assertDoesNotExist()
        rule.onNodeWithContentDescription("Delete Rainy day fund").performClick()
        assertEquals(null, purged) // the minus only arms
        rule.onNodeWithContentDescription("Trash Rainy day fund").performClick()
        assertEquals("a1", purged)
    }

    @Test fun `deleted accounts - empty list says no data`() {
        rule.setContent { MoneyBookTheme(darkTheme = false) { DeletedAccountsContent(emptyList(), {}, {}, {}) } }
        rule.onNodeWithText("No data available.").assertExists()
    }

    private val deletedGroups = listOf(
        DeletedGroupRow("g1", "Trips", AccountGroupType.OTHERS, 0, accountCount = 2),
        DeletedGroupRow("g2", "Old", AccountGroupType.OTHERS, 1, accountCount = 0),
    )

    @Test fun `deleted groups - count suffix only when accounts remain, a row asks and YES restores`() {
        var restored: String? = null
        rule.setContent { MoneyBookTheme(darkTheme = false) { DeletedGroupsContent(deletedGroups, {}, { restored = it }, {}) } }
        rule.onNodeWithText("Trips (2)").assertExists()
        rule.onNodeWithText("Old").assertExists()
        rule.onAllNodesWithText("Restore").assertCountEquals(2)
        rule.onNodeWithText("Trips (2)").performClick()
        rule.onNodeWithText("Do you want to restore?").assertExists()
        rule.onNodeWithText("NO").performClick()
        assertEquals(null, restored)
        rule.onNodeWithText("Trips (2)").performClick()
        rule.onNodeWithText("YES").performClick()
        assertEquals("g1", restored)
    }

    @Test fun `deleted groups - minus then trash purges and empty list says no data`() {
        var purged: String? = null
        rule.setContent { MoneyBookTheme(darkTheme = false) { DeletedGroupsContent(deletedGroups, {}, {}, { purged = it }) } }
        rule.onNodeWithContentDescription("Delete Old").performClick()
        rule.onNodeWithContentDescription("Trash Old").performClick()
        assertEquals("g2", purged)
    }

    @Test fun `deleted groups - empty`() {
        rule.setContent { MoneyBookTheme(darkTheme = false) { DeletedGroupsContent(emptyList(), {}, {}, {}) } }
        rule.onNodeWithText("No data available.").assertExists()
    }

    @Test fun `account group list - the four fixed groups have no minus, the others do, the trash deletes, plus adds`() {
        val list = listOf(
            AccountGroupType.CASH, AccountGroupType.ACCOUNTS, AccountGroupType.CARD, AccountGroupType.DEBIT_CARD, AccountGroupType.SAVINGS,
        ).mapIndexed { i, t -> AccountGroupEntity(DefaultData.groupId(t), t.name.lowercase().replaceFirstChar { it.uppercase() }, t, i, 0) }
        var deleted: String? = null
        var added = false
        rule.setContent { MoneyBookTheme(darkTheme = false) { AccountGroupContent(list, {}, {}, { added = true }, {}, { deleted = it }) } }
        listOf("Cash", "Accounts", "Card", "Debit_card").forEach { rule.onNodeWithContentDescription("Delete $it").assertDoesNotExist() }
        rule.onNodeWithContentDescription("Delete Savings").performClick()
        rule.onNodeWithContentDescription("Trash Savings").performClick()
        assertEquals(DefaultData.groupId(AccountGroupType.SAVINGS), deleted)
        rule.onNodeWithContentDescription("Add").performClick()
        assertTrue(added)
    }

    @Test fun `read-only detail has Restore but no add, edit or pay`() {
        var restore = false
        val s = AccountDetailState(name = "Old bank", month = YearMonth.of(2026, 10))
        rule.setContent { MoneyBookTheme(darkTheme = false) { AccountDetailContent(s, {}, {}, null, null, null, onRestore = { restore = true }) } }
        rule.onNodeWithContentDescription("Add").assertDoesNotExist()
        rule.onNodeWithContentDescription("Edit").assertDoesNotExist()
        rule.onNodeWithContentDescription("Pay").assertDoesNotExist()
        rule.onNodeWithText("Restore").performClick()
        assertTrue(restore)
    }
}

@RunWith(AndroidJUnit4::class)
class DeletedFlowTest {
    @get:Rule(order = 0) val rule = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<MoneyBookApp>()
    private val savingsGroup = DefaultData.groupId(AccountGroupType.SAVINGS)

    @get:Rule(order = 1) val opened = object : ExternalResource() {
        override fun before() {
            rule.waitUntil(10_000) { app.container.lock.state.value == LockState.OPEN }
            rule.waitUntil(10_000) { runBlocking { app.container.db.categoryDao().all().isNotEmpty() } }
            runBlocking {
                app.container.db.accountDao().upsert(
                    AccountEntity("fund", savingsGroup, "Rainy day fund", "CZK", 500, sortOrder = 9, updatedAt = 0, state = ArchiveState.DELETED),
                )
            }
            rule.waitForIdle()
        }
    }

    private fun waitFor(text: String) = rule.waitUntil(10_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun openMoreAccounts() {
        rule.onNodeWithText("More").performClick()
        rule.onAllNodesWithText("Accounts").onFirst().performClick()
        waitFor("Deleted accounts")
    }

    @Test fun `restoring a deleted account asks, confirms, goes back and the account is normal again`() {
        openMoreAccounts()
        rule.onNodeWithText("Deleted accounts").performClick()
        waitFor("Rainy day fund")
        rule.onNodeWithText("Rainy day fund").performClick()
        waitFor("Statement")
        rule.onNodeWithContentDescription("Add").assertDoesNotExist()
        rule.onNodeWithContentDescription("Edit").assertDoesNotExist()
        rule.onNodeWithText("Restore").performClick()
        rule.onNodeWithText("Do you want to restore?").assertExists()
        rule.onNodeWithText("YES").performClick()
        waitFor("Restore completed.")
        rule.onNodeWithText("OK").performClick()
        waitFor("No data available.")
        assertEquals(ArchiveState.ACTIVE, runBlocking { app.container.accounts.get("fund")!!.state })
    }

    @Test fun `purging from the list leaves the entries and marks the account purged`() {
        openMoreAccounts()
        rule.onNodeWithText("Deleted accounts").performClick()
        waitFor("Rainy day fund")
        rule.onNodeWithContentDescription("Delete Rainy day fund").performClick()
        rule.onNodeWithContentDescription("Trash Rainy day fund").performClick()
        waitFor("No data available.")
        assertEquals(ArchiveState.PURGED, runBlocking { app.container.accounts.get("fund")!!.state })
    }

    @Test fun `a new group is added with the chosen type, and deleting a group lists it in Deleted account group with its count`() {
        openMoreAccounts()
        rule.onNodeWithText("Account Group").performClick()
        waitFor("Account Group")
        rule.onNodeWithContentDescription("Add").performClick()
        rule.onNodeWithText("Account group for credit cards").performClick()
        rule.onNodeWithContentDescription("Name").performTextInput("Plastic")
        rule.onNodeWithText("Save").performClick()
        waitFor("Plastic")
        val added = runBlocking { app.container.accounts.groups().single { it.name == "Plastic" } }
        assertEquals(AccountGroupType.CARD, added.type)
        // delete the Savings group: it holds no active account here, so give it one first
        runBlocking {
            app.container.db.accountDao().upsert(
                AccountEntity("live", savingsGroup, "Live savings", "CZK", 0, sortOrder = 8, updatedAt = 0),
            )
        }
        rule.onNodeWithContentDescription("Delete Savings").performClick()
        rule.onNodeWithContentDescription("Trash Savings").performClick()
        rule.waitUntil(10_000) { runBlocking { app.container.accounts.groups().none { it.id == savingsGroup } } }
        assertEquals(1, runBlocking { app.container.accounts.observeDeletedGroups().first().single().accountCount })
    }
}
