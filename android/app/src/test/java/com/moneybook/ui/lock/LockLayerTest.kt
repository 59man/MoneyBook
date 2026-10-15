package com.moneybook.ui.lock

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.LockState
import com.moneybook.testutil.FakeDeviceSettingsRepository
import com.moneybook.ui.common.ConfirmDialog
import com.moneybook.ui.theme.MoneyBookTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LockLayerTest {
    @get:Rule val rule = createComposeRule()
    private val device = FakeDeviceSettingsRepository()

    // A dialog is a window of its own above the lock screen, so it must not show at all while locked.
    @Test fun aDialogLeftOpenHidesWhileLockedAndComesBackAfter() {
        var lock by mutableStateOf(LockState.OPEN)
        rule.setContent {
            MoneyBookTheme(darkTheme = false) {
                LockLayer(lock, device, onUnlock = {}, onLeave = {}) {
                    ConfirmDialog("Restore", "Replaces all current data.", "Restore", onConfirm = {}, onDismiss = {})
                }
            }
        }
        rule.onNodeWithText("Replaces all current data.").assertExists()
        lock = LockState.LOCKED
        rule.onNodeWithText("Replaces all current data.").assertDoesNotExist()
        lock = LockState.OPEN
        rule.onNodeWithText("Replaces all current data.").assertExists()
    }

    @Test fun lockingTakesTheFocusAwaySoTheKeyboardCannotTypeBehindIt() {
        var lock by mutableStateOf(LockState.OPEN)
        rule.setContent {
            MoneyBookTheme(darkTheme = false) {
                LockLayer(lock, device, onUnlock = {}, onLeave = {}) {
                    val focus = remember { FocusRequester() }
                    var text by remember { mutableStateOf("") }
                    BasicTextField(text, { text = it }, Modifier.focusRequester(focus).semantics { contentDescription = "Note" })
                    LaunchedEffect(Unit) { focus.requestFocus() }
                }
            }
        }
        rule.onNodeWithContentDescription("Note").assertIsFocused()
        lock = LockState.LOCKED
        rule.waitForIdle()
        lock = LockState.OPEN
        rule.onNodeWithContentDescription("Note").assertIsNotFocused()
    }
}
