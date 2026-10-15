package com.moneybook.ui.edit

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.testutil.testPhotos
import com.moneybook.ui.theme.MoneyBookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoStripTest {
    @get:Rule val rule = createComposeRule()

    @Test fun oneCellPerPhotoAndTheCrossRemovesItsName() {
        val removed = mutableListOf<String>()
        rule.setContent {
            MoneyBookTheme(darkTheme = false) { PhotoStrip(listOf("a.jpg", "b.jpg"), testPhotos(), removed::add, decode = { null as ImageBitmap? }) }
        }
        rule.onNodeWithContentDescription("First photo").assertExists()
        rule.onNodeWithContentDescription("Second photo").assertExists()
        rule.onNodeWithContentDescription("Third photo").assertDoesNotExist()
        rule.onAllNodesWithContentDescription("Delete", useUnmergedTree = true)[1].performClick()
        assertEquals(listOf("b.jpg"), removed)
    }

    @Test fun aTapOnACellOpensItsIndex() {
        val opened = mutableListOf<Int>()
        rule.setContent {
            MoneyBookTheme(darkTheme = false) { PhotoStrip(listOf("a.jpg", "b.jpg"), testPhotos(), {}, onOpen = opened::add, decode = { null as ImageBitmap? }) }
        }
        rule.onNodeWithContentDescription("Second photo").performClick()
        assertEquals(listOf(1), opened)
    }

    @Test fun noPhotosDrawNothing() {
        rule.setContent { MoneyBookTheme(darkTheme = false) { PhotoStrip(emptyList(), testPhotos(), {}, decode = { null }) } }
        rule.onNodeWithContentDescription("First photo").assertDoesNotExist()
    }
}
