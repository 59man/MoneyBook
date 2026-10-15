package com.moneybook.ui.more

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moneybook.data.DeviceSettings
import com.moneybook.domain.LockDelay
import com.moneybook.ui.common.ChoiceDialog
import com.moneybook.ui.common.Hairline
import com.moneybook.ui.common.MbTopBar
import com.moneybook.ui.common.SettingsGroup
import com.moneybook.ui.common.SettingsRow
import com.moneybook.ui.common.SettingsSection
import com.moneybook.ui.common.SettingsSwitchRow
import com.moneybook.ui.common.TitleText
import com.moneybook.ui.common.appContainer
import com.moneybook.ui.lock.LocalBiometricGate
import com.moneybook.ui.theme.Mb
import kotlinx.coroutines.launch

/** Configuration → Passcode with a passcode set, once its code was checked. */
@Composable
fun PasscodeSettingsScreen(onBack: () -> Unit, onTurnedOff: () -> Unit, onChange: () -> Unit) {
    val c = appContainer()
    val device by c.device.settings.collectAsStateWithLifecycle(initialValue = DeviceSettings())
    val scope = rememberCoroutineScope()
    PasscodeSettingsContent(
        device, canAuthenticate = LocalBiometricGate.current.canAuthenticate(LocalContext.current), onBack = onBack, onChange = onChange,
        // The code was just checked to get here, so it turns off at once; the write finishes before the screen goes.
        onTurnOff = {
            scope.launch {
                c.device.update { it.copy(passcodeHash = null, passcodeSalt = null, wrongTries = 0, retryAt = null) }
                onTurnedOff()
            }
        },
        onBiometric = { on -> scope.launch { c.device.update { it.copy(biometric = on) } } },
        onDelay = { seconds -> scope.launch { c.device.update { it.copy(lockDelaySeconds = seconds) } } },
    )
}

/**
 * Realbyte's Passcode list: Turn off Passcode, Change Passcode, Biometrics (only when the phone can authenticate; named
 * Fingerprint below Android 10), then the Request Passcode section with its option dialog.
 */
@Composable
fun PasscodeSettingsContent(
    device: DeviceSettings,
    canAuthenticate: Boolean,
    onBack: () -> Unit,
    onTurnOff: () -> Unit,
    onChange: () -> Unit,
    onBiometric: (Boolean) -> Unit,
    onDelay: (Int) -> Unit,
) {
    var asking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Mb.colors.background).systemBarsPadding()) {
        MbTopBar(onBack = onBack, title = { TitleText("Passcode") })
        Hairline(px = 2)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SettingsGroup {
                SettingsRow("Turn off Passcode") { onTurnOff() }
                SettingsRow("Change Passcode") { onChange() }
                if (canAuthenticate) {
                    SettingsSwitchRow(if (Build.VERSION.SDK_INT >= 29) "Biometrics" else "Fingerprint", device.biometric, onBiometric)
                }
            }
            SettingsSection("Request Passcode")
            SettingsGroup { SettingsRow("Request Passcode", value = LockDelay.label(device.lockDelaySeconds)) { asking = true } }
        }
    }
    if (asking) {
        ChoiceDialog(
            "Request Passcode", LockDelay.options.map { it.second }, selected = LockDelay.options.indexOfFirst { it.first == device.lockDelaySeconds }.takeIf { it >= 0 } ?: 0,
            onDismiss = { asking = false },
        ) { i ->
            onDelay(LockDelay.options[i].first)
            asking = false
        }
    }
}
