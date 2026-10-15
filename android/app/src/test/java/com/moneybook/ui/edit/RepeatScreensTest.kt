package com.moneybook.ui.edit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.shared.model.RepeatFrequency
import com.moneybook.ui.theme.MoneyBookTheme
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RepeatScreensTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theRepeatListMarksTheChoiceAndPicksOne() {
        var picked: RepeatFrequency? = RepeatFrequency.DAILY
        rule.setContent {
            MoneyBookTheme(darkTheme = false) { RepeatPickScreen(RepeatFrequency.MONTHLY, includeNothing = true, onPick = { picked = it }, onBack = {}) }
        }
        listOf("Repeat", "Nothing", "Every Day", "Weekdays", "Every Month", "The end of the month").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        rule.onNodeWithContentDescription("Selected").assertIsDisplayed()
        rule.onNodeWithText("Weekdays").performClick()
        assertEquals(RepeatFrequency.WEEKDAYS, picked)
        rule.onNodeWithText("Nothing").performClick()
        assertNull(picked)
    }

    @Test fun theInstallmentPadAcceptsTwoToSixtyMonths() {
        val saved = mutableListOf<Int>()
        rule.setContent { MoneyBookTheme(darkTheme = false) { InstallmentScreen(null, onSave = { saved += it }, onBack = {}) } }
        rule.onNodeWithText("Monthly installment plan").assertIsDisplayed()
        rule.onNodeWithText("1").performClick()
        rule.onNodeWithContentDescription("Save months").performClick()
        assertEquals(emptyList<Int>(), saved) // 1 month is not a plan
        rule.onNodeWithText("2").performClick()
        rule.onNodeWithText("12").assertIsDisplayed()
        rule.onNodeWithContentDescription("Save months").performClick()
        rule.onNodeWithText("C").performClick()
        rule.onNodeWithText("6").performClick()
        rule.onAllNodesWithText("Save").onFirst().performClick() // the big Save button above the pad
        assertEquals(listOf(12, 6), saved)
    }

    @Test fun tapsOnTheRepeatListDoNotReachTheFormBehindIt() {
        var behind = false
        rule.setContent {
            MoneyBookTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().clickable { behind = true })
                    InstallmentScreen(null, onSave = {}, onBack = {})
                }
            }
        }
        rule.onNodeWithText("Monthly installment plan").performClick()
        assertEquals(false, behind)
    }

    @Test fun theDateRowShowsRepInstOnlyWhenItCanBeUsed() {
        var tapped = false
        rule.setContent {
            MoneyBookTheme(darkTheme = false) {
                DateRow(LocalDate.of(2026, 10, 6), LocalTime.of(9, 30), "Every Month", onDate = {}, onTime = {}, onRepeat = { tapped = true })
            }
        }
        rule.onNodeWithText("Every Month").performClick()
        assertEquals(true, tapped)
    }

    @Test fun anExistingEntryHasNoRepInst() {
        rule.setContent {
            MoneyBookTheme(darkTheme = false) { DateRow(LocalDate.of(2026, 10, 6), LocalTime.of(9, 30), null, onDate = {}, onTime = {}, onRepeat = null) }
        }
        rule.onNodeWithText("Rep/Inst.").assertDoesNotExist()
    }

    @Test fun theDateRowCanHideTheTime() {
        rule.setContent {
            MoneyBookTheme(darkTheme = false) {
                DateRow(LocalDate.of(2026, 10, 6), LocalTime.of(9, 30), null, onDate = {}, onTime = {}, onRepeat = null, showTime = false)
            }
        }
        rule.onNodeWithText("09:30").assertDoesNotExist()
        rule.onNodeWithText("10/6/26 (Tue)").assertIsDisplayed()
    }
}
