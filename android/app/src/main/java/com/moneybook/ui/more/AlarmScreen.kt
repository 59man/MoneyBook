package com.moneybook.ui.more

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moneybook.R
import com.moneybook.data.DeviceSettings
import com.moneybook.ui.common.Hairline
import com.moneybook.ui.common.LocalAppLocked
import com.moneybook.ui.common.MbIcon
import com.moneybook.ui.common.MbTopBar
import com.moneybook.ui.common.SettingsRow
import com.moneybook.ui.common.TitleText
import com.moneybook.ui.common.appContainer
import com.moneybook.ui.common.rememberNotificationPermission
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType
import kotlinx.coroutines.launch

private const val HELP = "MoneyBook can remind you at your chosen time of the day to key in your data."

private fun hhmm(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)

@Composable
fun AlarmScreen(onBack: () -> Unit) {
    val c = appContainer()
    val device by c.device.settings.collectAsStateWithLifecycle(initialValue = DeviceSettings())
    val scope = rememberCoroutineScope()
    val permission = rememberNotificationPermission()
    val context = LocalContext.current
    AlarmContent(
        device.alarmOn, device.alarmMinutes,
        onToggle = { on ->
            if (!on) {
                scope.launch { c.device.update { it.copy(alarmOn = false) }; c.reminders.cancel() }
            } else {
                permission.request { granted ->
                    if (granted) {
                        scope.launch { c.device.update { it.copy(alarmOn = true) }; c.reminders.schedule(device.alarmMinutes) }
                    } else {
                        Toast.makeText(context, "Allow notifications for MoneyBook to get the reminder.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        },
        onTime = { m -> scope.launch { c.device.update { it.copy(alarmMinutes = m) }; if (device.alarmOn) c.reminders.schedule(m) } },
        onBack = onBack,
    )
}

/** Configuration → Alarm Setting (capture 56): help card, the Alarm switch, and while on the time. */
@Composable
fun AlarmContent(on: Boolean, minutes: Int, onToggle: (Boolean) -> Unit, onTime: (Int) -> Unit, onBack: () -> Unit) {
    var picking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Mb.colors.surface).systemBarsPadding()) {
        MbTopBar(onBack = onBack, title = { TitleText("Alarm Setting") })
        Hairline(px = 2)
        Row(Modifier.fillMaxWidth().background(Mb.colors.background).padding(horizontal = 20.dp, vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(66.dp).border(1.dp, Mb.colors.accent, CircleShape), contentAlignment = Alignment.Center) {
                MbIcon(R.drawable.ic_bell, null, tint = Mb.colors.accent, size = 26.dp)
            }
            Spacer(Modifier.width(18.dp))
            Text(HELP, style = MbType.helpText, color = Mb.colors.textSecondary)
        }
        Hairline()
        Row(
            Modifier.fillMaxWidth().heightIn(min = MbDimens.styleRow).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Alarm", style = MbType.dialogOption.copy(fontWeight = FontWeight.Bold), color = Mb.colors.textBody, modifier = Modifier.weight(1f))
            Switch(
                checked = on, onCheckedChange = onToggle, modifier = Modifier.semantics { contentDescription = "Alarm" },
                colors = SwitchDefaults.colors(checkedTrackColor = Mb.colors.accent, checkedThumbColor = Color.White),
            )
        }
        Hairline()
        if (on) SettingsRow("Time", value = hhmm(minutes)) { picking = true }
    }
    if (picking) AlarmTimeDialog(minutes, onPick = { onTime(it); picking = false }, onDismiss = { picking = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmTimeDialog(minutes: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    if (LocalAppLocked.current) return // a window of its own, above the lock screen
    val state = rememberTimePickerState(initialHour = minutes / 60, initialMinute = minutes % 60, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { TimePicker(state = state) },
    )
}
