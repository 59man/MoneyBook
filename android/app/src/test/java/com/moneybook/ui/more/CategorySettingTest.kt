package com.moneybook.ui.more

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.shared.model.CategoryType
import com.moneybook.testutil.Fixtures
import com.moneybook.ui.theme.MoneyBookTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CategorySettingTest {
    @get:Rule val rule = createComposeRule()
    private val items = listOf(Fixtures.category("f", name = "🍜 Food"), Fixtures.category("p", name = "🐶 Pets", sortOrder = 1))

    @Test fun deleteAsksFirstAndPencilOpensTheEditor() {
        var deleted: String? = null
        var edited: String? = null
        var added = false
        rule.setContent {
            MoneyBookTheme(darkTheme = false) {
                CategorySettingContent(
                    CategoryType.EXPENSE, items, onBack = {}, onAdd = { added = true }, onEdit = { edited = it },
                    onDelete = { deleted = it }, onReorder = {},
                )
            }
        }
        rule.onNodeWithText("Expenses Category").assertIsDisplayed()
        rule.onNodeWithContentDescription("Delete 🐶 Pets").performClick()
        assertNull(deleted)
        rule.onNodeWithText("Delete").performClick()
        assertEquals("p", deleted)
        rule.onNodeWithContentDescription("Edit 🍜 Food").performClick()
        assertEquals("f", edited)
        rule.onNodeWithContentDescription("Add").performClick()
        assertEquals(true, added)
    }

    @Test fun deleteCanMoveTheEntriesToAnotherCategory() {
        var moved: Pair<String, String>? = null
        var deleted: String? = null
        rule.setContent {
            MoneyBookTheme(darkTheme = false) {
                CategorySettingContent(
                    CategoryType.EXPENSE, items, onBack = {}, onAdd = {}, onEdit = {}, onDelete = { deleted = it }, onReorder = {},
                    onMove = { from, to -> moved = from to to },
                )
            }
        }
        rule.onNodeWithContentDescription("Delete 🐶 Pets").performClick()
        rule.onNodeWithText("Move entries…").performClick()
        rule.onNodeWithText("Move entries to").assertIsDisplayed()
        rule.onAllNodesWithText("🐶 Pets").assertCountEquals(1) // only the list row; the category being deleted is not a target
        rule.onAllNodesWithText("🍜 Food")[1].performClick()
        assertEquals("p" to "f", moved)
        assertNull(deleted)
    }

    private fun content(
        subcategory: Boolean, onSubcategory: ((Boolean) -> Unit)? = {}, onOpenSubs: (String) -> Unit = {},
        subs: List<com.moneybook.data.db.CategoryEntity> = emptyList(),
    ) = rule.setContent {
        MoneyBookTheme(darkTheme = false) {
            CategorySettingContent(
                CategoryType.EXPENSE, items, onBack = {}, onAdd = {}, onEdit = {}, onDelete = {}, onReorder = {},
                subcategory = subcategory, onSubcategory = onSubcategory, subs = subs, onOpenSubs = onOpenSubs,
            )
        }
    }

    private val subs = listOf(
        Fixtures.category("l", name = "Lunch", parentId = "f"), Fixtures.category("d", name = "Dinner", parentId = "f", sortOrder = 1),
    )

    @Test fun offShowsTheSwitchAndPlainRows() {
        var tapped: String? = null
        content(subcategory = false, onOpenSubs = { tapped = it }, subs = subs)
        rule.onNodeWithText("Subcategory").assertIsDisplayed()
        rule.onNodeWithText("🍜 Food").assertIsDisplayed() // no count, no names
        rule.onAllNodesWithText("Lunch, Dinner", substring = true).assertCountEquals(0)
        rule.onNodeWithText("🍜 Food").performClick()
        assertNull(tapped)
    }

    @Test fun onShowsCountsAndNamesAndARowOpensItsSubcategories() {
        var tapped: String? = null
        var edited: String? = null
        rule.setContent {
            MoneyBookTheme(darkTheme = false) {
                CategorySettingContent(
                    CategoryType.EXPENSE, items, onBack = {}, onAdd = {}, onEdit = { edited = it }, onDelete = {}, onReorder = {},
                    subcategory = true, onSubcategory = {}, subs = subs, onOpenSubs = { tapped = it },
                )
            }
        }
        rule.onNodeWithText("🍜 Food (2)", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Lunch, Dinner", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("🐶 Pets", useUnmergedTree = true).assertIsDisplayed() // none: no count
        rule.onNodeWithText("🍜 Food (2)", useUnmergedTree = true).performClick()
        assertEquals("f", tapped)
        rule.onNodeWithContentDescription("Edit 🍜 Food").performClick()
        assertEquals("f", edited)
    }

    @Test fun theSwitchWritesTheSetting() {
        val written = mutableListOf<Boolean>()
        content(subcategory = false, onSubcategory = { written += it })
        rule.onNodeWithContentDescription("Subcategory").performClick()
        assertEquals(listOf(true), written)
    }

    @Test fun theSubcategoryListHasItsOwnTitleAndNoSwitch() {
        rule.setContent {
            MoneyBookTheme(darkTheme = false) {
                CategorySettingContent(
                    CategoryType.EXPENSE, subs, onBack = {}, onAdd = {}, onEdit = {}, onDelete = {}, onReorder = {}, title = "Subcategory",
                )
            }
        }
        rule.onAllNodesWithText("Subcategory").assertCountEquals(1)
        rule.onNodeWithText("Lunch").assertIsDisplayed()
    }

    @Test fun moveBlockOffersToSubcategoryWithRealbytesConfirmation() {
        var moved: String? = null
        rule.setContent {
            MoneyBookTheme(darkTheme = false) {
                CategoryMoveBlock(name = "Pets", isMain = true, targets = listOf(items[0]), onToSub = { moved = it }, onToMain = {})
            }
        }
        rule.onNodeWithText("\u2192 Subcategory").performClick()
        rule.onNodeWithText("\uD83C\uDF5C Food").performClick() // picking a parent asks first
        assertNull(moved)
        rule.onNodeWithText(
            "Category \u2019Pets\u2019 will now become a subcategory of \u2019\uD83C\uDF5C Food\u2019. All subcategories of category \u2019Pets\u2019 will become " +
                "subcategories of \u2019\uD83C\uDF5C Food\u2019. Do you wish to continue?",
        ).assertIsDisplayed()
        rule.onNodeWithText("YES").performClick()
        assertEquals("f", moved)
    }

    @Test fun moveBlockOnASubcategoryOffersToMainCategory() {
        var main = false
        rule.setContent {
            MoneyBookTheme(darkTheme = false) {
                CategoryMoveBlock(name = "Lunch", isMain = false, targets = emptyList(), onToSub = {}, onToMain = { main = true })
            }
        }
        rule.onAllNodesWithText("\u2192 Subcategory").assertCountEquals(0)
        rule.onNodeWithText("\u2192 Main Category").performClick()
        assertEquals(true, main)
    }
}
