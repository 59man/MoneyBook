package com.moneybook.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moneybook.data.AppSettings
import com.moneybook.data.CategoryRepository
import com.moneybook.data.SettingsRepository
import com.moneybook.data.TimeSource
import com.moneybook.data.TxnRepository
import com.moneybook.domain.CategorySlice
import com.moneybook.domain.StatsMode
import com.moneybook.domain.StatsPeriod
import com.moneybook.domain.Totals
import com.moneybook.domain.categorySlices
import com.moneybook.domain.totalsOf
import com.moneybook.shared.model.TxnType
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.ui.common.specOf
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class StatsUiState(
    val period: StatsPeriod,
    val type: TxnType = TxnType.EXPENSE,
    val currency: CurrencySpec = specOf("USD"),
    val totals: Totals = Totals(),
    val slices: List<CategorySlice> = emptyList(),
)

private data class StatsParams(val period: StatsPeriod, val type: TxnType, val settings: AppSettings)

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(
    private val txns: TxnRepository,
    private val categories: CategoryRepository,
    private val settings: SettingsRepository,
    private val time: TimeSource,
) : ViewModel() {
    /** Null until the user picks a period; until then the month containing today (Monthly Start Date applied). */
    private val chosen = MutableStateFlow<StatsPeriod?>(null)
    private val type = MutableStateFlow(TxnType.EXPENSE)

    val state: StateFlow<StatsUiState> = combine(chosen, type, settings.settings) { p, t, s ->
        StatsParams(p ?: StatsPeriod.current(StatsMode.MONTHLY, time.today(), s.monthStartDay, s.weekStart, s.monthStartWeekend), t, s)
    }.flatMapLatest { p ->
        txns.observeRange(p.period.range(p.settings.monthStartDay, p.settings.monthStartWeekend)).map { rows ->
            StatsUiState(
                period = p.period, type = p.type, currency = specOf(p.settings.mainCurrency),
                totals = totalsOf(rows), slices = categorySlices(rows, p.type, categories.statsFallback(p.type)),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState(StatsPeriod.Month(YearMonth.from(time.today()))))

    fun selectType(t: TxnType) {
        type.value = t
    }

    /** Switching mode shows that mode's period containing today, as Realbyte does. */
    fun selectMode(mode: StatsMode) {
        viewModelScope.launch {
            val s = settings.current()
            chosen.value = StatsPeriod.current(mode, time.today(), s.monthStartDay, s.weekStart, s.monthStartWeekend)
        }
    }

    fun previous() {
        chosen.value = state.value.period.shift(-1)
    }

    fun next() {
        chosen.value = state.value.period.shift(1)
    }

    fun setCustom(first: LocalDate, last: LocalDate) {
        chosen.value = StatsPeriod.custom(first, last)
    }
}
