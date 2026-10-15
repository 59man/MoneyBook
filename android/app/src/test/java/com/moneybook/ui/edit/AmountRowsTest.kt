package com.moneybook.ui.edit

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.domain.AmountInput
import com.moneybook.shared.model.TxnType
import com.moneybook.shared.money.Fx
import com.moneybook.ui.common.specOf
import com.moneybook.ui.theme.MoneyBookTheme
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A repeat rule stores only the amount and its currency, so rule mode shows neither Fees nor the entry's own rate. */
@RunWith(AndroidJUnit4::class)
class AmountRowsTest {
    @get:Rule val rule = createComposeRule()

    private fun transfer(ruleId: String?, fee: AmountInput?) = EditTxnState(
        date = LocalDate.of(2026, 10, 8), time = LocalTime.of(12, 0), type = TxnType.TRANSFER, ruleId = ruleId,
        amount = AmountInput.of(2_000, specOf("EUR")), currency = specOf("EUR"), mainCurrency = specOf("USD"),
        fx = Fx("USD", mapOf("EUR" to BigDecimal("1.1"))), fee = fee, feeCurrency = specOf("USD"),
    )

    private fun show(s: EditTxnState) = rule.setContent { MoneyBookTheme(darkTheme = false) { Column { AmountRows(s, Color.Black, {}, {}, {}, {}) } } }

    @Test fun anEntryShowsTheRateAndFees() {
        show(transfer(ruleId = null, fee = null))
        rule.onNodeWithText("Exchange rate: 1.1").assertExists()
        rule.onNodeWithText("Fees").assertExists()
    }

    @Test fun ruleModeHidesTheRateAndFees() {
        show(transfer(ruleId = "r-1", fee = AmountInput.of(200, specOf("USD"))))
        assertEquals(0, rule.onAllNodesWithText("Exchange rate: 1.1").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("Fees").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithContentDescription("Remove fees").fetchSemanticsNodes().size)
    }
}
