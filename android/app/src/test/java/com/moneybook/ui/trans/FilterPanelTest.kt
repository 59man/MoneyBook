package com.moneybook.ui.trans

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.domain.FilterAccount
import com.moneybook.domain.FilterCategory
import com.moneybook.domain.FilterGroup
import com.moneybook.domain.FilterOptions
import com.moneybook.domain.Totals
import com.moneybook.domain.TxnFilter
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.ui.theme.MoneyBookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FilterPanelTest {
    @get:Rule val rule = createComposeRule()
    private val options = FilterOptions(
        income = listOf(FilterCategory("salary", "💰 Salary", 250_000)),
        expense = listOf(FilterCategory("food", "🍜 Food", 30_000), FilterCategory("pets", "🐶 Pets", 0)),
        groups = listOf(FilterGroup("Cash", listOf(FilterAccount("wallet", "Wallet", 250_000, 30_000)))),
        month = Totals(250_000, 40_000),
        selected = Totals(0, 10_000),
    )

    @Test fun showsTheRingsAndEachList() {
        var toggled: String? = null
        var all: Pair<FilterTab, Boolean>? = null
        var closed = false
        rule.setContent {
            MoneyBookTheme(darkTheme = false) {
                TransFilterPanel(
                    "Sep 2026", options, TxnFilter(categories = setOf("food")), CurrencySpec.of("CZK"),
                    FilterActions(onClose = { closed = true }, onToggleCategory = { toggled = it }, onAll = { t, on -> all = t to on }),
                )
            }
        }
        listOf("Sep 2026", "Select items that you want to filter.", "0%", "25%", "400.00Kč", "Total", "2,100.00Kč", "💰 Salary")
            .forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        rule.onNodeWithText("EXPENSES").performClick()
        rule.onNodeWithText("This month").assertIsDisplayed()
        rule.onNodeWithText("300.00Kč").assertIsDisplayed()
        rule.onNodeWithText("🐶 Pets").performClick()
        assertEquals("pets", toggled)
        rule.onNodeWithText("All").performClick()
        assertEquals(FilterTab.EXPENSES to true, all)
        rule.onNodeWithText("ACCOUNT").performClick()
        listOf("Cash", "Wallet", "Transfer-In", "Transfer-Out").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        rule.onNodeWithContentDescription("Close filter").performClick()
        assertEquals(true, closed)
    }

    @Test fun tapsOnThePanelDoNotReachTheListBehindIt() {
        var behind = false
        rule.setContent {
            MoneyBookTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().clickable { behind = true })
                    TransFilterPanel("Sep 2026", options, TxnFilter(), CurrencySpec.of("CZK"))
                }
            }
        }
        rule.onNodeWithText("Total").performClick()
        rule.onNodeWithText("Select items that you want to filter.").performClick()
        assertEquals(false, behind)
    }
}
