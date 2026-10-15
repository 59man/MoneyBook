package com.moneybook.ui.more

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.ui.theme.MoneyBookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlarmScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theSwitchTurnsTheAlarmOnAndTheTimeShowsOnlyThen() {
        var toggled: Boolean? = null
        rule.setContent { MoneyBookTheme(darkTheme = false) { AlarmContent(false, 21 * 60, onToggle = { toggled = it }, onTime = {}, onBack = {}) } }
        rule.onNodeWithText("Alarm Setting").assertIsDisplayed()
        rule.onNodeWithText("MoneyBook can remind you", substring = true).assertIsDisplayed()
        rule.onNodeWithText("21:00").assertDoesNotExist()
        rule.onNodeWithContentDescription("Alarm").performClick()
        assertEquals(true, toggled)
    }

    @Test fun whileOnTheTimeIsShown() {
        rule.setContent { MoneyBookTheme(darkTheme = false) { AlarmContent(true, 8 * 60 + 5, onToggle = {}, onTime = {}, onBack = {}) } }
        rule.onNodeWithText("08:05").assertIsDisplayed()
    }
}
