package com.moneybook.ui.more

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.db.RepeatRuleRow
import com.moneybook.shared.model.RepeatFrequency
import com.moneybook.shared.model.RepeatFrequency.MONTHLY
import com.moneybook.shared.model.RepeatFrequency.WEEKLY
import com.moneybook.shared.model.TxnType
import com.moneybook.ui.theme.MoneyBookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RepeatSettingTest {
    @get:Rule val rule = createComposeRule()

    private fun row(id: String, frequency: RepeatFrequency, start: String, last: String, installments: Int? = null, note: String = "") =
        RepeatRuleRow(
            id = id, frequency = frequency, startDate = start, lastDate = last, installments = installments, type = TxnType.EXPENSE,
            amount = 10_000, currency = "CZK", note = note, accountName = "Cash", toAccountName = null, categoryName = "🍜 Food",
            parentCategoryName = null,
        )

    @Test fun itemsComeSoonestFirstAndPlansShowTheirPart() {
        val items = repeatItems(
            listOf(
                row("m", MONTHLY, "2026-09-20", "2026-09-20"), // next Oct 20
                row("w", WEEKLY, "2026-10-01", "2026-10-01"), // next Oct 8
                row("p", MONTHLY, "2026-08-10", "2026-09-10", installments = 3), // next Oct 10, the third part
                row("done", MONTHLY, "2026-07-01", "2026-09-01", installments = 3), // all three parts added
            ),
        )
        assertEquals(listOf("w", "p", "m", "done"), items.map { it.row.id })
        assertEquals("Every Week", items[0].title())
        assertEquals("Installment 3/3", items[1].title())
        assertEquals("10.10 (Sat)", items[1].dateText())
        assertEquals("Finished", items[3].dateText())
    }

    @Test fun theScreenShowsHelpTimingAndRules() {
        var opened: String? = null
        var added = false
        rule.setContent {
            MoneyBookTheme(darkTheme = false) {
                RepeatSettingContent(
                    RepeatSettingState(repeatItems(listOf(row("m", MONTHLY, "2026-09-20", "2026-09-20", note = "Nájem")))),
                    onBack = {}, onAdd = { added = true }, onOpen = { opened = it },
                )
            }
        }
        listOf("Repeat Setting", "How to setup 'Repeat' transactions?", "Timing of reflection", "On the date", "Every Month", "Nájem", "100.00Kč")
            .forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        rule.onNodeWithText("Nájem").performClick()
        assertEquals("m", opened)
        rule.onNodeWithContentDescription("Add").performClick()
        assertEquals(true, added)
    }
}
