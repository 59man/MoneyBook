package com.moneybook.ui.lock

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.moneybook.R
import com.moneybook.data.DeviceSettings
import com.moneybook.data.DeviceSettingsRepository
import com.moneybook.domain.Passcode
import com.moneybook.domain.PasscodeEntry
import com.moneybook.domain.PasscodeMode
import com.moneybook.domain.PasscodeRetry
import com.moneybook.ui.common.FitText
import com.moneybook.ui.common.IconButtonBox
import com.moneybook.ui.common.LocalAppLocked
import com.moneybook.ui.common.MbIcon
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * SET: Configuration → Passcode with none set. UNLOCK_SETTINGS: with one set, the current code opens the Passcode list.
 * CHANGE: the current code, then a new one twice.
 */
enum class PasscodePurpose { SET, CHANGE, UNLOCK_SETTINGS }

private const val FORGET_NOTE = "If you forget it, only clearing the app's data removes it."

/** Capture 63: the accent screen with a lock, the title, four dots and a 3×4 pad. [onBack] null hides the arrow (lock screen). */
@Composable
fun PasscodeContent(
    entry: PasscodeEntry,
    title: String?,
    note: String?,
    onBack: (() -> Unit)?,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
) {
    // It covers the app (the lock screen), so it takes every tap.
    Column(Modifier.fillMaxSize().background(Mb.colors.accent).pointerInput(Unit) {}.systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(MbDimens.topBar), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButtonBox(R.drawable.ic_arrow_back, "Back", onBack, tint = Color.White)
            if (title != null) Text(title, style = MbType.screenTitle, color = Color.White, modifier = Modifier.padding(start = if (onBack == null) 20.dp else 0.dp))
        }
        Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(40.dp))
            MbIcon(R.drawable.ic_lock, null, tint = Color.White, size = 56.dp)
            Spacer(Modifier.height(36.dp))
            FitText(entry.title, MbType.passcodeTitle, Color.White, Modifier.padding(horizontal = 24.dp), textAlign = TextAlign.Center)
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                repeat(PasscodeEntry.LENGTH) { i ->
                    val filled = i < entry.digits.length
                    Box(
                        Modifier.size(MbDimens.passcodeDot).clip(CircleShape).background(if (filled) Color.White else Color.Transparent)
                            .border(2.dp, Color.White, CircleShape),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            entry.error?.let { Text(it, style = MbType.listItem, color = Color.White) }
            note?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, style = MbType.summaryLabel, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
            }
        }
        listOf("123", "456", "789", " 0<").forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { key ->
                    val cell = Modifier.weight(1f).heightIn(min = MbDimens.passcodeKey)
                    when (key) {
                        ' ' -> Spacer(cell)
                        '<' -> Box(cell.clickable(onClick = onBackspace), contentAlignment = Alignment.Center) {
                            MbIcon(R.drawable.ic_backspace, "Backspace", tint = Color.White, size = 34.dp)
                        }
                        else -> Box(cell.clickable { onDigit(key) }, contentAlignment = Alignment.Center) {
                            Text(key.toString(), style = MbType.passcodeKey, color = Color.White)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** Set Passcode (twice), Change Passcode (the old one first) or the check before the Passcode list, then [onDone]. */
@Composable
fun PasscodeFlow(
    purpose: PasscodePurpose,
    device: DeviceSettingsRepository,
    onDone: () -> Unit,
    onBack: () -> Unit,
    clock: () -> Long = SystemClock::elapsedRealtime,
) {
    BackHandler(onBack = onBack)
    var checked by remember { mutableStateOf(false) }
    when {
        purpose == PasscodePurpose.UNLOCK_SETTINGS -> CheckPad(device, title = "Passcode", onBack = onBack, clock = clock, mayPrompt = true) { onDone() }
        // Realbyte offers no fingerprint when changing the code.
        purpose == PasscodePurpose.CHANGE && !checked -> CheckPad(device, title = "Change Passcode", onBack = onBack, clock = clock, mayPrompt = false) { checked = true }
        else -> SetPad(device, onDone, onBack)
    }
}

@Composable
private fun SetPad(device: DeviceSettingsRepository, onDone: () -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var entry by remember { mutableStateOf(PasscodeEntry(PasscodeMode.SET)) }
    PasscodeContent(
        entry, title = "Set Passcode", note = FORGET_NOTE, onBack = onBack,
        onDigit = { d ->
            if (entry.done != null) return@PasscodeContent
            entry = entry.press(d) { false }
            val code = entry.done ?: return@PasscodeContent
            scope.launch {
                val salt = Passcode.newSalt()
                device.update { it.copy(passcodeHash = Passcode.hash(code, salt), passcodeSalt = salt, wrongTries = 0, retryAt = null) }
                onDone()
            }
        },
        onBackspace = { entry = entry.backspace() },
    )
}

/** Drawn over the whole app while it is locked. Back leaves the app instead of reaching the screens behind. */
@Composable
fun LockScreen(
    device: DeviceSettingsRepository,
    onUnlock: () -> Unit,
    onLeave: () -> Unit,
    clock: () -> Long = SystemClock::elapsedRealtime,
) {
    BackHandler(onBack = onLeave)
    CheckPad(device, title = null, onBack = null, clock = clock, mayPrompt = true) { onUnlock() }
}

/**
 * The pad asking for the current passcode. After PasscodeRetry.MAX_WRONG wrong codes in a row it ignores digits and
 * counts down PasscodeRetry.WAIT_MS; the count and the wait are stored, so leaving the screen or the app keeps them.
 * [onRight] runs once, after the count is cleared, for a right code or, with [mayPrompt], a recognized biometric.
 * [mayPrompt] opens the biometric prompt each time the screen resumes (and when it first shows), unless Biometrics is
 * off, the phone can't authenticate or the wait runs. A miss or Cancel leaves the pad and never touches the count.
 */
@Composable
private fun CheckPad(
    device: DeviceSettingsRepository, title: String?, onBack: (() -> Unit)?, clock: () -> Long, mayPrompt: Boolean,
    onRight: suspend () -> Unit,
) {
    val saved by device.settings.collectAsState(initial = null)
    var entry by remember { mutableStateOf(PasscodeEntry(PasscodeMode.CHECK)) }
    var now by remember { mutableLongStateOf(clock()) }
    val retryAt = saved?.retryAt
    LaunchedEffect(retryAt) {
        while (true) {
            now = clock()
            if (PasscodeRetry.remaining(retryAt, now) == 0L) break
            delay(250)
        }
    }
    val wait = PasscodeRetry.remaining(retryAt, now)
    val scope = rememberCoroutineScope()
    var accepted by remember { mutableStateOf(false) }
    val accept: (DeviceSettings) -> Unit = { s ->
        if (!accepted) {
            accepted = true
            scope.launch {
                if (s.wrongTries != 0 || s.retryAt != null) device.update { it.copy(wrongTries = 0, retryAt = null) }
                onRight()
            }
        }
    }
    val gate = LocalBiometricGate.current
    val context = LocalContext.current
    if (mayPrompt) {
        // Keyed on the read being done, with the flag captured at composition: the first run (nothing read yet) must not prompt.
        val ready = saved != null
        // The settings pad stays composed behind the lock screen: only the lock screen (outside LocalAppLocked) may prompt then.
        val locked by rememberUpdatedState(LocalAppLocked.current)
        LifecycleResumeEffect(ready) {
            val s = saved
            if (ready && s != null && !locked && s.biometric && PasscodeRetry.remaining(s.retryAt, clock()) == 0L && gate.canAuthenticate(context)) {
                gate.prompt(context) { accept(s) }
            }
            onPauseOrDispose {}
        }
    }
    val shown = if (wait > 0) entry.copy(digits = "", error = "Too many wrong tries. Try again in ${(wait + 999) / 1000} s.") else entry
    PasscodeContent(
        shown, title = title, note = null, onBack = onBack,
        onDigit = { d ->
            val s = saved ?: return@PasscodeContent
            if (entry.done != null || PasscodeRetry.remaining(s.retryAt, clock()) > 0) return@PasscodeContent
            val full = entry.digits.length == PasscodeEntry.LENGTH - 1
            entry = entry.press(d) { code -> s.checks(code) }
            if (entry.done != null) accept(s)
            else if (full) {
                val at = clock()
                scope.launch {
                    device.update {
                        val (wrong, retry) = PasscodeRetry.afterWrong(it.wrongTries, at)
                        it.copy(wrongTries = wrong, retryAt = retry ?: it.retryAt)
                    }
                }
            }
        },
        onBackspace = { entry = entry.backspace() },
    )
}
