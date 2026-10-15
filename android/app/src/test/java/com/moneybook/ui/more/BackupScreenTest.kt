package com.moneybook.ui.more

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.MoneyBookApp
import com.moneybook.ui.theme.MoneyBookTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupScreenTest {
    @get:Rule val rule = createComposeRule()
    private val device get() = ApplicationProvider.getApplicationContext<MoneyBookApp>().container.device

    // Signing in starts the app's sync triggers, which enqueue work.
    @Before fun workManager() = WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext(), Configuration.Builder().build())

    // The device DataStore outlives each test's Application, so undo the sign-in.
    @After fun signOut() = runBlocking { device.update { it.copy(refreshToken = "") } }

    private fun show(signedIn: Boolean) {
        runBlocking { device.update { it.copy(refreshToken = if (signedIn) "token" else "") } }
        rule.setContent { MoneyBookTheme(darkTheme = false) { BackupScreen(onBack = {}, onImportLegacy = {}) } }
    }

    private fun assertQuestion(row: String, base: String, sentence: Boolean) {
        rule.onNodeWithText(row).performClick()
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText(base, substring = true).assertIsDisplayed() }.isSuccess }
        val full = base + (if (sentence) SYNC_NOTE.trimEnd() else "")
        rule.onNodeWithText(full, substring = true).assertIsDisplayed()
        if (!sentence) rule.onNodeWithText("synced devices", substring = true).assertDoesNotExist()
    }

    @Test fun signedInAddsTheSentenceToResetAndRestoreQuestions() {
        show(signedIn = true)
        assertQuestion("A complete reset", "Deletes all entries, accounts and categories, and restores the defaults.", sentence = true)
    }

    @Test fun signedInAddsTheSentenceToResetContents() {
        show(signedIn = true)
        assertQuestion("Reset contents only (Others remain)", "Deletes all entries. Accounts, categories and settings remain.", sentence = true)
    }

    @Test fun signedOutHasNoSentence() {
        show(signedIn = false)
        assertQuestion("A complete reset", "Deletes all entries, accounts and categories, and restores the defaults.", sentence = false)
    }
}
