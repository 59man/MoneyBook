package com.moneybook.ui.more

import androidx.compose.ui.test.assert
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.domain.Formats
import com.moneybook.ui.common.LocalAppLocked
import com.moneybook.ui.theme.MoneyBookTheme
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncScreenTest {
    @get:Rule val rule = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun show(state: SyncUiState, locked: Boolean = false) = rule.setContent {
        MoneyBookTheme(darkTheme = false) {
            CompositionLocalProvider(LocalAppLocked provides locked) {
                SyncContent(
                    state, onBack = { calls += "back" },
                    onLogin = { url, key -> calls += "login $url|$key" },
                    onSyncNow = { calls += "syncNow" }, onLogout = { calls += "logout" },
                    onMerge = { calls += "merge" }, onReplace = { calls += "replace" }, onCancelLogin = { calls += "cancel" },
                )
            }
        }
    }

    private val signedIn = SyncUiState(signedIn = true, serverUrl = "https://casa.example.ts.net")

    @Test fun signedOutShowsFieldsAndLogsIn() {
        show(SyncUiState())
        listOf("Sync", "Server URL", "API key", "Show", "Log in").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        rule.onNodeWithContentDescription("Server URL").performTextInput("casa.example.ts.net")
        rule.onNodeWithContentDescription("API key").performTextInput("secret key")
        rule.onNodeWithContentDescription("API key").performImeAction() // Done submits
        assertEquals(listOf("login casa.example.ts.net|secret key"), calls)
    }

    @Test fun logInButtonSubmitsAndPrefillsFromTheDevice() {
        show(SyncUiState(serverUrl = "https://casa.example.ts.net"))
        rule.onNodeWithContentDescription("API key").performTextInput("pw")
        rule.onNodeWithText("Log in").performClick()
        assertEquals(listOf("login https://casa.example.ts.net|pw"), calls)
    }

    @Test fun apiKeyIsMaskedUntilShown() {
        show(SyncUiState())
        rule.onNodeWithContentDescription("API key").performTextInput("abc123")
        fun shown(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text))
        rule.onNodeWithContentDescription("API key").assert(shown("••••••"))
        rule.onNodeWithText("Show").performClick()
        rule.onNodeWithContentDescription("API key").assert(shown("abc123"))
        rule.onNodeWithText("Hide").assertIsDisplayed()
    }

    @Test fun busyLoginShowsLoggingInAndDoesNotSubmitAgain() {
        show(SyncUiState(busy = true))
        rule.onNodeWithText("Log in").assertDoesNotExist()
        rule.onNodeWithText("Logging in…").performClick()
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun errorLineShowsUnderTheButtonSignedOut() {
        show(SyncUiState(error = loginErrorText(com.moneybook.data.sync.SyncError.Unauthorized)))
        rule.onNodeWithText("Wrong API key").assertIsDisplayed()
    }

    @Test fun signedInShowsServerAccountAndNeverSynced() {
        show(signedIn)
        listOf("Server", "https://casa.example.ts.net", "Last sync", "Never", "Sync now", "Log out")
            .forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        rule.onNodeWithText("Log in").assertDoesNotExist()
    }

    @Test fun signedInShowsTheLastSyncTimeAndTheErrorLine() {
        val at = 1_792_000_000_000L
        show(signedIn.copy(lastSyncAt = at, error = "Offline"))
        rule.onNodeWithText(Formats.syncTime(at, ZoneId.systemDefault())).assertIsDisplayed()
        rule.onNodeWithText("Never").assertDoesNotExist()
        rule.onNodeWithText("Offline").assertIsDisplayed()
    }

    @Test fun syncNowRuns() {
        show(signedIn)
        rule.onNodeWithText("Sync now").performClick()
        assertEquals(listOf("syncNow"), calls)
    }

    @Test fun syncNowShowsSyncingWhileRunning() {
        show(signedIn.copy(syncing = true))
        rule.onNodeWithText("Sync now").assertDoesNotExist()
        rule.onNodeWithText("Syncing…").assertIsNotEnabled()
    }

    @Test fun logOutAsksFirst() {
        show(signedIn)
        rule.onNodeWithText("Log out").performClick()
        rule.onNodeWithText("This device stops syncing", substring = true).assertIsDisplayed()
        assertEquals(emptyList<String>(), calls)
        rule.onNode(hasText("Log out") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        assertEquals(listOf("logout"), calls)
    }

    @Test fun logOutCanBeCancelled() {
        show(signedIn)
        rule.onNodeWithText("Log out").performClick()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(emptyList<String>(), calls)
        rule.onNodeWithText("This device stops syncing", substring = true).assertDoesNotExist()
    }

    private fun chooseOption(option: String, call: String) {
        show(SyncUiState(choice = true))
        rule.onNodeWithText("This device already has data").assertIsDisplayed()
        rule.onNodeWithText("The account on the server has data too.", substring = true).assertIsDisplayed()
        rule.onNodeWithText(option).performClick()
        assertEquals(listOf(call), calls)
    }

    @Test fun choiceDialogMerge() = chooseOption("Merge", "merge")

    @Test fun choiceDialogReplace() = chooseOption("Replace this device's data", "replace")

    @Test fun choiceDialogCancel() = chooseOption("Cancel", "cancel")

    @Test fun choiceDialogHiddenWhileLocked() {
        show(SyncUiState(choice = true), locked = true)
        rule.onNodeWithText("This device already has data").assertDoesNotExist()
    }
}
