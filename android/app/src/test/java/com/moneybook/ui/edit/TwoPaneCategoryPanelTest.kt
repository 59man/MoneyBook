package com.moneybook.ui.edit

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.db.CategoryEntity
import com.moneybook.shared.model.CategoryType
import com.moneybook.ui.theme.MoneyBookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TwoPaneCategoryPanelTest {
    @get:Rule val rule = createComposeRule()
    private fun cat(id: String, name: String, parent: String? = null) =
        CategoryEntity(id = id, type = CategoryType.EXPENSE, parentId = parent, name = name, sortOrder = 0, updatedAt = 0)

    private val top = listOf(cat("food", "Food"), cat("home", "Home"))
    private val subs = listOf(cat("lunch", "Lunch", "food"), cat("dinner", "Dinner", "food"))
    private var picked: String? = null

    private fun show(current: String? = null) = rule.setContent {
        MoneyBookTheme(darkTheme = false) { TwoPaneCategoryPanel(top, subs, current, { picked = it }, {}) }
    }

    @Test fun aCategoryWithSubcategoriesFillsTheRightPaneAndPickingASubPicks() {
        show()
        rule.onNodeWithText("Lunch").assertDoesNotExist()
        rule.onNodeWithText("Food").performClick()
        assertEquals(null, picked)
        rule.onNodeWithText("Lunch").assertIsDisplayed()
        rule.onNodeWithText("Dinner").assertIsDisplayed()
        rule.onNodeWithText("Lunch").performClick()
        assertEquals("lunch", picked)
    }

    @Test fun aCategoryWithoutSubcategoriesIsPickedAtOnce() {
        show()
        rule.onNodeWithText("Home").performClick()
        assertEquals("home", picked)
    }

    @Test fun theCurrentSubcategorysParentStartsSelected() {
        show(current = "dinner")
        rule.onNodeWithText("Lunch").assertIsDisplayed()
    }
}
