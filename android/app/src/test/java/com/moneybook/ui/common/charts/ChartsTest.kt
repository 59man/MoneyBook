package com.moneybook.ui.common.charts

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.domain.Totals
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.ui.theme.MoneyBookTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChartsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun lineChartLabelsEveryPointAndItsValue() {
        rule.setContent {
            MoneyBookTheme(darkTheme = false) {
                LineChart(
                    listOf(80_000, 120_000, 100_000), listOf("Jul", "Aug", "Sep"), decimals = 2, maxLines = 3,
                    valueTexts = listOf("800.00", "1,200.00", "1,000.00"),
                )
            }
        }
        listOf("Jul", "Aug", "Sep", "800.00", "1,200.00", "1,000.00").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        rule.onNodeWithContentDescription("Line chart").assertIsDisplayed()
    }

    @Test fun barChartListsIncomeAndExpensePerMonth() {
        rule.setContent {
            MoneyBookTheme(darkTheme = false) { BarChart(listOf(Totals(50_000, 20_000), Totals()), listOf("Aug", "Sep"), CurrencySpec.of("CZK")) }
        }
        listOf("Aug", "Sep", "500.00", "200.00").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        rule.onAllNodesWithText("0.00").assertCountEquals(2)
        rule.onNodeWithContentDescription("Bar chart").assertIsDisplayed()
    }

    @Test fun pieChartDraws() {
        rule.setContent { MoneyBookTheme(darkTheme = false) { PieChart(listOf(PieEntry("🍜 Food", "100.0 %", 1.0, Color.Red))) } }
        rule.onNodeWithContentDescription("Pie chart").assertIsDisplayed()
    }
}
