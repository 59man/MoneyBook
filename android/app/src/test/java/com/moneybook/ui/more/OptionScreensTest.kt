package com.moneybook.ui.more

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.StyleMode
import com.moneybook.domain.WeekendShift
import com.moneybook.ui.theme.MoneyBookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OptionScreensTest {
    @get:Rule val rule = createComposeRule()

    @Test fun monthlyStartDateSavesTheDayAndTheWeekendRule() {
        var saved: Pair<Int, WeekendShift>? = null
        rule.setContent {
            MoneyBookTheme(darkTheme = false) { MonthlyStartDateContent(1, WeekendShift.NONE, onSave = { d, w -> saved = d to w }, onBack = {}) }
        }
        listOf("Monthly Start Date", "Start Date", "Every 1", "If monthly start date is weekend,", "No changes", "Previous Friday", "Following Monday")
            .forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        rule.onNodeWithText("Every 1").performClick()
        rule.onNodeWithText("Every 25").performScrollTo().performClick()
        rule.onNodeWithText("Previous Friday").performClick()
        rule.onNodeWithText("Save").performClick()
        assertEquals(25 to WeekendShift.PREVIOUS_FRIDAY, saved)
    }

    @Test fun styleMarksTheModeAndPicksAnother() {
        var picked: StyleMode? = null
        rule.setContent { MoneyBookTheme(darkTheme = false) { StyleContent(StyleMode.SYSTEM, onPick = { picked = it }, onBack = {}) } }
        listOf("Style", "System Mode", "Dark Mode", "Light Mode").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        rule.onAllNodesWithContentDescription("Selected").assertCountEquals(1)
        rule.onNodeWithText("Dark Mode").performClick()
        assertEquals(StyleMode.DARK, picked)
    }
}
