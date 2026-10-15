package com.moneybook.testutil

import com.moneybook.data.AppSettings
import com.moneybook.data.SettingsRepository
import com.moneybook.data.SettingsSync
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeSettingsRepository(initial: AppSettings = AppSettings(mainCurrency = "CZK")) : SettingsRepository {
    val state = MutableStateFlow(initial)
    val sync = MutableStateFlow(SettingsSync(0, false))
    override val settings: Flow<AppSettings> = state
    override val syncState: Flow<SettingsSync> = sync

    private fun stamp() {
        sync.value = SettingsSync(maxOf(System.currentTimeMillis(), sync.value.updatedAt + 1), true)
    }

    override suspend fun setMainCurrency(code: String) {
        state.value = state.value.copy(mainCurrency = code)
        stamp()
    }

    override suspend fun replace(value: AppSettings) {
        val changed = value.copy(style = state.value.style) != state.value
        state.value = value
        if (changed) stamp()
    }

    override suspend fun applyRemote(value: AppSettings, updatedAt: Long) {
        state.value = value.copy(style = state.value.style)
        sync.value = SettingsSync(updatedAt, false)
    }

    override suspend fun clearDirty(updatedAt: Long) {
        if (sync.value.updatedAt == updatedAt) sync.value = sync.value.copy(dirty = false)
    }

    override suspend fun markDirty() {
        sync.value = sync.value.copy(dirty = sync.value.updatedAt > 0)
    }

    override suspend fun resetForReplace(defaultCurrency: String) {
        state.value = AppSettings(mainCurrency = defaultCurrency, style = state.value.style)
        sync.value = SettingsSync(0, false)
    }
}
