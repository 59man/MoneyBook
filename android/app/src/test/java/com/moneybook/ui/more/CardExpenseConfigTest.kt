package com.moneybook.ui.more

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.domain.CardExpenseMode
import com.moneybook.ui.theme.MoneyBookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CardExpenseConfigTest {
    @get:Rule val rule = createComposeRule()

    @Test fun `accounts settings show the current mode`() {
        var opened = false
        rule.setContent { MoneyBookTheme(darkTheme = false) { AccountSettingsContent(CardExpenseMode.AT_THE_TIME, {}, {}, {}, {}, {}, {}, { opened = true }) } }
        rule.onNodeWithText("A. At the time").assertExists()
        rule.onNodeWithText("Card expenses display config").performClick()
        assertEquals(true, opened)
    }

    @Test fun `tapping B picks lump sum`() {
        var picked: CardExpenseMode? = null
        rule.setContent { MoneyBookTheme(darkTheme = false) { CardExpenseConfigContent(onBack = {}) { picked = it } } }
        rule.onNodeWithText("lump sum on your card payment date", substring = true).assertExists()
        rule.onNodeWithText("B. Lump sum").performClick()
        assertEquals(CardExpenseMode.LUMP_SUM, picked)
        rule.onNodeWithText("A. At the time").performClick()
        assertEquals(CardExpenseMode.AT_THE_TIME, picked)
    }
}
