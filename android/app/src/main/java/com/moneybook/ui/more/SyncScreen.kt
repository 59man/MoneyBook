package com.moneybook.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moneybook.domain.Formats
import com.moneybook.ui.common.ChoiceDialog
import com.moneybook.ui.common.ConfirmDialog
import com.moneybook.ui.common.FilledButton
import com.moneybook.ui.common.FitText
import com.moneybook.ui.common.Hairline
import com.moneybook.ui.common.MbTopBar
import com.moneybook.ui.common.SettingsGroup
import com.moneybook.ui.common.SettingsRow
import com.moneybook.ui.common.TitleText
import com.moneybook.ui.common.appContainer
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType
import java.time.ZoneId

private const val CHOICE_HELP =
    "The account on the server has data too. Merge keeps both. Replace deletes this device's data, then downloads the account's."

@Composable
fun SyncScreen(onBack: () -> Unit) {
    val c = appContainer()
    val vm: SyncViewModel = viewModel { SyncViewModel(c.account, c.sync, c.device) }
    val state by vm.state.collectAsStateWithLifecycle()
    SyncContent(
        state, onBack, onLogin = vm::login, onSyncNow = vm::syncNow, onLogout = vm::logout,
        onMerge = vm::merge, onReplace = vm::replace, onCancelLogin = vm::cancelLogin,
    )
}

/** More → Sync: the login form, or (signed in) the account, last sync, Sync now and Log out. */
@Composable
fun SyncContent(
    state: SyncUiState,
    onBack: () -> Unit,
    onLogin: (url: String, apiKey: String) -> Unit,
    onSyncNow: () -> Unit,
    onLogout: () -> Unit,
    onMerge: () -> Unit,
    onReplace: () -> Unit,
    onCancelLogin: () -> Unit,
) {
    var askLogout by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Mb.colors.surface).systemBarsPadding().imePadding()) {
        MbTopBar(onBack = onBack, title = { TitleText("Sync") })
        Hairline(px = 2)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (state.signedIn) {
                SettingsGroup {
                    InfoRow("Server", state.serverUrl)
                    InfoRow("Last sync", if (state.lastSyncAt == 0L) "Never" else Formats.syncTime(state.lastSyncAt, ZoneId.systemDefault()))
                }
                ErrorLine(state.error)
                SettingsGroup {
                    SettingsRow(if (state.syncing) "Syncing…" else "Sync now", enabled = !state.syncing, onClick = onSyncNow)
                    SettingsRow("Log out") { askLogout = true }
                }
            } else {
                LoginForm(state, onLogin)
            }
        }
    }
    if (askLogout) {
        ConfirmDialog(
            "Log out", "This device stops syncing. Its data stays here, and the account keeps its data on the server.", "Log out",
            onConfirm = onLogout, onDismiss = { askLogout = false },
        )
    }
    // Closing the question any other way than a button counts as Cancel: nothing is left half logged in.
    if (state.choice) {
        ChoiceDialog(
            "This device already has data", listOf("Merge", "Replace this device's data", "Cancel"),
            onDismiss = onCancelLogin, description = CHOICE_HELP,
        ) { i -> when (i) { 0 -> onMerge(); 1 -> onReplace(); else -> onCancelLogin() } }
    }
}

@Composable
private fun LoginForm(state: SyncUiState, onLogin: (String, String) -> Unit) {
    var url by rememberSaveable(state.serverUrl) { mutableStateOf(state.serverUrl) }
    var key by remember { mutableStateOf("") } // never saved with the instance state
    var show by remember { mutableStateOf(false) }
    val submit = { if (!state.busy) onLogin(url, key) }
    Field("Server URL", url, { url = it }, KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false), hint = "http://100.x.y.z:8095")
    Field(
        "API key", key, { key = it },
        KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
        KeyboardActions(onDone = { submit() }), if (show) VisualTransformation.None else PasswordVisualTransformation(),
        trailing = {
            Text(
                if (show) "Hide" else "Show", style = MbType.formLabel, color = Mb.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { show = !show }.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        },
    )
    FilledButton(
        if (state.busy) "Logging in…" else "Log in", if (state.busy) Mb.colors.textSecondary else Mb.colors.accent,
        Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, top = 26.dp),
    ) { submit() }
    ErrorLine(state.error)
}

/** A labelled single-line field, drawn like Change Category's name field. */
@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    keyboard: KeyboardOptions,
    actions: KeyboardActions = KeyboardActions.Default,
    visual: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
    hint: String? = null,
) {
    Column(Modifier.padding(start = 10.dp, end = 20.dp, top = 15.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FitText(label, MbType.formLabel, Mb.colors.textSecondary, Modifier.padding(start = 10.dp).weight(1f))
            trailing?.invoke()
        }
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = MbType.formValue.copy(color = Mb.colors.textPrimary),
            cursorBrush = SolidColor(Mb.colors.accent),
            keyboardOptions = keyboard, keyboardActions = actions, visualTransformation = visual,
            modifier = Modifier.fillMaxWidth().heightIn(min = MbDimens.nameField).padding(start = 10.dp, top = 6.dp)
                .semantics { contentDescription = label },
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty() && hint != null) Text(hint, style = MbType.formValue, color = Mb.colors.textSecondary)
                    inner()
                }
            },
        )
        Hairline()
    }
}

/** A read-only line of the signed-in list: the title at the left, the value (shrunk when long) at the right. */
@Composable
private fun InfoRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = MbDimens.listRow).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        FitText(title, MbType.listItem, Mb.colors.textPrimary, Modifier.weight(1f), maxLines = 2)
        FitText(value, MbType.listItem, Mb.colors.textSecondary, Modifier.weight(2f), maxLines = 2, textAlign = TextAlign.End)
    }
}

/** Red because it is an error, not the expense color: Color Set B turns that green. */
@Composable
private fun ErrorLine(text: String?) {
    if (text == null) return
    Text(text, style = MbType.listItem, color = Mb.colors.danger, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp))
}
