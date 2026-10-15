package com.moneybook.ui.trans

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.domain.withCarryOver
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.ui.theme.MoneyBookTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Carry-over row in Daily: "Carry-over" in the category column, no note or account, and it opens nothing. */
@RunWith(AndroidJUnit4::class)
class CarryOverRowTest {
    @get:Rule val rule = createComposeRule()
    private val czk = CurrencySpec.of("CZK")
    private val day = LocalDate.of(2026, 10, 1)

    private fun show(value: Long, opened: MutableList<String> = mutableListOf()) = rule.setContent {
        MoneyBookTheme(darkTheme = false) { DailyTab(withCarryOver(emptyList(), day, value, "CZK", appendLast = true), czk, { opened += it }) }
    }

    @Test fun `the row says Carry-over and cannot be opened`() {
        val opened = mutableListOf<String>()
        show(33_400, opened)
        rule.onNodeWithText("Carry-over").assertExists().performClick()
        assertEquals(emptyList<String>(), opened)
        rule.onAllNodesWithText("334.00", substring = true, useUnmergedTree = true).assertCountEquals(2) // day header + row
    }

    @Test fun `a zero value prints no amount`() {
        show(0)
        rule.onNodeWithText("Carry-over").assertExists()
        rule.onAllNodesWithText("0.00", substring = true, useUnmergedTree = true).assertCountEquals(2) // header income and expense only
    }
}
