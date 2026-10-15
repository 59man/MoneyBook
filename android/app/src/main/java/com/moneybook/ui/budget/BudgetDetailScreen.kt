package com.moneybook.ui.budget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moneybook.R
import com.moneybook.data.BudgetRepository
import com.moneybook.data.SettingsRepository
import com.moneybook.data.TimeSource
import com.moneybook.data.TxnRepository
import com.moneybook.domain.BudgetYearPoint
import com.moneybook.domain.Budgets
import com.moneybook.domain.CardExpenseMode
import com.moneybook.domain.DayGroup
import com.moneybook.domain.Formats
import com.moneybook.domain.Periods
import com.moneybook.domain.budgetYear
import com.moneybook.domain.groupByDay
import com.moneybook.domain.localDate
import com.moneybook.shared.model.TxnType
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.ui.common.Currencies
import com.moneybook.ui.common.Hairline
import com.moneybook.ui.common.IconButtonBox
import com.moneybook.ui.common.MbTopBar
import com.moneybook.ui.common.MonthNavigator
import com.moneybook.ui.common.SummaryBar
import com.moneybook.ui.common.SummaryColor
import com.moneybook.ui.common.SummaryItem
import com.moneybook.ui.common.TitleText
import com.moneybook.ui.common.appContainer
import com.moneybook.ui.common.charts.BudgetChart
import com.moneybook.ui.common.money
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.trans.DailyTab
import java.time.YearMonth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** [month] is the Trans month shown; [gone] once the budget no longer exists. */
data class BudgetDetailState(
    val name: String = "",
    val month: YearMonth,
    val budget: Long = 0,
    val used: Long = 0,
    val points: List<BudgetYearPoint> = emptyList(),
    val days: List<DayGroup> = emptyList(),
    val gone: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class BudgetDetailViewModel(
    txns: TxnRepository, budgets: BudgetRepository, settings: SettingsRepository, @Suppress("UNUSED_PARAMETER") time: TimeSource,
    private val categoryId: String, start: YearMonth,
) : ViewModel() {
    private val month = MutableStateFlow(start)

    val state: StateFlow<BudgetDetailState> = combine(month, settings.settings, budgets.observeOne(categoryId)) { m, s, item -> Triple(m, s, item) }
        .flatMapLatest { (m, s, item) ->
            if (item == null) return@flatMapLatest flowOf(BudgetDetailState(month = m, gone = true))
            val year = Periods.year(m.year, s.monthStartDay, s.monthStartWeekend)
            val period = Periods.month(m, s.monthStartDay, s.monthStartWeekend)
            txns.observeRange(year).map { rows ->
                val inMonth = rows.filter {
                    it.type == TxnType.EXPENSE && (it.parentCategoryId ?: it.categoryId) == categoryId && period.contains(it.localDate())
                }
                BudgetDetailState(
                    name = item.name, month = m,
                    budget = Budgets.amountFor(item.rows, Budgets.monthOf(period)),
                    used = inMonth.sumOf { it.amountMain },
                    points = budgetYear(m.year, s.monthStartDay, s.monthStartWeekend, rows, categoryId, item.rows),
                    days = groupByDay(inMonth, s.timeInput.ascending, CardExpenseMode.AT_THE_TIME),
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BudgetDetailState(month = start))

    fun previous() = month.update { it.minusMonths(1) }
    fun next() = month.update { it.plusMonths(1) }
}

@Composable
fun BudgetDetailScreen(categoryId: String, month: YearMonth, onBack: () -> Unit, onEdit: () -> Unit, onOpenTxn: (String) -> Unit) {
    val c = appContainer()
    val vm: BudgetDetailViewModel = viewModel(key = "budget-detail-$categoryId") {
        BudgetDetailViewModel(c.txns, c.budgets, c.settings, c.time, categoryId, month)
    }
    val s by vm.state.collectAsStateWithLifecycle()
    BudgetDetailContent(s, Currencies.state.mainSpec, onBack, vm::previous, vm::next, onEdit, onOpenTxn)
}

/** Budget detail (capture `rb-bdetail`): the month's Budget, Used Amount and Remaining, the year chart, then the entries. */
@Composable
fun BudgetDetailContent(
    s: BudgetDetailState,
    spec: CurrencySpec,
    onBack: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onEdit: () -> Unit,
    onOpenTxn: (String) -> Unit,
) {
    LaunchedEffect(s.gone) { if (s.gone) onBack() }
    Column(Modifier.fillMaxSize().background(Mb.colors.background).systemBarsPadding()) {
        MbTopBar(
            onBack = onBack, title = { TitleText(s.name) },
            actions = {
                MonthNavigator(Formats.monthTitle(s.month), onPrev, onNext)
                IconButtonBox(R.drawable.ic_edit, "Edit budget", onEdit)
            },
        )
        Hairline(px = 2)
        val remaining = s.budget - s.used
        SummaryBar(
            listOf(
                SummaryItem("Budget", money(s.budget, spec), SummaryColor.PRIMARY),
                SummaryItem("Used Amount", money(s.used, spec), SummaryColor.EXPENSE),
                SummaryItem("Remaining", money(remaining, spec), if (remaining < 0) SummaryColor.EXPENSE else SummaryColor.PRIMARY),
            ),
        )
        BudgetChart(s.points, s.month.monthValue - 1, spec.decimals)
        Hairline()
        Column(Modifier.weight(1f)) { DailyTab(s.days, spec, onOpenTxn) }
    }
}
