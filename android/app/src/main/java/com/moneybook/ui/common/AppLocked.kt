package com.moneybook.ui.common

import androidx.compose.runtime.compositionLocalOf

/**
 * True while the passcode lock (or its start-up check) covers the app. Dialogs and popups open in windows of their
 * own, above the lock screen, so every one of them must stay hidden while this is true; their state survives, and
 * they show again once unlocked.
 */
val LocalAppLocked = compositionLocalOf { false }
