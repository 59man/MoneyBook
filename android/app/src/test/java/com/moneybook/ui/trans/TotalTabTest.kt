package com.moneybook.ui.trans

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import com.moneybook.ui.common.money
import com.moneybook.domain.TOTAL_BUDGET
import com.moneybook.domain.BudgetLine
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.domain.DateRange
import com.moneybook.domain.TotalBreakdown
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.ui.theme.MoneyBookTheme
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TotalTabTest {
    @get:Rule val rule = createComposeRule()
    private val czk = CurrencySpec.of("CZK")
    private val september = DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1))

    @Test fun showsTheAccountsCardAndTheExportSlot() {
        rule.setContent {
            MoneyBookTheme(darkTheme = false) { TotalTab(TotalBreakdown(137, 72_600, 10_000, 7_000), september, czk, emptyList(), null, {}, {}) { Text("export slot") } }
        }
        listOf(
            "Accounts", "9.1.26  ~  9.30.26", "Compared Expenses (Last month)", "137%", "Expenses (Cash, Accounts)", "726.00Kč",
            "Expenses (Card)", "100.00Kč", "Transfer (Cash, Accounts→ )", "70.00Kč", "export slot",
        ).forEach { rule.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test fun noExpensesLastMonthShowsADash() {
        rule.setContent { MoneyBookTheme(darkTheme = false) { TotalTab(TotalBreakdown(null, 0, 0, 0), september, czk, emptyList(), null, {}, {}) {} } }
        rule.onNodeWithText("-").assertIsDisplayed()
    }

    private val usd = CurrencySpec.of("USD")

    @Test fun budgetRowsShowRateAmountsAndExcess() {
        val lines = listOf(
            BudgetLine(null, TOTAL_BUDGET, 30000, 21400),
            BudgetLine("food", "🍜 Food", 5000, 0),
            BudgetLine("other", "Other", 10000, 21400),
        )
        rule.setContent { MoneyBookTheme(darkTheme = false) { TotalTab(null, null, usd, lines, 8f / 31f, {}, {}) {} } }
        rule.onNodeWithText("Budget Setting").assertExists()
        rule.onNodeWithText("Today").assertExists()
        listOf("71%", "214%", "0%", "Excess " + money(-11400, usd), money(8600, usd)).forEach {
            rule.onNodeWithText(it, useUnmergedTree = true).assertExists()
        }
    }

    @Test fun noBudgetsShowsOnlyTheTitleRow() {
        rule.setContent { MoneyBookTheme(darkTheme = false) { TotalTab(null, null, usd, emptyList(), 0.5f, {}, {}) {} } }
        rule.onNodeWithText("Budget Setting").assertExists()
        rule.onNodeWithText("Today").assertDoesNotExist()
        rule.onNodeWithText(TOTAL_BUDGET).assertDoesNotExist()
    }

    @Test fun tappingARowReportsItsLineAndTheButtonOpensSettings() {
        var tapped: BudgetLine? = null
        var setting = false
        val line = BudgetLine("food", "🍜 Food", 5000, 0)
        rule.setContent {
            MoneyBookTheme(darkTheme = false) {
                TotalTab(null, null, usd, listOf(BudgetLine(null, TOTAL_BUDGET, 5000, 0, true), line), null, { setting = true }, { tapped = it }) {}
            }
        }
        rule.onNodeWithText("🍜 Food", useUnmergedTree = true).performClick()
        rule.onNodeWithText("Budget Setting", useUnmergedTree = true).performClick()
        org.junit.Assert.assertEquals(line, tapped)
        org.junit.Assert.assertEquals(true, setting)
    }
}
