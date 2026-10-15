package com.moneybook.ui.accounts

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.domain.AccountGroupItem
import com.moneybook.domain.AccountItem
import com.moneybook.domain.AccountsOverview
import com.moneybook.domain.CardStatus
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.ui.theme.MoneyBookTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountsCardTest {
    @get:Rule val rule = createComposeRule()

    private fun show(card: CardStatus) {
        val account = AccountItem("c1", "Visa", -(card.payable + card.outstanding), "CZK", card = card)
        val group = AccountGroupItem("g", "Card", AccountGroupType.CARD, account.balance, listOf(account))
        val overview = AccountsOverview(0, 0, listOf(group))
        rule.setContent {
            MoneyBookTheme { AccountsContent(AccountsUiState(CurrencySpec.of("CZK"), overview, overview), onOpenAccount = {}, onAddAccount = null) }
        }
    }

    @Test fun `card group and row show payable, the overdue part and outstanding`() {
        show(CardStatus(payable = 3_400, outstanding = 5_000, overdue = 400))
        rule.onNodeWithText("Balance Payable", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Outst. Balance", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("34.00Kč", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("30.00Kč", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("-4.00Kč", useUnmergedTree = true).assertExists()
        rule.onAllNodesWithText("50.00Kč", useUnmergedTree = true).assertCountEquals(2)
    }

    @Test fun `a credit shows without a sign and no overdue line`() {
        show(CardStatus(payable = 0, outstanding = -1_000, overdue = 0))
        rule.onAllNodesWithText("10.00Kč", useUnmergedTree = true).assertCountEquals(2)
        rule.onAllNodesWithText("-", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }
}
