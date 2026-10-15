package com.moneybook.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moneybook.data.AccountRepository
import com.moneybook.data.CurrencyRepository
import com.moneybook.data.SettingsRepository
import com.moneybook.data.TimeSource
import com.moneybook.data.TxnRepository
import com.moneybook.data.db.DbTime
import com.moneybook.domain.DateRange
import com.moneybook.domain.Formats
import com.moneybook.domain.Periods
import com.moneybook.domain.Totals
import com.moneybook.domain.buildAccountsOverview
import com.moneybook.domain.totalsPerRange
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.ui.common.specOf
import java.time.YearMonth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class TotalStatsState(
    val month: YearMonth,
    val currency: CurrencySpec = specOf("USD"),
    /** The 6 months ending with [month], oldest first. */
    val months: List<YearMonth> = emptyList(),
    /** The Accounts-tab Total at the end of each month. */
    val balances: List<Long> = emptyList(),
    val totals: List<Totals> = emptyList(),
) {
    val title: String get() = Formats.monthTitle(month)
    val balance: Long get() = balances.lastOrNull() ?: 0
}

@OptIn(ExperimentalCoroutinesApi::class)
class TotalStatsViewModel(
    private val txns: TxnRepository,
    private val accounts: AccountRepository,
    settings: SettingsRepository,
    private val time: TimeSource,
    currencies: CurrencyRepository,
) : ViewModel() {
    /** Null until the user steps; until then the month containing today (Monthly Start Date applied). */
    private val chosen = MutableStateFlow<YearMonth?>(null)

    val state: StateFlow<TotalStatsState> = combine(chosen, settings.settings, currencies.state) { m, s, cs -> Triple(m ?: Periods.monthOf(time.today(), s.monthStartDay, s.monthStartWeekend), s, cs) }
        .flatMapLatest { (month, s, cs) ->
            val months = (MONTHS - 1 downTo 0).map { month.minusMonths(it.toLong()) }
            val ranges = months.map { Periods.month(it, s.monthStartDay, s.monthStartWeekend) }
            val balances = combine(ranges.map { r -> accounts.observeBalances(DbTime.startOf(r.endExclusive)).map { buildAccountsOverview(it, toMain = cs.fx::toMain).total } }) { it.toList() }
            combine(balances, txns.observeRange(DateRange(ranges.first().start, ranges.last().endExclusive))) { totals, rows ->
                TotalStatsState(month, cs.mainSpec, months, totals, totalsPerRange(rows, ranges))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TotalStatsState(YearMonth.from(time.today())))

    fun previous() {
        chosen.value = state.value.month.minusMonths(1)
    }

    fun next() {
        chosen.value = state.value.month.plusMonths(1)
    }

    private companion object {
        const val MONTHS = 6
    }
}
