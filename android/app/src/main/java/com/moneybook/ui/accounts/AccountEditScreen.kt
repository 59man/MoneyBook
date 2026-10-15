package com.moneybook.ui.accounts

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moneybook.R
import com.moneybook.data.db.AccountGroupEntity
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.ui.common.ConfirmDialog
import com.moneybook.ui.common.FilledButton
import com.moneybook.ui.common.FitText
import com.moneybook.ui.common.FormRow
import com.moneybook.ui.common.IconButtonBox
import com.moneybook.ui.common.LocalAppLocked
import com.moneybook.ui.common.MbIcon
import com.moneybook.ui.common.MbTopBar
import com.moneybook.ui.common.TitleText
import com.moneybook.ui.common.ValueText
import com.moneybook.ui.common.appContainer
import com.moneybook.ui.common.money
import com.moneybook.ui.edit.AmountKeypad
import com.moneybook.ui.edit.DayKeypad
import com.moneybook.ui.edit.GridPickerPanel
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType

@Composable
fun AccountEditScreen(accountId: String?, onClose: () -> Unit, onDeleted: () -> Unit, onAddCurrency: (() -> Unit)? = null) {
    val c = appContainer()
    val vm: AccountEditViewModel = viewModel { AccountEditViewModel(accountId, c.accounts, c.settings, c.time, c.currencies) }
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(vm) {
        vm.events.collect { e ->
            when (e) {
                is AccountEditEvent.Error -> Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
                AccountEditEvent.Saved -> onClose()
                AccountEditEvent.Deleted -> onDeleted()
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Mb.colors.surface).systemBarsPadding().imePadding()) {
        MbTopBar(
            onBack = onClose,
            title = { TitleText(if (s.isNew) "Add Account" else "Account Info") },
            actions = { if (!s.isNew) IconButtonBox(R.drawable.ic_delete, "Delete", { confirmDelete = true }) },
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(21.dp))
            FormRow("Group", onClick = { focus.clearFocus(); vm.openGroupPicker() }) { ValueText(s.groupName) }
            FormRow("Name") { TextInput(s.name, vm::setName, "Name", onFocus = vm::closePanel) }
            FormRow("Amount", active = s.keypadOpen, onClick = { focus.clearFocus(); vm.openKeypad() }) {
                ValueText(s.amount.minorUnits()?.let { money(it, s.currency) } ?: money(0, s.currency))
            }
            FormRow("Currency") {
                CurrencyChips(s.currencies, s.currency.code, onPick = { focus.clearFocus(); vm.closePanel(); vm.pickCurrency(it) }, onAdd = onAddCurrency)
            }
            if (s.isCard) {
                FormRow("Payment", active = s.panel == AccountPanel.PAYMENT_ACCOUNT, onClick = { focus.clearFocus(); vm.openPaymentAccount() }) {
                    ValueText(s.paymentAccountName)
                }
            }
            FormRow("Description") { TextInput(s.description, vm::setDescription, "Description", onFocus = vm::closePanel) }
            s.preview?.let { preview ->
                Spacer(Modifier.fillMaxWidth().height(20.dp).background(Mb.colors.background))
                FormRow("Settlement\nDate", active = s.panel == AccountPanel.SETTLEMENT, labelLines = 2, onClick = { focus.clearFocus(); vm.openSettlement() }) {
                    ValueText("Every ${s.statementDay.value}")
                }
                FormRow("Payment\nDate", active = s.panel == AccountPanel.PAYMENT_DAY, labelLines = 2, onClick = { focus.clearFocus(); vm.openPaymentDay() }) {
                    ValueText("Every ${s.paymentDay.value}")
                }
                CardPreviewBox(preview)
            }
            Spacer(Modifier.fillMaxWidth().height(20.dp).background(if (s.isCard) Mb.colors.background else Mb.colors.surface))
            SwitchRow("Include in totals", s.includeInTotals, vm::setIncludeInTotals)
            SwitchRow("Hide", s.hidden, vm::setHidden)
            FilledButton("Save", Mb.colors.accent, Modifier.fillMaxWidth().padding(15.dp)) { vm.save() }
        }
        when (s.panel) {
            AccountPanel.NONE -> Unit
            AccountPanel.AMOUNT -> AmountKeypad(onKey = vm::onKey, onDone = vm::closePanel, onClose = vm::closePanel)
            AccountPanel.SETTLEMENT -> DayKeypad("Settlement", vm::onKey, onDone = vm::closePanel, onClose = vm::closePanel)
            AccountPanel.PAYMENT_DAY -> DayKeypad("Payment", vm::onKey, onDone = vm::closePanel, onClose = vm::closePanel)
            AccountPanel.PAYMENT_ACCOUNT -> GridPickerPanel("Accounts", s.paymentChoices.map { it.id to it.name }, vm::pickPaymentAccount, vm::closePanel)
        }
    }

    if (s.loaded && s.showGroupPicker) {
        GroupPickerDialog(s.groups, onPick = vm::pickGroup, onDismiss = { if (s.groupId == null) onClose() else vm.dismissGroupPicker() })
    }
    if (s.pendingCurrency != null) {
        ConfirmDialog("Notice", CURRENCY_NOTICE, "YES", onConfirm = vm::confirmCurrency, onDismiss = vm::dismissCurrency)
    }
    if (confirmDelete) {
        ConfirmDialog(
            "Delete", "Are you sure you want to delete?", "Yes", "No",
            onConfirm = vm::delete, onDismiss = { confirmDelete = false },
        )
    }
}

/** Realbyte's [Notice] before an account (or the main currency) changes currency. */
internal const val CURRENCY_NOTICE = "Changing the currency will affect the entry made before. The recent exchange rate of " +
    "changed-currency will apply to the exchange rate in a lump. Are you sure you want to change the currency for this account?"

/** Account Info → Currency: one outlined chip per currency (the chosen one in the accent color), then + to add one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CurrencyChips(specs: List<CurrencySpec>, selected: String, onPick: (String) -> Unit, onAdd: (() -> Unit)?) {
    FlowRow(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        specs.forEach { spec ->
            val on = spec.code == selected
            val shape = RoundedCornerShape(6.dp)
            Box(
                Modifier.heightIn(min = 34.dp).widthIn(min = 34.dp).clip(shape)
                    .background(if (on) Mb.colors.accent.copy(alpha = 0.08f) else Mb.colors.surface)
                    .border(1.dp, if (on) Mb.colors.accent else Mb.colors.textSecondary, shape)
                    .clickable { onPick(spec.code) }.padding(horizontal = 8.dp).semantics { contentDescription = spec.code },
                contentAlignment = Alignment.Center,
            ) {
                Text(spec.symbol, style = MbType.formValue, color = if (on) Mb.colors.accent else Mb.colors.textPrimary)
            }
        }
        if (onAdd != null) {
            val shape = RoundedCornerShape(6.dp)
            Box(
                Modifier.size(34.dp).clip(shape).border(1.dp, Mb.colors.textPrimary, shape).clickable(onClick = onAdd),
                contentAlignment = Alignment.Center,
            ) { MbIcon(R.drawable.ic_add, "Add currency", tint = Mb.colors.textPrimary, size = 20.dp) }
        }
    }
}

/** Capture 35: the two statement periods for today, in a rounded outlined box. */
@Composable
private fun CardPreviewBox(preview: CardPreview) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 21.dp)
            .border(1.dp, Mb.colors.divider, RoundedCornerShape(6.dp)).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PreviewLine("Balance Payable", preview.payable)
        PreviewLine("Outst. Balance", preview.outstanding)
    }
}

@Composable
private fun PreviewLine(label: String, period: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FitText(label, MbType.formLabel, Mb.colors.textTertiary, Modifier.weight(1f))
        FitText(period, MbType.rowAccount, Mb.colors.textPrimary, textAlign = TextAlign.End)
    }
}

@Composable
private fun TextInput(value: String, onChange: (String) -> Unit, description: String, onFocus: () -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = MbType.formValue.copy(color = Mb.colors.textPrimary),
        cursorBrush = SolidColor(Mb.colors.accent),
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocus() }.semantics { contentDescription = description },
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = MbDimens.listRow).padding(horizontal = 17.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MbType.listItem, color = Mb.colors.textBody, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = Mb.colors.accent))
    }
}

@Composable
private fun GroupPickerDialog(groups: List<AccountGroupEntity>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    if (LocalAppLocked.current) return // a window of its own, above the lock screen
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = { Text("Account Group") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                groups.forEach { g ->
                    Text(
                        g.name, style = MbType.listItem, color = Mb.colors.textPrimary,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(g.id) }.padding(vertical = 12.dp),
                    )
                }
            }
        },
    )
}
