package com.moneybook.data.sync

import com.moneybook.BuildConfig
import com.moneybook.data.DeviceSettingsRepository
import com.moneybook.data.Seeder
import com.moneybook.data.SettingsRepository
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.shared.sync.LoginRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface LoginResult {
    /** Logged in and the first run happened (its failure, if any, is in `lastSyncError`). */
    data object Synced : LoginResult

    /** The server has data and this device has live entries: the screen asks merge, replace or cancel. */
    data object NeedsChoice : LoginResult

    /** Nothing was stored. */
    data class Failed(val error: SyncError) : LoginResult
}

/** Login (with merge or replace) and logout: the account flow behind the Sync screen. */
class SyncAccount(
    private val device: DeviceSettingsRepository,
    private val settings: SettingsRepository,
    private val db: MoneyBookDatabase,
    private val engine: SyncEngine,
    private val api: SyncApi,
    private val seeder: Seeder,
    private val deviceName: String,
) {
    private val pendingState = MutableStateFlow(false)

    /** True between [LoginResult.NeedsChoice] and merge/replace/cancel; in memory only (Ruling 8). */
    val pending: StateFlow<Boolean> = pendingState.asStateFlow()

    suspend fun login(serverUrl: String, apiKey: String): LoginResult {
        val url = normalizeUrl(serverUrl)
        if (!ServerUrl.isAllowed(url, BuildConfig.DEBUG)) return LoginResult.Failed(SyncError.InsecureUrl)
        // Logout keeps url and lastSyncAt (the key is never stored): the same server that already synced here (after a server restore or an
        // expired session) merges silently, nothing is lost. A login that was cancelled or failed has lastSyncAt 0, so it asks again.
        val stored = device.current()
        val sameAccount = stored.lastSyncAt > 0 && stored.serverUrl == url
        try {
            // Under the engine's mutex, so a running sync can't push into the new account (or out of the old one) halfway.
            engine.exclusive {
                val tokens = api.login(url, LoginRequest(apiKey.trim(), device.deviceId(), deviceName))
                pendingState.value = true // held from the moment the token is stored, so no background run pushes before the choice
                device.update {
                    it.copy(
                        serverUrl = url, refreshToken = tokens.refreshToken, syncCursor = 0, syncEpoch = "",
                        lastSyncError = "", lastSyncAt = 0,
                    )
                }
                engine.markAllDirtyUnlocked()
            }
        } catch (e: SyncError) {
            return LoginResult.Failed(e)
        }
        val serverHasData = try {
            engine.probeServer()
        } catch (e: SyncError) {
            logout() // signed in nowhere: the user retries the whole login
            return LoginResult.Failed(e)
        }
        if (!sameAccount && serverHasData && db.txnDao().count() > 0) return LoginResult.NeedsChoice
        pendingState.value = false
        engine.sync()
        return LoginResult.Synced
    }

    suspend fun merge(): SyncOutcome {
        pendingState.value = false
        return engine.sync()
    }

    suspend fun replace(): SyncOutcome {
        try {
            engine.wipeLocal()
            device.update { it.copy(syncCursor = 0, syncEpoch = "") }
            val outcome = engine.sync()
            // only writes when the pulled account had no groups (or the run failed and the app would sit empty)
            seeder.seedIfEmpty(settings.current().mainCurrency, stamp = 0)
            return outcome
        } finally {
            pendingState.value = false
        }
    }

    suspend fun cancelLogin() = logout()

    /** Best-effort server logout, then forget the tokens and the cursor; url and all local data stay. */
    suspend fun logout() {
        // After any running sync, so it can't end by writing an error into the signed-out state.
        engine.exclusive {
            try {
                api.logout()
            } catch (_: SyncError) {
                // the server forgets the device when the refresh token expires
            }
            device.update { it.copy(refreshToken = "", syncCursor = 0, syncEpoch = "", lastSyncError = "") }
        }
        pendingState.value = false
    }

    companion object {
        /** Trimmed, no trailing slashes, `https://` when no scheme was typed (`http://` for a bare Tailscale IPv4, with or without a port). */
        fun normalizeUrl(raw: String): String {
            val t = raw.trim().trimEnd('/')
            if (t.isEmpty() || "://" in t) return t
            return if (ServerUrl.isTailscale(t.substringBefore(':'))) "http://$t" else "https://$t"
        }
    }
}
