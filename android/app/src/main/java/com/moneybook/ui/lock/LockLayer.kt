package com.moneybook.ui.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.moneybook.LockState
import com.moneybook.data.DeviceSettingsRepository
import com.moneybook.ui.common.LocalAppLocked
import com.moneybook.ui.theme.Mb

/**
 * The app's [content] under the passcode lock. While not OPEN the content stays composed (its state survives) but
 * is hidden from accessibility services, loses the focus and keyboard, and keeps its dialogs closed
 * ([LocalAppLocked]); CHECKING covers it with the background, LOCKED with the lock screen.
 */
@Composable
fun LockLayer(
    lock: LockState,
    device: DeviceSettingsRepository,
    onUnlock: () -> Unit,
    onLeave: () -> Unit,
    content: @Composable () -> Unit,
) {
    val open = lock == LockState.OPEN
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(open) {
        if (!open) {
            focus.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    Box(Modifier.fillMaxSize()) {
        // Locked, the content is a focus group that refuses focus: after clearFocus Compose would otherwise move it
        // straight back into the first text field behind the lock.
        val behind = if (open) {
            Modifier.fillMaxSize()
        } else {
            Modifier.fillMaxSize().clearAndSetSemantics {}.focusProperties { onEnter = { cancelFocusChange() } }.focusGroup()
        }
        Box(behind) { CompositionLocalProvider(LocalAppLocked provides !open) { content() } }
        when (lock) {
            // Nothing of the app shows until the device settings say whether it is locked.
            LockState.CHECKING -> Box(Modifier.fillMaxSize().background(Mb.colors.background))
            LockState.LOCKED -> LockScreen(device, onUnlock = onUnlock, onLeave = onLeave)
            LockState.OPEN -> Unit
        }
    }
}
