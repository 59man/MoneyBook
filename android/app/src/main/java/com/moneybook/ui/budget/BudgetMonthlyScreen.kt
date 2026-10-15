package com.moneybook.ui.budget

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moneybook.R
import com.moneybook.data.BudgetRepository
import com.moneybook.data.SettingsRepository
import com.moneybook.data.TimeSource
import com.moneybook.domain.Budgets
import com.moneybook.domain.Formats
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.ui.common.ConfirmDialog
import com.moneybook.ui.common.Currencies
import com.moneybook.ui.common.FitText
import com.moneybook.ui.common.Hairline
import com.moneybook.ui.common.IconButtonBox
import com.moneybook.ui.common.MbTopBar
import com.moneybook.ui.common.MonthNavigator
import com.moneybook.ui.common.TitleText
import com.moneybook.ui.common.appContainer
import com.moneybook.ui.common.money
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType
import java.time.YearMonth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** [months] run Dec → Jan of [year]; [current] is the budget month of today; [gone] once the budget no longer exists. */
data class BudgetMonthlyState(
    val name: String = "",
    val year: Int,
    val defaultAmount: Long = 0,
    val months: List<Pair<YearMonth, Long>> = emptyList(),
    val current: YearMonth? = null,
    val gone: Boolean = false,
)

class BudgetMonthlyViewModel(
    private val budgets: BudgetRepository, settings: SettingsRepository, private val time: TimeSource, private val categoryId: String?,
) : ViewModel() {
    private val year = MutableStateFlow(time.today().year)

    val state: StateFlow<BudgetMonthlyState> = combine(budgets.observeOne(categoryId), year, settings.settings) { item, y, s ->
        if (item == null) return@combine BudgetMonthlyState(year = y, gone = true)
        val current = Budgets.yearMonth(Budgets.current(time.today(), s.monthStartDay, s.monthStartWeekend))
        BudgetMonthlyState(
            name = item.name, year = y, defaultAmount = Budgets.defaultAmount(item.rows),
            months = (12 downTo 1).map { m -> YearMonth.of(y, m).let { it to Budgets.amountFor(item.rows, Budgets.key(it)) } },
            current = current,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BudgetMonthlyState(year = time.today().year))

    fun previousYear() = year.update { it - 1 }
    fun nextYear() = year.update { it + 1 }
    fun delete() = viewModelScope.launch { budgets.delete(categoryId) }
}

@Composable
fun BudgetMonthlyScreen(categoryId: String?, onBack: () -> Unit, onOpen: (YearMonth?) -> Unit, onGone: () -> Unit = onBack) {
    val c = appContainer()
    val vm: BudgetMonthlyViewModel = viewModel(key = "budget-monthly-$categoryId") {
        BudgetMonthlyViewModel(c.budgets, c.settings, c.time, categoryId)
    }
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(s.gone) { if (s.gone) onGone() }
    BudgetMonthlyContent(s, Currencies.state.mainSpec, onBack, vm::previousYear, vm::nextYear, vm::delete, onOpen)
}

/** A budget's months (capture `rb-bmonthly`): the Default Budget, then Dec to Jan of the year with the current month marked. */
@Composable
fun BudgetMonthlyContent(
    s: BudgetMonthlyState,
    spec: CurrencySpec,
    onBack: () -> Unit,
    onPrevYear: () -> Unit,
    onNextYear: () -> Unit,
    onDelete: () -> Unit,
    onOpen: (YearMonth?) -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Mb.colors.background).systemBarsPadding()) {
        MbTopBar(
            onBack = onBack, title = { TitleText(s.name) },
            actions = {
                MonthNavigator(s.year.toString(), onPrevYear, onNextYear)
                IconButtonBox(R.drawable.ic_delete, "Delete", { confirm = true })
            },
        )
        Hairline(px = 2)
        BudgetHelpCard(
            AnnotatedString("You can set the budget settings for each month. If you change the default budget, it will be applied starting next month."),
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item {
                val bold = MbType.budgetDefault
                Row(
                    Modifier.fillMaxWidth().background(Mb.colors.surface).clickable { onOpen(null) }
                        .heightIn(min = MbDimens.budgetDefaultRow).padding(start = MbDimens.sidePadding, end = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FitText("Default Budget", bold, Mb.colors.textPrimary, Modifier.weight(1f))
                    FitText(money(s.defaultAmount, spec), bold, Mb.colors.textPrimary)
                }
                Hairline()
            }
            items(s.months, key = { it.first.toString() }) { (ym, amount) ->
                val tint = if (ym == s.current) Mb.colors.accent else Mb.colors.textSecondary
                Row(
                    Modifier.fillMaxWidth().background(Mb.colors.surface).clickable { onOpen(ym) }
                        .heightIn(min = MbDimens.listRow).padding(start = MbDimens.sidePadding, end = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier.width(60.dp).border(1.dp, if (ym == s.current) Mb.colors.accent else Mb.colors.divider, RoundedCornerShape(3.dp))
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) { FitText(Formats.monthName(ym), MbType.listItem, tint) }
                    Spacer(Modifier.weight(1f))
                    FitText(money(amount, spec), MbType.listItem, Mb.colors.textPrimary)
                }
                Hairline()
            }
        }
    }
    if (confirm) {
        ConfirmDialog("", "Are you sure you want to delete?", "Yes", "No", onConfirm = onDelete, onDismiss = { confirm = false })
    }
}
