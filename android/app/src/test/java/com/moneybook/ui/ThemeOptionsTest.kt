package com.moneybook.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.db.TxnRow
import com.moneybook.shared.model.TxnType
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.ui.common.ListOptions
import com.moneybook.ui.common.LocalListOptions
import com.moneybook.ui.common.TxnRowItem
import com.moneybook.ui.theme.LightMbColors
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbColors
import com.moneybook.ui.theme.MoneyBookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeOptionsTest {
    @get:Rule val rule = createComposeRule()

    private val row = TxnRow(
        id = "a", type = TxnType.EXPENSE, dateTime = "2026-10-06T18:40:12.345", amount = 100, toAmount = null, amountMain = 100,
        currency = "CZK", fee = 0, note = "Oběd", description = "S kolegy", accountId = "cash", accountName = "Cash",
        toAccountId = null, toAccountName = null, categoryId = null, categoryName = "🍜 Food", parentCategoryName = null,
    )

    @Test fun setBSwapsIncomeAndExpenseColors() {
        var seen: MbColors? = null
        rule.setContent { MoneyBookTheme(darkTheme = false, swapIncomeExpense = true) { seen = Mb.colors } }
        rule.waitForIdle()
        assertEquals(LightMbColors.expense, seen!!.income)
        assertEquals(LightMbColors.income, seen!!.expense)
        assertEquals(LightMbColors.ringIncome, seen!!.ringExpense)
        assertEquals(LightMbColors.accent, seen!!.accent)
        assertEquals(LightMbColors.expenseFaded, seen!!.incomeFaded) // the calendar's other-month days swap too
        assertEquals(LightMbColors.incomeFaded, seen!!.expenseFaded)
        assertEquals(LightMbColors.danger, seen!!.danger) // delete and reset stay red
    }

    @Test fun rowsShowTimeAndDescriptionWhenAsked() {
        rule.setContent {
            MoneyBookTheme(darkTheme = false) {
                CompositionLocalProvider(LocalListOptions provides ListOptions(showTime = true, showDescription = true)) {
                    TxnRowItem(row, CurrencySpec.of("CZK")) {}
                }
            }
        }
        rule.onNodeWithText("18:40", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("S kolegy", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun rowsHideThemByDefault() {
        rule.setContent { MoneyBookTheme(darkTheme = false) { TxnRowItem(row, CurrencySpec.of("CZK")) {} } }
        rule.onNodeWithText("18:40", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("S kolegy", useUnmergedTree = true).assertDoesNotExist()
    }
}
