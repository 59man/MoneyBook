package com.moneybook.ui.stats

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.domain.CategorySlice
import com.moneybook.domain.StatsMode
import com.moneybook.domain.StatsPeriod
import com.moneybook.domain.Totals
import com.moneybook.shared.model.TxnType
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.ui.theme.MoneyBookTheme
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StatsScreenTest {
    @get:Rule val rule = createComposeRule()
    private val state = StatsUiState(
        period = StatsPeriod.Month(YearMonth.of(2026, 9)), type = TxnType.EXPENSE, currency = CurrencySpec.of("CZK"),
        totals = Totals(250_000, 40_000),
        slices = listOf(CategorySlice("food", "🍜 Food", 30_000, 40_000), CategorySlice("tr", "🚖 Transport", 10_000, 40_000)),
    )

    @Test fun showsTotalsAndCategoryRows() {
        rule.setContent { MoneyBookTheme(darkTheme = false) { StatsContent(state) } }
        listOf("Sep 2026", "Monthly", "Income", "2,500.00Kč", "Expenses", "400.00Kč", "75%", "🍜 Food", "300.00Kč", "25%", "🚖 Transport", "100.00Kč")
            .forEach { rule.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test fun rowsTabsAndModesReportTaps() {
        var opened: CategorySlice? = null
        var type: TxnType? = null
        var mode: StatsMode? = null
        rule.setContent {
            MoneyBookTheme(darkTheme = false) {
                StatsContent(state, StatsActions(onMode = { mode = it }, onType = { type = it }, onOpenSlice = { opened = it }))
            }
        }
        rule.onNodeWithText("🚖 Transport").performClick()
        assertEquals("tr", opened?.key)
        rule.onNodeWithText("Income").performClick()
        assertEquals(TxnType.INCOME, type)
        rule.onNodeWithText("Monthly").performClick()
        rule.onNodeWithText("Annually").performClick()
        assertEquals(StatsMode.ANNUALLY, mode)
    }

    @Test fun periodModeShowsDateChipsAndAnEmptyPeriodSaysSo() {
        val custom = state.copy(
            period = StatsPeriod.custom(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)), totals = Totals(), slices = emptyList(),
        )
        rule.setContent { MoneyBookTheme(darkTheme = false) { StatsContent(custom) } }
        listOf("1/1/26", "12/31/26", "Period", "No data available.").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test fun allTimeIsInTheModeMenuAndHasNoArrows() {
        var mode: StatsMode? = null
        rule.setContent {
            MoneyBookTheme(darkTheme = false) {
                StatsContent(state.copy(period = StatsPeriod.AllTime), StatsActions(onMode = { mode = it }))
            }
        }
        rule.onNodeWithText("All time").assertIsDisplayed() // the title
        rule.onNodeWithContentDescription("Previous").assertDoesNotExist()
        rule.onNodeWithText("All Time").performClick() // the mode button
        rule.onNodeWithText("Monthly").performClick()
        assertEquals(StatsMode.MONTHLY, mode)
    }
}
