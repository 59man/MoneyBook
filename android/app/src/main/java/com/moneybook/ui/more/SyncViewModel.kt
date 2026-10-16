package com.moneybook.ui.more

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moneybook.data.DeviceSettingsRepository
import com.moneybook.data.sync.LoginResult
import com.moneybook.data.sync.SyncAccount
import com.moneybook.data.sync.SyncEngine
import com.moneybook.data.sync.SyncError
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the Sync screen shows. While a login, merge or replace runs, or the merge/replace question is open, the
 * device is already signed in but the screen still shows the login form ([signedIn] false), so the account's
 * state never flashes before the user has chosen.
 */
data class SyncUiState(
    val signedIn: Boolean = false,
    val serverUrl: String = "",
    val lastSyncAt: Long = 0,
    /** The one error line: a failed login, or (signed in) the last run's error, or (signed out) an expired session. */
    val error: String? = null,
    val busy: Boolean = false,
    val syncing: Boolean = false,
    /** The "This device already has data" question is open. */
    val choice: Boolean = false,
)

private const val VPN_OFF_TEXT = "Turn on Tailscale to sync"
private const val FORBIDDEN_TEXT = "The server refused this network. Connect through Tailscale."
private const val INSECURE_URL_TEXT = "Use https, or a Tailscale 100.x address"

fun loginErrorText(e: SyncError): String = when {
    e is SyncError.InsecureUrl -> INSECURE_URL_TEXT
    e is SyncError.VpnOff -> VPN_OFF_TEXT
    e is SyncError.Forbidden -> FORBIDDEN_TEXT
    e is SyncError.Unauthorized -> "Wrong API key"
    e is SyncError.Offline -> "Can't reach the server"
    e is SyncError.Rejected && e.status == 429 -> "Too many attempts. Try again in a minute."
    e is SyncError.Server -> "Server error"
    else -> "Couldn't log in"
}

/** `DeviceSettings.lastSyncError` (a [SyncError.code]) as the screen's text; null for none. */
fun syncErrorText(code: String): String? = when (code) {
    "" -> null
    "offline" -> "Offline"
    "session_expired" -> "Session expired. Log in again."
    "server" -> "Server error"
    "insecure_url" -> INSECURE_URL_TEXT
    "vpn_off" -> VPN_OFF_TEXT
    "forbidden" -> FORBIDDEN_TEXT
    "rejected" -> "Update the app to sync this data"
    else -> "Sync failed"
}

class SyncViewModel(
    private val account: SyncAccount,
    private val engine: SyncEngine,
    device: DeviceSettingsRepository,
) : ViewModel() {
    private val working = MutableStateFlow(false)
    private val loginError = MutableStateFlow<String?>(null)

    val state: StateFlow<SyncUiState> = combine(device.settings, engine.running, account.pending, working, loginError) { d, running, pending, busy, failed ->
        val signedIn = d.signedIn && !busy && !pending
        SyncUiState(
            signedIn = signedIn, serverUrl = d.serverUrl, lastSyncAt = d.lastSyncAt,
            error = if (signedIn) syncErrorText(d.lastSyncError) else failed ?: syncErrorText("session_expired").takeIf { d.lastSyncError == "session_expired" },
            busy = busy, syncing = running, choice = pending && !busy,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SyncUiState())

    fun login(serverUrl: String, apiKey: String) {
        if (working.value) return
        loginError.value = null
        // NonCancellable: leaving the screen must not stop a login halfway, with the token stored but the question unanswered.
        launchWorking {
            val r = account.login(serverUrl, apiKey)
            if (r is LoginResult.Failed) loginError.value = loginErrorText(r.error)
        }
    }

    fun merge() = launchWorking { account.merge() }

    fun replace() = launchWorking { account.replace() }

    fun cancelLogin() {
        viewModelScope.launch { withContext(NonCancellable) { account.cancelLogin() } }
    }

    fun syncNow() {
        if (engine.running.value) return
        viewModelScope.launch { engine.sync() }
    }

    fun logout() {
        viewModelScope.launch { withContext(NonCancellable) { account.logout() } }
    }

    private fun launchWorking(block: suspend () -> Unit) {
        working.value = true
        viewModelScope.launch {
            try {
                withContext(NonCancellable) { block() }
            } finally {
                working.value = false
            }
        }
    }
}
