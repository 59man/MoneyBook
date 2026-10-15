package com.moneybook.ui.edit

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.CopyDateBy
import com.moneybook.ui.common.LocalAppLocked
import com.moneybook.ui.theme.MoneyBookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CopyDialogTest {
    @get:Rule val rule = createComposeRule()

    private fun show(selected: CopyDateBy, locked: Boolean = false, onSelect: (CopyDateBy) -> Unit = {}, onDismiss: () -> Unit = {}, onOk: () -> Unit = {}) =
        rule.setContent {
            MoneyBookTheme(darkTheme = false) {
                CompositionLocalProvider(LocalAppLocked provides locked) { CopyDialog(selected, onSelect, onDismiss, onOk) }
            }
        }

    @Test fun showsTheTwoRowsWithTheStoredChoiceSelected() {
        show(CopyDateBy.ENTRY_DATE)
        rule.onNodeWithText("Copy").assertExists()
        rule.onNodeWithText("Copy with today's date").assertExists()
        rule.onNodeWithText("Copy with date of entry").assertExists()
        rule.onNodeWithText("OK").assertExists()
        // one check, and no ✕ (Realbyte's Copy dialog has none)
        rule.onAllNodesWithContentDescription("Selected", useUnmergedTree = true).assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Close").assertCountEquals(0)
    }

    @Test fun aRowTapSelectsAndOkConfirms() {
        val chosen = mutableListOf<CopyDateBy>()
        var ok = 0
        show(CopyDateBy.TODAY, onSelect = { chosen += it }, onOk = { ok++ })
        rule.onNodeWithText("Copy with date of entry").performClick()
        assertEquals(listOf(CopyDateBy.ENTRY_DATE), chosen)
        assertEquals(0, ok)
        rule.onNodeWithText("OK").performClick()
        assertEquals(1, ok)
    }

    @Test fun staysHiddenWhileTheAppIsLocked() {
        show(CopyDateBy.TODAY, locked = true)
        rule.onAllNodesWithText("Copy").assertCountEquals(0)
    }
}
