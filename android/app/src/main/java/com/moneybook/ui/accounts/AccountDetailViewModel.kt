package com.moneybook.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moneybook.data.AccountRepository
import com.moneybook.data.AppSettings
import com.moneybook.data.SettingsRepository
import com.moneybook.data.TimeSource
import com.moneybook.data.TxnRepository
import com.moneybook.data.db.AccountBalanceRow
import com.moneybook.data.db.DbTime
import com.moneybook.domain.AccountPeriodSummary
import com.moneybook.domain.CardStatement
import com.moneybook.domain.DateRange
import com.moneybook.domain.DayGroup
import com.moneybook.domain.Formats
import com.moneybook.domain.PeriodLine
import com.moneybook.domain.StatementPeriods
import com.moneybook.domain.accountPeriodSummary
import com.moneybook.domain.Totals
import com.moneybook.domain.groupByDay
import com.moneybook.domain.localDate
import com.moneybook.domain.periodLines
import com.moneybook.domain.runningBalances
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.ui.common.specOf
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

enum class DetailTab(val label: String) { DAILY("Daily"), MONTHLY("Monthly"), ANNUALLY("Annually") }

/** A Monthly or Annually row: its title ("Dec", "2026"), the month or year it opens, and its sums. */
data class DetailLine(val title: String, val month: YearMonth?, val year: Int, val line: PeriodLine)

/** What Pay opens: a transfer from the card's payment account, for the overdue part or Balance Payable. */
data class PayPreset(val fromAccountId: String?, val amount: Long)

data class AccountDetailState(
    val name: String = "",
    val tab: DetailTab = DetailTab.DAILY,
    val month: YearMonth,
    val year: Int = month.year,
    val today: LocalDate = month.atDay(1),
    val currency: CurrencySpec = specOf("USD"),
    /** The Statement block's period: the month's (Daily) or the year's (Monthly, Annually). */
    val period: DateRange? = null,
    val summary: AccountPeriodSummary = AccountPeriodSummary(0, 0),
    /** Balance at the end of [period]. */
    val balance: Long = 0,
    val dayGroups: List<DayGroup> = emptyList(),
    /** The balance just after each entry of [dayGroups], by id. */
    val balances: Map<String, Long> = emptyMap(),
    /** Monthly: December first; Annually: the newest year first. */
    val lines: List<DetailLine> = emptyList(),
    val showChart: Boolean = false,
    /** The six months up to [month], oldest first, for the charts. */
    val chart: List<DetailLine> = emptyList(),
    val isCard: Boolean = false,
    val pay: PayPreset? = null,
) {
    val title: String get() = when (tab) {
        DetailTab.DAILY -> Formats.monthTitle(month)
        DetailTab.MONTHLY -> year.toString()
        DetailTab.ANNUALLY -> ""
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AccountDetailViewModel(
    private val accountId: String,
    private val txns: TxnRepository,
    private val accounts: AccountRepository,
    settings: SettingsRepository,
    private val time: TimeSource,
) : ViewModel() {
    private val tab = MutableStateFlow(DetailTab.DAILY)

    /** Null until the user steps; until then the month (and its year) that holds today. */
    private val month = MutableStateFlow<YearMonth?>(null)
    private val year = MutableStateFlow<Int?>(null)
    private val showChart = MutableStateFlow(false)

    // Any state: a deleted account's detail (Deleted accounts) is read-only but still shows its name and balance.
    private val account: Flow<AccountBalanceRow?> = accounts.observeAnyBalance(accountId).distinctUntilChanged()

    private data class Inputs(val tab: DetailTab, val month: YearMonth?, val year: Int?, val chart: Boolean, val settings: AppSettings)

    val state: StateFlow<AccountDetailState> =
        combine(tab, month, year, showChart, settings.settings) { t, m, y, c, s -> Inputs(t, m, y, c, s) }
            .flatMapLatest { inputs -> account.flatMapLatest { acc -> build(inputs, acc) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountDetailState(month = YearMonth.from(time.today())))

    private fun build(i: Inputs, acc: AccountBalanceRow?): Flow<AccountDetailState> {
        val isCard = acc?.groupType == AccountGroupType.CARD
        val periods = StatementPeriods(if (isCard) acc?.statementDay ?: 1 else null, i.settings.monthStartDay, i.settings.monthStartWeekend)
        val today = time.today()
        val m = i.month ?: periods.monthOf(today)
        val y = i.year ?: m.year
        val base = AccountDetailState(
            name = acc?.name.orEmpty(), tab = i.tab, month = m, year = y, today = today,
            currency = specOf(acc?.currency ?: i.settings.mainCurrency), showChart = i.chart, isCard = isCard,
        )
        val main: Flow<AccountDetailState> = when (i.tab) {
            DetailTab.DAILY -> {
                val p = periods.month(m)
                combine(txns.observeRangeForAccount(accountId, p), balanceAt(p.endExclusive)) { rows, end ->
                    base.copy(
                        period = p, summary = accountPeriodSummary(accountId, rows), balance = end,
                        // Each day in the account's own currency: its deposits and withdrawals, transfers included (Realbyte).
                        dayGroups = groupByDay(rows, i.settings.timeInput.ascending).map { g ->
                            accountPeriodSummary(accountId, g.rows).let { g.copy(totals = Totals(it.deposit, it.withdrawal)) }
                        },
                        balances = runningBalances(rows, accountId, end),
                    )
                }
            }
            DetailTab.MONTHLY -> {
                val months = (1..12).map { YearMonth.of(y, it) }
                val whole = periods.year(y)
                combine(txns.observeRangeForAccount(accountId, whole), balanceAt(whole.endExclusive)) { rows, end ->
                    val lines = periodLines(months.map(periods::month), rows, accountId, end)
                    base.copy(
                        period = whole, summary = accountPeriodSummary(accountId, rows), balance = end,
                        lines = months.zip(lines) { ym, l -> DetailLine(Formats.monthName(ym), ym, y, l) }.reversed(),
                    )
                }
            }
            DetailTab.ANNUALLY -> {
                val current = periods.monthOf(today).year
                val whole = periods.year(current)
                combine(txns.observeAllForAccount(accountId), balanceAt(whole.endExclusive)) { rows, end ->
                    val first = rows.minOfOrNull { periods.monthOf(it.localDate()).year }?.coerceAtMost(current) ?: current
                    val years = (first..current).toList()
                    val lines = periodLines(years.map(periods::year), rows, accountId, end)
                    val last = lines.last()
                    base.copy(
                        period = whole, summary = AccountPeriodSummary(last.deposit, last.withdrawal), balance = end,
                        lines = years.zip(lines) { yr, l -> DetailLine(yr.toString(), null, yr, l) }.reversed(),
                    )
                }
            }
        }
        val chart: Flow<List<DetailLine>> = if (!i.chart) {
            flowOf(emptyList())
        } else {
            val months = (CHART_MONTHS - 1 downTo 0).map { m.minusMonths(it.toLong()) }
            val ranges = months.map(periods::month)
            combine(
                txns.observeRangeForAccount(accountId, DateRange(ranges.first().start, ranges.last().endExclusive)),
                balanceAt(ranges.last().endExclusive),
            ) { rows, end -> months.zip(periodLines(ranges, rows, accountId, end)) { ym, l -> DetailLine(Formats.monthName(ym), ym, ym.year, l) } }
        }
        val pay: Flow<PayPreset?> = if (!isCard || acc == null) {
            flowOf(null)
        } else {
            accounts.observeCardCharges().map { charges ->
                val status = CardStatement.status(acc.balance, charges[accountId].orEmpty(), today, acc.statementDay ?: 1, acc.paymentDay ?: 1)
                PayPreset(acc.paymentAccountId, status.payPrefill)
            }
        }
        return combine(main, chart, pay) { s, c, p -> s.copy(chart = c, pay = p) }
    }

    private fun balanceAt(endExclusive: LocalDate): Flow<Long> =
        accounts.observeAnyBalance(accountId, DbTime.startOf(endExclusive)).map { it?.balance ?: 0 }

    /** Deleted accounts → Restore. */
    suspend fun restore() = accounts.restore(accountId)

    fun selectTab(t: DetailTab) {
        tab.value = t
    }

    fun toggleChart() {
        showChart.value = !showChart.value
    }

    fun previous() = step(-1)

    fun next() = step(1)

    private fun step(by: Long) {
        val s = state.value
        when (s.tab) {
            DetailTab.DAILY -> {
                month.value = s.month.plusMonths(by)
                year.value = null // Monthly then shows the year of the month shown on Daily.
            }
            DetailTab.MONTHLY -> year.value = s.year + by.toInt()
            DetailTab.ANNUALLY -> Unit
        }
    }

    /** A Monthly row opens Daily on its month; an Annually row opens Monthly on its year. */
    fun open(line: DetailLine) {
        if (line.month != null) {
            month.value = line.month
            tab.value = DetailTab.DAILY
        } else {
            year.value = line.year
            tab.value = DetailTab.MONTHLY
        }
    }

    private companion object {
        const val CHART_MONTHS = 6
    }
}
