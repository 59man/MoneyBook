package com.moneybook.ui.budget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moneybook.R
import com.moneybook.data.BudgetRepository
import com.moneybook.data.SettingsRepository
import com.moneybook.data.TimeSource
import com.moneybook.domain.Budgets
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.ui.common.Currencies
import com.moneybook.ui.common.EditPencil
import com.moneybook.ui.common.EmptyState
import com.moneybook.ui.common.FitText
import com.moneybook.ui.common.Hairline
import com.moneybook.ui.common.IconButtonBox
import com.moneybook.ui.common.MbTopBar
import com.moneybook.ui.common.TitleText
import com.moneybook.ui.common.appContainer
import com.moneybook.ui.common.money
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** A Budget Setting row: [categoryId] null is the Total Budget; [amount] is this month's. */
data class BudgetSettingItem(val categoryId: String?, val name: String, val amount: Long)

class BudgetSettingViewModel(budgets: BudgetRepository, settings: SettingsRepository, time: TimeSource) : ViewModel() {
    val state: StateFlow<List<BudgetSettingItem>> = combine(budgets.observe(), settings.settings) { items, s ->
        val month = Budgets.current(time.today(), s.monthStartDay, s.monthStartWeekend)
        items.map { BudgetSettingItem(it.categoryId, it.name, Budgets.amountFor(it.rows, month)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@Composable
fun BudgetSettingScreen(onBack: () -> Unit, onAdd: () -> Unit, onOpen: (String?) -> Unit) {
    val c = appContainer()
    val vm: BudgetSettingViewModel = viewModel { BudgetSettingViewModel(c.budgets, c.settings, c.time) }
    val items by vm.state.collectAsStateWithLifecycle()
    BudgetSettingContent(items, Currencies.state.mainSpec, onBack, onAdd, onOpen)
}

/** Configuration → Budget Setting (capture 42, `rb-blist2`): help card, then each budget's amount this month and a pencil. */
@Composable
fun BudgetSettingContent(items: List<BudgetSettingItem>, spec: CurrencySpec, onBack: () -> Unit, onAdd: () -> Unit, onOpen: (String?) -> Unit) {
    Column(Modifier.fillMaxSize().background(Mb.colors.background).systemBarsPadding()) {
        MbTopBar(onBack = onBack, title = { TitleText("Budget") }, actions = { IconButtonBox(R.drawable.ic_add, "Add", onAdd) })
        Hairline(px = 2)
        BudgetHelpCard(budgetListHelp(Mb.colors.textPrimary))
        if (items.isEmpty()) {
            EmptyState(Modifier.weight(1f))
            return@Column
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(items, key = { it.categoryId ?: "total" }) { item ->
                Row(
                    Modifier.fillMaxWidth().background(Mb.colors.surface).clickable { onOpen(item.categoryId) }
                        .heightIn(min = MbDimens.listRow).padding(start = MbDimens.sidePadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FitText(item.name, MbType.listItem, Mb.colors.textPrimary, Modifier.weight(1f))
                    FitText(money(item.amount, spec), MbType.listItem, Mb.colors.textPrimary)
                    EditPencil("Edit ${item.name}") { onOpen(item.categoryId) }
                }
                Hairline()
            }
        }
    }
}
