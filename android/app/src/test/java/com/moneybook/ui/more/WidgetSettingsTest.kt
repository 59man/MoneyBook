package com.moneybook.ui.more

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.StyleMode
import com.moneybook.domain.Mom
import com.moneybook.domain.Totals
import com.moneybook.domain.WidgetSummary
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.ui.theme.MoneyBookTheme
import com.moneybook.widget.SummaryData
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetSettingsTest {
    @get:Rule val rule = createComposeRule()

    private val data = SummaryData(
        WidgetSummary(Totals(income = 0, expense = 12_000), income = Mom(0, null, null), expense = Mom(-5_000, 50, good = true)),
        CurrencySpec.of("CZK"),
    )

    @Test fun thePreviewShowsTheExpensesTabAndTheRowsChangeTheLook() {
        var background: StyleMode? = null
        var opacity: Int? = null
        rule.setContent {
            MoneyBookTheme(darkTheme = false) {
                WidgetSettingsContent(data, StyleMode.SYSTEM, 100, onBackground = { background = it }, onOpacity = { opacity = it }, onBack = {})
            }
        }
        rule.onNodeWithText("Widget Settings").assertExists()
        rule.onNodeWithText("This Month").assertExists()
        rule.onNodeWithText("120.00").assertExists()
        rule.onNodeWithText("▼ 50.00 (50%)").assertExists()
        rule.onNodeWithText("100%").assertExists()
        rule.onNodeWithText("Dark Mode").performClick()
        assertEquals(StyleMode.DARK, background)
        rule.onNodeWithContentDescription("Background Opacity").performSemanticsAction(SemanticsActions.SetProgress) { it(37f) }
        assertEquals(35, opacity)
    }
}
