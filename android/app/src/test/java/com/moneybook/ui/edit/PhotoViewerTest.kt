package com.moneybook.ui.edit

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.testutil.testPhotos
import com.moneybook.ui.theme.MoneyBookTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoViewerTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun show(start: Int, saved: Set<String>, onBack: () -> Unit = {}, onDownload: (String) -> Unit = {}) = rule.setContent {
        MoneyBookTheme(darkTheme = false) {
            PhotoViewer(listOf("a.jpg", "b.jpg", "c.jpg"), saved, start, testPhotos(), onBack, onDownload, decode = { null as ImageBitmap? })
        }
    }

    @Test fun opensAtTheTappedPhoto() {
        show(start = 1, saved = emptySet())
        rule.onNodeWithContentDescription("Photo 2").assertExists()
        rule.onNodeWithContentDescription("Photo 1").assertDoesNotExist()
    }

    @Test fun backButtonAndSystemBackClose() {
        var closed = 0
        show(start = 0, saved = emptySet(), onBack = { closed++ })
        rule.onNodeWithContentDescription("Back").performClick()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(2, closed)
    }

    @Test fun downloadIconOnlyForAPhotoSavedWithTheEntry() {
        val got = mutableListOf<String>()
        show(start = 0, saved = setOf("b.jpg"), onDownload = got::add)
        rule.onNodeWithContentDescription("Download").assertDoesNotExist()
        rule.onNodeWithContentDescription("Photo 1").assertExists()
    }

    @Test fun downloadSendsTheCurrentPhotosName() {
        val got = mutableListOf<String>()
        show(start = 1, saved = setOf("b.jpg"), onDownload = got::add)
        rule.onNodeWithContentDescription("Download").performClick()
        assertEquals(listOf("b.jpg"), got)
    }

    @Test fun zoomClampsBetweenQuarterStepsAndTen() {
        assertEquals(0.75f, nextPhotoScale(1f, 0.1f), 0f)
        assertEquals(10f, nextPhotoScale(5f, 5f), 0f)
        assertEquals(3f, nextPhotoScale(1.5f, 2f), 0f)
        assertTrue(doubleTapScale(1f) == 2f && doubleTapScale(2f) == 1f && doubleTapScale(0.75f) == 2f)
        assertFalse(doubleTapScale(5f) == 2f)
    }
}
