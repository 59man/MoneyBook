package com.moneybook.ui.budget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.moneybook.data.CategoryRepository
import com.moneybook.data.SettingsRepository
import com.moneybook.data.TimeSource
import com.moneybook.domain.AmountInput
import com.moneybook.domain.Budgets
import com.moneybook.shared.model.CategoryType
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
import com.moneybook.ui.edit.GridPickerPanel
import com.moneybook.ui.edit.KeypadKey
import com.moneybook.ui.edit.applyTo
import com.moneybook.ui.theme.Mb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The grid's "· All" id: the Total Budget. */
const val ALL_ID = "all"

enum class BudgetPanel { NONE, CATEGORY, AMOUNT }

data class BudgetAddState(
    val categories: List<Pair<String, String>> = emptyList(),
    val categoryId: String? = null,
    val total: Boolean = false,
    val categoryName: String = "",
    val amount: AmountInput = AmountInput(),
    val panel: BudgetPanel = BudgetPanel.CATEGORY,
)

class BudgetAddViewModel(
    private val budgets: BudgetRepository,
    categories: CategoryRepository,
    private val settings: SettingsRepository,
    private val time: TimeSource,
    total: Boolean,
) : ViewModel() {
    private val _state = MutableStateFlow(
        if (total) BudgetAddState(total = true, categoryName = ALL_NAME, panel = BudgetPanel.AMOUNT) else BudgetAddState(),
    )
    val state: StateFlow<BudgetAddState> = _state

    init {
        viewModelScope.launch {
            val spec = specOf(settings.current().mainCurrency)
            val grid = listOf(ALL_ID to ALL_NAME) + categories.top(CategoryType.EXPENSE).filter { !it.hidden }.map { it.id to it.name }
            _state.update { it.copy(categories = grid, amount = it.amount.withCurrency(spec)) }
        }
    }

    fun pickCategory(id: String) = _state.update { s ->
        val name = if (id == ALL_ID) ALL_NAME else s.categories.firstOrNull { it.first == id }?.second.orEmpty()
        s.copy(total = id == ALL_ID, categoryId = id.takeIf { it != ALL_ID }, categoryName = name, panel = BudgetPanel.AMOUNT)
    }

    fun onKey(key: KeypadKey) = _state.update { s -> if (key == KeypadKey.Minus) s else s.copy(amount = key.applyTo(s.amount)) }
    fun openCategory() = _state.update { it.copy(panel = BudgetPanel.CATEGORY) }
    fun openAmount() = _state.update { it.copy(panel = BudgetPanel.AMOUNT) }
    fun closePanel() = _state.update { it.copy(panel = BudgetPanel.NONE) }

    /** Saves and returns true; false (nothing saved) without a category or with an amount of 0. */
    suspend fun save(): Boolean {
        val s = _state.value
        val st = settings.current()
        val amount = s.amount.withCurrency(specOf(st.mainCurrency)).minorUnits() ?: return false
        if ((!s.total && s.categoryId == null) || amount <= 0) return false
        budgets.add(if (s.total) null else s.categoryId, amount, Budgets.current(time.today(), st.monthStartDay, st.monthStartWeekend))
        return true
    }

    private companion object {
        const val ALL_NAME = "· All"
    }
}

/** Budget Setting → + (capture 43): Category and Amount rows, a Save button and the grid or keypad below. */
@Composable
fun BudgetAddScreen(total: Boolean, onBack: () -> Unit) {
    val c = appContainer()
    val vm: BudgetAddViewModel = viewModel { BudgetAddViewModel(c.budgets, c.categories, c.settings, c.time, total) }
    val s by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val save: () -> Unit = { scope.launch { if (vm.save()) onBack() } }
    BudgetAddContent(s, Currencies.state.mainSpec, onBack, vm::openCategory, vm::openAmount, vm::pickCategory, vm::closePanel, vm::onKey, save)
}

@Composable
fun BudgetAddContent(
    s: BudgetAddState, spec: CurrencySpec, onBack: () -> Unit, onCategory: () -> Unit, onAmount: () -> Unit,
    onPick: (String) -> Unit, onClose: () -> Unit, onKey: (KeypadKey) -> Unit, onSave: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Mb.colors.surface).systemBarsPadding()) {
        MbTopBar(onBack = onBack, title = { TitleText("Add Budget") })
        Hairline(px = 2)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(21.dp))
            FormRow("Category", active = s.panel == BudgetPanel.CATEGORY, onClick = onCategory) { ValueText(s.categoryName) }
            FormRow("Amount", active = s.panel == BudgetPanel.AMOUNT, onClick = onAmount) {
                ValueText(money(s.amount.minorUnits() ?: 0, spec))
            }
            FilledButton("Save", Mb.colors.accent, Modifier.fillMaxWidth().padding(15.dp), onSave)
        }
        when (s.panel) {
            BudgetPanel.NONE -> Unit
            BudgetPanel.CATEGORY -> GridPickerPanel("Category", s.categories, onPick, onClose)
            BudgetPanel.AMOUNT -> AmountKeypad(onKey, onDone = onSave, onClose = onClose, clearKey = true, title = "Amount")
        }
    }
}
