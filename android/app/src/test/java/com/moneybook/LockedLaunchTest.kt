package com.moneybook

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import android.content.Intent
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.domain.Passcode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LockedLaunchTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<MoneyBookApp>()

    /** The DataStore outlives this test's Application (one per process), so later tests would start locked. */
    @After fun removePasscode() = runBlocking { app.container.device.update { it.copy(passcodeHash = null, passcodeSalt = null) } }

    @Test fun aColdStartWithAPasscodeHidesTheAppEvenFromAccessibilityUntilUnlocked() {
        val salt = Passcode.newSalt()
        runBlocking { app.container.device.update { it.copy(passcodeHash = Passcode.hash("4826", salt), passcodeSalt = salt) } }
        ActivityScenario.launch(MainActivity::class.java).use {
            rule.waitUntil(10_000) { rule.onAllNodesWithText("Enter your passcode").fetchSemanticsNodes().isNotEmpty() }
            // The nav host stays composed behind the lock, but nothing of it may be in the semantics tree.
            rule.onNodeWithText("Trans.").assertDoesNotExist()
            "4826".forEach { d -> rule.onNodeWithText(d.toString()).performClick() }
            rule.waitUntil(10_000) { rule.onAllNodesWithText("Trans.").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("Trans.").assertIsDisplayed()
        }
    }

    @Test fun aShortcutWaitsForTheUnlockBeforeOpeningItsScreen() {
        val salt = Passcode.newSalt()
        runBlocking { app.container.device.update { it.copy(passcodeHash = Passcode.hash("4826", salt), passcodeSalt = salt) } }
        val intent = Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_SEARCH)
        ActivityScenario.launch<MainActivity>(intent).use {
            rule.waitUntil(10_000) { rule.onAllNodesWithText("Enter your passcode").fetchSemanticsNodes().isNotEmpty() }
            rule.waitForIdle()
            // Search's field takes focus (and the keyboard) when it opens, so it must not open behind the lock.
            assertTrue(rule.onAllNodesWithContentDescription("Search text", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
            "4826".forEach { d -> rule.onNodeWithText(d.toString()).performClick() }
            rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("Search text").fetchSemanticsNodes().isNotEmpty() }
        }
    }
}
