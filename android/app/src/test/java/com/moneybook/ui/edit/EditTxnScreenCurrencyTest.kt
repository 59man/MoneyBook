package com.moneybook.ui.edit

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.MainActivity
import com.moneybook.MoneyBookApp
import com.moneybook.shared.money.SymbolPosition
import com.moneybook.ui.common.money
import com.moneybook.LockState
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/** The entry form with a sub-currency: the keypad's strip, the main amount and rate line, and a transfer's Fees. */
@RunWith(AndroidJUnit4::class)
class EditTxnScreenCurrencyTest {
    @get:Rule(order = 0) val rule = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<MoneyBookApp>()

    @get:Rule(order = 1) val opened = object : ExternalResource() {
        override fun before() {
            rule.waitUntil(10_000) { app.container.lock.state.value == LockState.OPEN }
            rule.waitUntil(10_000) { runBlocking { app.container.db.categoryDao().all().isNotEmpty() } }
            runBlocking {
                app.container.currencies.add("EUR")
                app.container.currencies.update("EUR", BigDecimal("1.1"), SymbolPosition.START, 2)
            }
            rule.waitForIdle()
        }
    }

    private fun openForm() {
        rule.onNodeWithContentDescription("Add").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("€").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun aEuroAmountShowsItsMainAmountAndRate() {
        openForm()
        rule.onNodeWithText("€").performClick()
        rule.onNodeWithText("2").performClick()
        rule.onNodeWithText("0").performClick()
        rule.onNodeWithText("Exchange rate: 1.1").assertExists()
        val main = runBlocking { app.container.currencies.current().mainSpec }
        rule.onNodeWithText(money(2_200, main)).assertExists()
    }

    @Test fun theFeesButtonAddsTheFeesRow() {
        openForm()
        rule.onNodeWithText("Transfer").performClick()
        rule.onNodeWithText("Fees").performClick()
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("Fees").fetchSemanticsNodes().size >= 2) // the row and the keypad's title
        assertTrue(rule.onAllNodesWithContentDescription("Remove fees").fetchSemanticsNodes().isNotEmpty())
    }
}
