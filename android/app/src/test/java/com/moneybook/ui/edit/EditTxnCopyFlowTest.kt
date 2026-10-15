package com.moneybook.ui.edit

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.LockState
import com.moneybook.MainActivity
import com.moneybook.MoneyBookApp
import com.moneybook.data.CopyDateBy
import com.moneybook.data.DefaultData
import com.moneybook.data.db.DbTime
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.CategoryType
import com.moneybook.shared.model.TxnType
import com.moneybook.testutil.Fixtures
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Trans → an entry → Copy → OK opens the copy form (the Edit screen is gone); Save adds a second entry. */
@RunWith(AndroidJUnit4::class)
class EditTxnCopyFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<MoneyBookApp>()

    @Test fun copyOpensTheCopyFormAndSaveAddsAnEntry() {
        rule.waitUntil(10_000) { app.container.lock.state.value == LockState.OPEN }
        rule.waitUntil(10_000) { runBlocking { app.container.db.categoryDao().all().isNotEmpty() } }
        val dao = app.container.db.txnDao()
        runBlocking {
            dao.upsert(
                Fixtures.txn(
                    "src", TxnType.EXPENSE, DbTime.format(LocalDate.now().atTime(8, 30)), DefaultData.accountId(AccountGroupType.CASH), 18_600,
                    categoryId = DefaultData.categoryId(CategoryType.EXPENSE, 0), note = "Zebra lunch",
                ).copy(currency = "USD"), // the app's main currency in this test
            )
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Zebra lunch").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Zebra lunch").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Copy").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Copy").performClick()
        rule.onNodeWithText("Copy with date of entry").performClick()
        rule.onNodeWithText("OK").performClick()
        // the copy form has loaded once it shows the entry's note (the Edit screen has no Save button)
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Save").fetchSemanticsNodes().isNotEmpty() && rule.onAllNodesWithText("Zebra lunch").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(CopyDateBy.ENTRY_DATE, runBlocking { app.container.settings.current().copyDateBy })
        // the Edit screen is gone: the copy form (a new entry) has Save and Continue, no Copy button
        rule.onNodeWithText("Continue").assertExists()
        assertEquals(0, rule.onAllNodesWithText("Copy").fetchSemanticsNodes().size)
        rule.onNodeWithText("Save").performClick()
        // Save closes the form (polling the database from here would block the main thread the save runs on)
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Save").fetchSemanticsNodes().isEmpty() }
        assertEquals(2, runBlocking { dao.count() })
        assertEquals(1, runBlocking { dao.allRows().count { it.id == "src" } })
        // restore the shared DataStore for later launch tests
        runBlocking { app.container.settings.replace(app.container.settings.current().copy(copyDateBy = CopyDateBy.TODAY)) }
    }
}
