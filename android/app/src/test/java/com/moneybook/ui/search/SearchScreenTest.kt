package com.moneybook.ui.search

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.db.TxnRow
import com.moneybook.domain.SearchFilter
import com.moneybook.ui.common.ListOptions
import com.moneybook.ui.common.LocalListOptions
import com.moneybook.domain.SearchTotals
import com.moneybook.shared.model.TxnType
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.ui.theme.MoneyBookTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchScreenTest {
    @get:Rule val rule = createComposeRule()
    private val row = TxnRow(
        id = "a", type = TxnType.EXPENSE, dateTime = "2026-09-26T12:00:00.000", amount = 49_100, toAmount = null, amountMain = 49_100,
        currency = "CZK", fee = 0, note = "Nákup jídlo", description = "", accountId = "cash", accountName = "Cash",
        toAccountId = null, toAccountName = null, categoryId = "food", categoryName = "🍜 Food", parentCategoryName = null,
    )
    private val state = SearchUiState(
        today = LocalDate.of(2026, 9, 26), filter = SearchFilter(text = "jídlo"), results = listOf(row),
        totals = SearchTotals(expense = 49_100), currency = CurrencySpec.of("CZK"),
    )

    @Test fun resultsShowTheSummaryAndRows() {
        var opened: String? = null
        var typed: String? = null
        rule.setContent {
            MoneyBookTheme(darkTheme = false) {
                SearchContent(state, SearchActions(onOpenTxn = { opened = it }, onText = { typed = it }), autoFocus = false)
            }
        }
        listOf("Search", "Income", "Expenses", "Transfer", "2026-09-26", "🍜 Food", "Nákup jídlo", "Cash")
            .forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        rule.onAllNodesWithText("491.00Kč").assertCountEquals(2) // the Expenses total and the row
        rule.onNodeWithText("Nákup jídlo").performClick()
        assertEquals("a", opened)
        rule.onNodeWithContentDescription("Search text").performTextInput("x")
        assertTrue(typed.orEmpty().contains("x"))
    }

    @Test fun aSubcategoryEntryReadsParentSlashSubOnlyWithSubcategoryOn() {
        val sub = state.copy(results = listOf(row.copy(categoryName = "Lunch", parentCategoryName = "🍜 Food")))
        rule.setContent {
            MoneyBookTheme(darkTheme = false) {
                CompositionLocalProvider(LocalListOptions provides ListOptions(subcategory = true)) { SearchContent(sub, SearchActions(), autoFocus = false) }
            }
        }
        rule.onNodeWithText("🍜 Food/Lunch").assertIsDisplayed()
    }

    @Test fun aSubcategoryEntryReadsAsItsParentWithSubcategoryOff() {
        val sub = state.copy(results = listOf(row.copy(categoryName = "Lunch", parentCategoryName = "🍜 Food")))
        rule.setContent { MoneyBookTheme(darkTheme = false) { SearchContent(sub, SearchActions(), autoFocus = false) } }
        rule.onNodeWithText("🍜 Food").assertIsDisplayed()
    }

    @Test fun theFilterIconShowsThePanelRows() {
        var toggled = false
        rule.setContent {
            MoneyBookTheme(darkTheme = false) {
                SearchContent(state.copy(panelOpen = true), SearchActions(onTogglePanel = { toggled = true }), autoFocus = false)
            }
        }
        listOf("Period", "Account", "Category", "Amount", "Min.", "Max.").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        rule.onNodeWithContentDescription("Filter").performClick()
        assertEquals(true, toggled)
    }
}
