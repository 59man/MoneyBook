package com.moneybook.testutil

import com.moneybook.data.DeviceSettings
import com.moneybook.data.DeviceSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeDeviceSettingsRepository(initial: DeviceSettings = DeviceSettings()) : DeviceSettingsRepository {
    val state = MutableStateFlow(initial)
    override val settings: Flow<DeviceSettings> = state
    override suspend fun update(change: (DeviceSettings) -> DeviceSettings) {
        state.value = change(state.value)
    }
}
