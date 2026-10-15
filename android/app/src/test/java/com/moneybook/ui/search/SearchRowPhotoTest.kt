package com.moneybook.ui.search

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.db.TxnRow
import com.moneybook.shared.model.TxnType
import com.moneybook.ui.theme.MoneyBookTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchRowPhotoTest {
    @get:Rule val rule = createComposeRule()

    private fun row(photo: Boolean) = TxnRow(
        id = "r", type = TxnType.EXPENSE, dateTime = "2026-09-26T18:40:12.345", amount = 52_500, toAmount = null, amountMain = 52_500,
        currency = "CZK", fee = 0, note = "n", description = "", accountId = "cash", accountName = "Cash",
        toAccountId = null, toAccountName = null, categoryId = null, categoryName = "Food", parentCategoryName = null, hasPhoto = photo,
    )

    @Test fun theIconShowsOnlyForAnEntryWithAPhoto() {
        rule.setContent { MoneyBookTheme(darkTheme = false) { SearchRow(row(true)) {}; SearchRow(row(false)) {} } }
        rule.onAllNodesWithContentDescription("Photo", useUnmergedTree = true).assertCountEquals(1)
    }
}
