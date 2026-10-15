package com.moneybook

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.moneybook.data.ColorSet
import com.moneybook.data.StyleMode
import com.moneybook.ui.common.ListOptions
import com.moneybook.domain.CardExpenseMode
import com.moneybook.ui.common.LocalListOptions
import com.moneybook.ui.lock.LockLayer
import com.moneybook.ui.nav.MoneyBookNavHost
import com.moneybook.ui.theme.MoneyBookTheme
import kotlinx.coroutines.launch

/** A FragmentActivity because BiometricPrompt needs one. */
class MainActivity : FragmentActivity() {
    /** A quick add shortcut's screen (EXTRA_OPEN), until the nav host has opened it. */
    private val openRequest = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settingsFlow = (application as MoneyBookApp).container.settings.settings
        if (savedInstanceState == null) openRequest.value = intent?.getStringExtra(EXTRA_OPEN)
        setContent {
            val s by settingsFlow.collectAsState(initial = null)
            val dark = when (s?.style) {
                StyleMode.DARK -> true
                StyleMode.LIGHT -> false
                else -> isSystemInDarkTheme()
            }
            // Dark/Light Mode can differ from the system's, so the system bar icons follow the app's theme.
            val view = LocalView.current
            SideEffect {
                WindowCompat.getInsetsController(window, view).run {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            MoneyBookTheme(darkTheme = dark, swapIncomeExpense = s?.colorSet == ColorSet.B) {
                val options = ListOptions(
                    showTime = s?.timeInput?.onList == true, showDescription = s?.showDescription == true,
                    cardLumpSum = s?.cardExpenseMode == CardExpenseMode.LUMP_SUM, subcategory = s?.subcategory == true,
                )
                val container = (application as MoneyBookApp).container
                val lock by container.lock.state.collectAsState()
                CompositionLocalProvider(LocalListOptions provides options) {
                    LockLayer(lock, container.device, onUnlock = container.lock::unlock, onLeave = { moveTaskToBack(true) }) {
                        // A shortcut's screen opens only once unlocked: Search would otherwise take focus and the keyboard behind the lock.
                        val open = if (lock == LockState.OPEN) openRequest.value else null
                        MoneyBookNavHost(open = open, onOpened = { openRequest.value = null })
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val app = application as MoneyBookApp
        app.catchUpRepeats()
        lifecycleScope.launch { app.container.lock.onStart(SystemClock.elapsedRealtime()) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRequest.value = intent.getStringExtra(EXTRA_OPEN)
    }

    override fun onStop() {
        super.onStop()
        val lock = (application as MoneyBookApp).container.lock
        // A recreation (dark mode, font size) is not a trip away: with Request Passcode = Immediately it would lock.
        if (isChangingConfigurations) lock.expectReturn()
        lock.onStop(SystemClock.elapsedRealtime())
    }

    companion object {
        /** Quick add's shortcuts (and later deep links) open these screens. */
        const val EXTRA_OPEN = "open"
        const val OPEN_SEARCH = "search"
        const val OPEN_BOOKMARKS = "bookmarks"
        const val OPEN_ADD = "add"

        /** The quick add widgets' + and −: a new entry on Income or on Expense. */
        const val OPEN_ADD_INCOME = "add_income"
        const val OPEN_ADD_EXPENSE = "add_expense"
    }
}
