package com.moneybook.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moneybook.data.CategoryRepository
import com.moneybook.data.SettingsRepository
import com.moneybook.data.TxnRepository
import com.moneybook.domain.CategorySlice
import com.moneybook.domain.DateRange
import com.moneybook.domain.DayGroup
import com.moneybook.domain.StatsPeriod
import com.moneybook.domain.groupByDay
import com.moneybook.domain.localDate
import com.moneybook.domain.periodSums
import com.moneybook.domain.sliceKey
import com.moneybook.domain.subcategorySlices
import com.moneybook.shared.model.TxnType
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.ui.common.specOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class CategoryDetailState(
    val name: String,
    val period: StatsPeriod,
    val currency: CurrencySpec = specOf("USD"),
    val total: Long = 0,
    /** This period and the ones before it, oldest first; empty for a custom period. */
    val trend: List<Long> = emptyList(),
    val trendLabels: List<String> = emptyList(),
    val dayGroups: List<DayGroup> = emptyList(),
    /** Subcategory ON and at least two subcategories in the period: the breakdown above the entries. */
    val subSlices: List<CategorySlice> = emptyList(),
)

/** One Stats slice ([key], see `sliceKey`) of [type] over a period that can step like the Stats tab. */
@OptIn(ExperimentalCoroutinesApi::class)
class CategoryDetailViewModel(
    private val key: String,
    name: String,
    private val type: TxnType,
    initial: StatsPeriod,
    txns: TxnRepository,
    categories: CategoryRepository,
    settings: SettingsRepository,
) : ViewModel() {
    private val period = MutableStateFlow(initial)

    val state: StateFlow<CategoryDetailState> = combine(period, settings.settings) { p, s -> p to s }
        .flatMapLatest { (p, s) ->
            val current = p.range(s.monthStartDay, s.monthStartWeekend)
            val trendPeriods = if (p.movable) p.lastPeriods(TREND_POINTS) else emptyList()
            val trendRanges = trendPeriods.map { it.range(s.monthStartDay, s.monthStartWeekend) }
            val window = DateRange(trendRanges.firstOrNull()?.start ?: current.start, current.endExclusive)
            txns.observeRange(window).map { rows ->
                val fallback = categories.statsFallback(type)
                val mine = rows.filter { it.sliceKey(type, fallback) == key }
                val inPeriod = mine.filter { current.contains(it.localDate()) }
                CategoryDetailState(
                    name = name, period = p, currency = specOf(s.mainCurrency),
                    total = inPeriod.sumOf { it.amountMain },
                    trend = periodSums(mine, trendRanges, type),
                    trendLabels = trendPeriods.map { it.axisLabel() },
                    dayGroups = groupByDay(inPeriod, s.timeInput.ascending),
                    subSlices = if (s.subcategory) subcategorySlices(inPeriod, type) else emptyList(),
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CategoryDetailState(name, initial))

    fun previous() {
        period.value = period.value.shift(-1)
    }

    fun next() {
        period.value = period.value.shift(1)
    }

    companion object {
        /** Realbyte's trend shows 8 periods. */
        const val TREND_POINTS = 8
    }
}
