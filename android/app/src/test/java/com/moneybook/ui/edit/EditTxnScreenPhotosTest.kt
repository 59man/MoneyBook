package com.moneybook.ui.edit

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.LockState
import com.moneybook.MainActivity
import com.moneybook.MoneyBookApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/** The camera icon at the end of Description: it opens the Gallery / Camera menu, and a tap on the menu leaves for the picker. */
@RunWith(AndroidJUnit4::class)
class EditTxnScreenPhotosTest {
    @get:Rule(order = 0) val rule = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<MoneyBookApp>()

    @get:Rule(order = 1) val opened = object : ExternalResource() {
        override fun before() {
            rule.waitUntil(10_000) { app.container.lock.state.value == LockState.OPEN }
            rule.waitUntil(10_000) { runBlocking { app.container.db.categoryDao().all().isNotEmpty() } }
            rule.waitForIdle()
        }
    }

    @Test fun theCameraIconOpensTheMenuAndAlsoTellsTheLockWeAreLeaving() {
        rule.onNodeWithContentDescription("Add").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Expense").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Camera").performClick()
        rule.onNodeWithText("Gallery").assertExists()
        rule.onNodeWithText("Gallery").performClick()
        // The launch (a picker activity Robolectric can't show) was announced to the lock before it.
        app.container.lock.onStop(now = 0)
        runBlocking { app.container.lock.onStart(now = Long.MAX_VALUE / 2) }
        assertEquals(LockState.OPEN, app.container.lock.state.value)
    }
}
