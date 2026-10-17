package com.moneybook.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.moneybook.domain.Passcode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

/** This phone's own settings: never backed up, synced or reset (Passcode, Alarm Setting, Quick add, Widget Settings). */
data class DeviceSettings(
    val passcodeHash: String? = null,
    val passcodeSalt: String? = null,
    val alarmOn: Boolean = false,
    /** Minutes after midnight; Realbyte's default reminder is in the evening. */
    val alarmMinutes: Int = 21 * 60,
    val quickAdd: Boolean = false,
    /** Wrong passcodes in a row, and when the pad may be used again (elapsedRealtime); see PasscodeRetry. */
    val wrongTries: Int = 0,
    val retryAt: Long? = null,
    /** Widget Settings: the summary widget's Background Color and Background Opacity (0–100 %). */
    val widgetBackground: StyleMode = StyleMode.SYSTEM,
    val widgetOpacity: Int = 100,
    /** Passcode → Biometrics (Realbyte's useFingerPrint, default on); only used where the phone can authenticate. */
    val biometric: Boolean = true,
    /** Passcode → Request Passcode: seconds in the background before the lock screen shows; 0 is Immediately. */
    val lockDelaySeconds: Int = 0,
    /** The entry form's account picker: by account group (two panes) instead of one grid (Realbyte's assetGroupFoldInputView). */
    val accountsGrouped: Boolean = false,
    /** Sync identity and cursor (M4); the access token lives only in memory, inside HttpSyncApi. */
    val deviceId: String = "",
    val serverUrl: String = "",
    val refreshToken: String = "",
    val syncCursor: Long = 0,
    /** The server's data epoch from the last response; "" = none yet. */
    val syncEpoch: String = "",
    /** Epoch ms of the last finished run, 0 = never. */
    val lastSyncAt: Long = 0,
    /** A SyncError.code, "" = none. */
    val lastSyncError: String = "",
) {
    val signedIn: Boolean get() = refreshToken.isNotEmpty()
    val passcodeSet: Boolean get() = passcodeHash != null && passcodeSalt != null

    fun checks(code: String): Boolean = passcodeHash != null && passcodeSalt != null && Passcode.matches(code, passcodeHash, passcodeSalt)
}

interface DeviceSettingsRepository {
    val settings: Flow<DeviceSettings>
    suspend fun current(): DeviceSettings = settings.first()
    suspend fun update(change: (DeviceSettings) -> DeviceSettings)

    /** This phone's sync id: a random UUID created once, in the same edit that stores it. */
    suspend fun deviceId(): String {
        update { if (it.deviceId.isEmpty()) it.copy(deviceId = UUID.randomUUID().toString()) else it }
        return current().deviceId
    }
}

class DataStoreDeviceSettingsRepository(private val store: DataStore<Preferences>) : DeviceSettingsRepository {
    private val passcodeHash = stringPreferencesKey("passcode_hash")
    private val passcodeSalt = stringPreferencesKey("passcode_salt")
    private val alarmOn = booleanPreferencesKey("alarm_on")
    private val alarmMinutes = intPreferencesKey("alarm_minutes")
    private val quickAdd = booleanPreferencesKey("quick_add")
    private val wrongTries = intPreferencesKey("wrong_tries")
    private val retryAt = longPreferencesKey("retry_at")
    private val widgetBackground = stringPreferencesKey("widget_background")
    private val widgetOpacity = intPreferencesKey("widget_opacity")
    private val biometric = booleanPreferencesKey("biometric")
    private val lockDelaySeconds = intPreferencesKey("lock_delay_seconds")
    private val accountsGrouped = booleanPreferencesKey("accounts_grouped")
    private val deviceId = stringPreferencesKey("sync_device_id")
    private val serverUrl = stringPreferencesKey("sync_server_url")
    private val refreshToken = stringPreferencesKey("sync_refresh_token")
    private val syncCursor = longPreferencesKey("sync_cursor")
    private val syncEpoch = stringPreferencesKey("sync_epoch")
    private val lastSyncAt = longPreferencesKey("sync_last_at")
    private val lastSyncError = stringPreferencesKey("sync_last_error")

    override val settings: Flow<DeviceSettings> = store.data.map(::read)

    private fun read(p: Preferences) = DeviceSettings(
        passcodeHash = p[passcodeHash], passcodeSalt = p[passcodeSalt], alarmOn = p[alarmOn] ?: false,
        alarmMinutes = p[alarmMinutes] ?: 21 * 60, quickAdd = p[quickAdd] ?: false,
        wrongTries = p[wrongTries] ?: 0, retryAt = p[retryAt],
        widgetBackground = StyleMode.entries.firstOrNull { it.name == p[widgetBackground] } ?: StyleMode.SYSTEM,
        widgetOpacity = p[widgetOpacity] ?: 100,
        biometric = p[biometric] ?: true, lockDelaySeconds = p[lockDelaySeconds] ?: 0,
        accountsGrouped = p[accountsGrouped] ?: false,
        deviceId = p[deviceId] ?: "", serverUrl = p[serverUrl] ?: "",
        refreshToken = p[refreshToken] ?: "", syncCursor = p[syncCursor] ?: 0, syncEpoch = p[syncEpoch] ?: "",
        lastSyncAt = p[lastSyncAt] ?: 0, lastSyncError = p[lastSyncError] ?: "",
    )

    override suspend fun update(change: (DeviceSettings) -> DeviceSettings) {
        store.edit { p ->
            val new = change(read(p))
            if (new.passcodeHash == null) p.remove(passcodeHash) else p[passcodeHash] = new.passcodeHash
            if (new.passcodeSalt == null) p.remove(passcodeSalt) else p[passcodeSalt] = new.passcodeSalt
            p[alarmOn] = new.alarmOn
            p[alarmMinutes] = new.alarmMinutes
            p[quickAdd] = new.quickAdd
            p[wrongTries] = new.wrongTries
            if (new.retryAt == null) p.remove(retryAt) else p[retryAt] = new.retryAt
            p[widgetBackground] = new.widgetBackground.name
            p[widgetOpacity] = new.widgetOpacity
            p[biometric] = new.biometric
            p[lockDelaySeconds] = new.lockDelaySeconds
            p[accountsGrouped] = new.accountsGrouped
            p[deviceId] = new.deviceId
            p[serverUrl] = new.serverUrl
            p[refreshToken] = new.refreshToken
            p[syncCursor] = new.syncCursor
            p[syncEpoch] = new.syncEpoch
            p[lastSyncAt] = new.lastSyncAt
            p[lastSyncError] = new.lastSyncError
        }
    }
}
