package com.moneybook.notify

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class QuickAddTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val notifications = app.getSystemService(NotificationManager::class.java)

    @Before fun grant() = shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    @Test fun quickAddIsAnOngoingNotificationThatHides() {
        val notifier = QuickAddNotifier(app)
        notifier.show()
        val posted = shadowOf(notifications).allNotifications.single()
        assertTrue(posted.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(Notifications.QUICK_ADD_CHANNEL, posted.channelId)
        notifier.hide()
        assertEquals(0, shadowOf(notifications).allNotifications.size)
    }

    @Test fun eachShortcutCarriesItsScreen() {
        val intent = shadowOf(Notifications.openApp(app, MainActivity.OPEN_SEARCH, 11)).savedIntent
        assertEquals(MainActivity.OPEN_SEARCH, intent.getStringExtra(MainActivity.EXTRA_OPEN))
    }

    @Test fun theBookmarksShortcutOpensBookmarks() {
        val intent = Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_BOOKMARKS)
        ActivityScenario.launch<MainActivity>(intent).use {
            rule.waitUntil(10_000) { rule.onAllNodesWithText("How to register Bookmark?").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("How to register Bookmark?").assertIsDisplayed()
        }
    }

    @Test fun theIncomeShortcutOpensTheFormOnIncome() {
        val intent = Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_ADD_INCOME)
        ActivityScenario.launch<MainActivity>(intent).use {
            // The title and the selected segment both read Income; an Expense form shows "Income" once.
            rule.waitUntil(10_000) { rule.onAllNodesWithText("Income").fetchSemanticsNodes().size >= 2 }
        }
    }
}
