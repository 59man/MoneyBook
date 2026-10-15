package com.moneybook.ui.bookmarks

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.db.BookmarkRow
import com.moneybook.shared.model.TxnType
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.ui.theme.MoneyBookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookmarksScreenTest {
    @get:Rule val rule = createComposeRule()
    private val row = BookmarkRow("b", TxnType.EXPENSE, 17_400, "CZK", "Oběd", "Cash", null, "🍜 Food", null)

    @Test fun listsBookmarksUnderTheHelpCardAndUsesOrDeletesThem() {
        var used: String? = null
        var deleted: String? = null
        rule.setContent {
            MoneyBookTheme(darkTheme = false) {
                BookmarksContent(BookmarksState(listOf(row), CurrencySpec.of("CZK")), onBack = {}, onUse = { used = it }, onDelete = { deleted = it })
            }
        }
        listOf("Bookmarks", "How to register Bookmark?", "🍜 Food", "Oběd", "Cash", "174.00Kč").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        rule.onNodeWithText("Oběd").performClick()
        assertEquals("b", used)
        rule.onNodeWithText("Oběd").performTouchInput { longClick() }
        rule.onNodeWithText("Delete this bookmark?").assertIsDisplayed()
        rule.onNodeWithText("Delete").performClick()
        assertEquals("b", deleted)
    }
}
