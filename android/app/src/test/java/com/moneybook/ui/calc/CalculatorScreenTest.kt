package com.moneybook.ui.calc

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.domain.Calculator
import com.moneybook.ui.edit.AmountKeypad
import com.moneybook.ui.theme.MoneyBookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalculatorScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun aSumAndDoneGiveTheAmount() {
        var done: Long? = null
        var closed = false
        rule.setContent {
            MoneyBookTheme(darkTheme = false) { CalculatorScreen(Calculator(), 2, onDone = { done = it }, onClose = { closed = true }) }
        }
        rule.onNodeWithText("1").performClick()
        rule.onNodeWithText("2").performClick()
        rule.onNodeWithText("+").performClick()
        rule.onNodeWithText("3").performClick()
        rule.onNodeWithText("12+3").assertIsDisplayed()
        rule.onNodeWithText("15").assertIsDisplayed()
        rule.onNodeWithText("DONE").performClick()
        assertEquals(1_500L, done)
        rule.onNodeWithContentDescription("Close").performClick()
        assertEquals(true, closed)
    }

    @Test fun theKeypadShowsTheCalcKeyOnlyWhenItCanOpenTheCalculator() {
        var opened = false
        rule.setContent { MoneyBookTheme(darkTheme = false) { AmountKeypad({}, {}, {}, onCalc = { opened = true }) } }
        rule.onNodeWithContentDescription("Calculator").performClick()
        assertEquals(true, opened)
    }

    @Test fun theAccountFormsKeypadHasNoCalcKey() {
        rule.setContent { MoneyBookTheme(darkTheme = false) { AmountKeypad({}, {}, {}) } }
        rule.onNodeWithContentDescription("Calculator").assertDoesNotExist()
    }
}
