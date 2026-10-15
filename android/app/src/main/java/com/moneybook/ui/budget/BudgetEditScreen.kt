package com.moneybook.ui.budget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moneybook.data.BudgetRepository
import com.moneybook.data.SettingsRepository
import com.moneybook.data.TimeSource
import com.moneybook.domain.AmountInput
import com.moneybook.domain.Budgets
import com.moneybook.domain.Formats
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.ui.common.Currencies
import com.moneybook.ui.common.FilledButton
import com.moneybook.ui.common.FormRow
import com.moneybook.ui.common.Hairline
import com.moneybook.ui.common.MbTopBar
import com.moneybook.ui.common.TitleText
import com.moneybook.ui.common.ValueText
import com.moneybook.ui.common.appContainer
import com.moneybook.ui.common.money
import com.moneybook.ui.common.specOf
import com.moneybook.ui.edit.AmountKeypad
import com.moneybook.ui.edit.KeypadKey
import com.moneybook.ui.edit.applyTo
import com.moneybook.ui.theme.Mb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** [fresh]: the stored amount is shown and the next digit or dot replaces it, as on Realbyte. Keys and Save wait for [loaded]. */
data class BudgetEditState(
    val name: String = "", val label: String = "", val amount: AmountInput = AmountInput(), val fresh: Boolean = true, val loaded: Boolean = false,
)

class BudgetEditViewModel(
    private val budgets: BudgetRepository, private val settings: SettingsRepository, private val time: TimeSource,
    private val categoryId: String?, private val month: Int?,
) : ViewModel() {
    private val _state = MutableStateFlow(BudgetEditState())
    val state: StateFlow<BudgetEditState> = _state

    init {
        viewModelScope.launch {
            val item = budgets.observeOne(categoryId).first() ?: return@launch
            val spec = specOf(settings.current().mainCurrency)
            val amount = if (month == null) Budgets.defaultAmount(item.rows) else Budgets.amountFor(item.rows, month)
            val label = if (month == null) "Default Budget" else Formats.monthTitle(Budgets.yearMonth(month))
            _state.value = BudgetEditState(item.name, label, AmountInput.of(amount, spec), loaded = true)
        }
    }

    fun onKey(key: KeypadKey) = _state.update { s ->
        if (!s.loaded) return@update s
        val start = if (s.fresh && (key is KeypadKey.Digit || key == KeypadKey.Dot)) s.amount.copy(text = "") else s.amount
        if (key == KeypadKey.Minus) s else s.copy(amount = key.applyTo(start), fresh = false)
    }

    /** Stores the amount (empty = 0) for the month, or as the Default from next month on. */
    suspend fun save() {
        if (!_state.value.loaded) return
        val amount = (_state.value.amount.minorUnits() ?: 0).coerceAtLeast(0)
        if (month != null) {
            budgets.setMonth(categoryId, month, amount)
        } else {
            val s = settings.current()
            budgets.setDefault(categoryId, amount, Budgets.current(time.today(), s.monthStartDay, s.monthStartWeekend))
        }
    }
}

/** One month's amount, or the Default Budget (capture `rb-bmedit`): the label and amount, Save, and the keypad open from the start. */
@Composable
fun BudgetEditScreen(categoryId: String?, month: Int?, onBack: () -> Unit) {
    val c = appContainer()
    val vm: BudgetEditViewModel = viewModel(key = "budget-edit-$categoryId-$month") {
        BudgetEditViewModel(c.budgets, c.settings, c.time, categoryId, month)
    }
    val s by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val save: () -> Unit = { scope.launch { vm.save(); onBack() } }

    BudgetEditContent(s, month, Currencies.state.mainSpec, onBack, vm::onKey, save)
}

@Composable
fun BudgetEditContent(s: BudgetEditState, month: Int?, spec: CurrencySpec, onBack: () -> Unit, onKey: (KeypadKey) -> Unit, onSave: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Mb.colors.surface).systemBarsPadding()) {
        MbTopBar(onBack = onBack, title = { TitleText(s.name) })
        Hairline(px = 2)
        Column(Modifier.weight(1f)) {
            Spacer(Modifier.height(21.dp))
            FormRow(s.label, active = true) { ValueText(money(s.amount.minorUnits() ?: 0, spec)) }
            FilledButton("Save", Mb.colors.accent, Modifier.fillMaxWidth().padding(15.dp), onSave)
        }
        AmountKeypad(onKey, onDone = onSave, onClose = onBack, clearKey = true, title = if (month == null) "Default Budget" else "Budget")
    }
}
