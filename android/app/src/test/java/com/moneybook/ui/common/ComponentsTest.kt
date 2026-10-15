package com.moneybook.ui.common

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.db.TxnRow
import com.moneybook.domain.DayGroup
import com.moneybook.domain.Totals
import com.moneybook.shared.model.TxnType
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.ui.theme.MoneyBookTheme
import java.time.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComponentsTest {
    @get:Rule val rule = createComposeRule()
    private val czk = CurrencySpec.of("CZK")

    private fun row(type: TxnType, note: String, category: String?, to: String? = null) = TxnRow(
        id = "r", type = type, dateTime = "2026-09-26T18:40:12.345", amount = 52_500, toAmount = null, amountMain = 52_500,
        currency = "CZK", fee = 0, note = note, description = "", accountId = "cash", accountName = "Cash",
        toAccountId = to, toAccountName = to, categoryId = null, categoryName = category, parentCategoryName = null,
    )

    @Test fun dayHeaderShowsRealbyteFields() {
        rule.setContent {
            MoneyBookTheme(darkTheme = false) { DayHeader(DayGroup(LocalDate.of(2026, 9, 26), Totals(0, 52_500), emptyList()), czk) }
        }
        listOf("26", "Sat", "09.2026", "0.00Kč", "525.00Kč").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test fun txnRowShowsCategoryNoteAccountAmountAndClicks() {
        var clicked = false
        rule.setContent {
            MoneyBookTheme(darkTheme = false) { TxnRowItem(row(TxnType.EXPENSE, "Zelenina z trhu", "🍜 Food"), czk) { clicked = true } }
        }
        listOf("🍜 Food", "Zelenina z trhu", "Cash", "525.00Kč").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        rule.onNodeWithText("Zelenina z trhu").performClick()
        assertTrue(clicked)
    }

    @Test fun txnRowShowsThePhotoIconOnlyWithAPhoto() {
        rule.setContent {
            MoneyBookTheme(darkTheme = false) {
                TxnRowItem(row(TxnType.EXPENSE, "a", "Food").copy(id = "p", hasPhoto = true), czk) {}
                TxnRowItem(row(TxnType.EXPENSE, "b", "Food"), czk) {}
            }
        }
        rule.onAllNodesWithContentDescription("Photo", useUnmergedTree = true).assertCountEquals(1)
    }

    private fun subRow() = row(TxnType.EXPENSE, "Polévka", "Lunch").copy(parentCategoryName = "🍜 Food")

    @Test fun subcategoryOnShowsTheParentAboveTheSub() {
        rule.setContent {
            MoneyBookTheme(darkTheme = false) {
                CompositionLocalProvider(LocalListOptions provides ListOptions(subcategory = true)) { TxnRowItem(subRow(), czk) {} }
            }
        }
        rule.onNodeWithText("🍜 Food").assertIsDisplayed()
        rule.onNodeWithText("Lunch").assertIsDisplayed()
    }

    @Test fun subcategoryOffShowsTheParentOnly() {
        rule.setContent { MoneyBookTheme(darkTheme = false) { TxnRowItem(subRow(), czk) {} } }
        rule.onNodeWithText("🍜 Food").assertIsDisplayed()
        rule.onNodeWithText("Lunch").assertDoesNotExist()
    }

    @Test fun transferRowShowsBothAccounts() {
        rule.setContent {
            MoneyBookTheme(darkTheme = false) { TxnRowItem(row(TxnType.TRANSFER, "", null, to = "Bank"), czk) {} }
        }
        rule.onNodeWithText("Transfer").assertIsDisplayed()
        rule.onNodeWithText("Cash → Bank").assertIsDisplayed()
    }

    @Test fun summaryBarShowsThreeColumns() {
        rule.setContent {
            MoneyBookTheme(darkTheme = false) { SummaryBar(incomeExpenseTotal(Totals(5_230_000, 2_915_000), czk)) }
        }
        listOf("Income", "Expenses", "Total", "52,300.00", "29,150.00", "23,150.00").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
    }
}
