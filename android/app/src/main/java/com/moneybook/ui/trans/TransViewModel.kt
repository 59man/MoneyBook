package com.moneybook.ui.trans

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moneybook.data.AccountRepository
import com.moneybook.data.AppSettings
import com.moneybook.data.BudgetRepository
import com.moneybook.data.CategoryRepository
import com.moneybook.data.MemoRepository
import com.moneybook.data.SettingsRepository
import com.moneybook.data.StartScreen
import com.moneybook.data.SwipeMode
import com.moneybook.data.TimeSource
import com.moneybook.data.TxnRepository
import com.moneybook.data.db.MemoEntity
import com.moneybook.domain.BudgetLine
import com.moneybook.domain.Budgets
import com.moneybook.domain.CalendarCell
import com.moneybook.domain.DateRange
import com.moneybook.domain.DayGroup
import com.moneybook.domain.FilterOptions
import com.moneybook.domain.Formats
import com.moneybook.domain.MonthRow
import com.moneybook.domain.Periods
import com.moneybook.domain.TotalBreakdown
import com.moneybook.domain.Totals
import com.moneybook.domain.TxnFilter
import com.moneybook.domain.budgetLines
import com.moneybook.domain.carryOver
import com.moneybook.domain.carryTotals
import com.moneybook.domain.withCarryOver
import com.moneybook.domain.buildCalendar
import com.moneybook.domain.buildMonthly
import com.moneybook.domain.calendarGrid
import com.moneybook.domain.filterOptions
import com.moneybook.domain.groupByDay
import com.moneybook.domain.localDate
import com.moneybook.domain.monthlyQueryRange
import com.moneybook.domain.totalBreakdown
import com.moneybook.domain.totalsOf
import com.moneybook.shared.model.CategoryType
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.ui.common.specOf
import java.time.DayOfWeek
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
import kotlinx.coroutines.launch

enum class TransTab(val label: String) {
    DAILY("Daily"),
    CALENDAR("Calendar"),
    MONTHLY("Monthly"),
    TOTAL("Total"),
    NOTE("Note"),
}

data class TransUiState(
    val tab: TransTab = TransTab.DAILY,
    val month: YearMonth,
    val currency: CurrencySpec = specOf("USD"),
    val weekStart: DayOfWeek = DayOfWeek.SUNDAY,
    val summary: Totals = Totals(),
    val dayGroups: List<DayGroup> = emptyList(),
    val calendar: List<CalendarCell> = emptyList(),
    val calendarDays: Map<LocalDate, DayGroup> = emptyMap(),
    val monthly: List<MonthRow> = emptyList(),
    /** Trans → Total: the Accounts card for [month] and the month's date range. */
    val total: TotalBreakdown? = null,
    val totalRange: DateRange? = null,
    /** Trans → Total's Budget section for [month], and where today sits along the bars (null outside the month). */
    val budgets: List<BudgetLine> = emptyList(),
    val budgetToday: Float? = null,
    val filter: TxnFilter = TxnFilter(),
    /** Trans → Note: the month's memos, and the date a new memo gets (today, or the month's first day). */
    val memos: List<MemoEntity> = emptyList(),
    val memoDate: LocalDate? = null,
    /** Configuration → Swipe and Note button setting. */
    val swipe: SwipeMode = SwipeMode.DATE,
    val noteButton: Boolean = true,
) {
    /** Realbyte shows only the year on the Monthly tab. */
    val title: String get() = if (tab == TransTab.MONTHLY) month.year.toString() else Formats.monthTitle(month)
}

private data class Params(
    val tab: TransTab, val month: YearMonth, val expanded: YearMonth?, val settings: AppSettings, val filter: TxnFilter,
)

@OptIn(ExperimentalCoroutinesApi::class)
class TransViewModel(
    private val txns: TxnRepository,
    settings: SettingsRepository,
    private val time: TimeSource,
    private val categories: CategoryRepository,
    private val accounts: AccountRepository,
    private val memos: MemoRepository,
    private val budgets: BudgetRepository,
) : ViewModel() {
    private val tab = MutableStateFlow(TransTab.DAILY)
    private val month = MutableStateFlow(YearMonth.from(time.today()))
    private val expanded = MutableStateFlow<YearMonth?>(YearMonth.from(time.today()))
    /** Trans → filter. It lasts until cleared or the process ends; it is not saved. */
    private val filter = MutableStateFlow(TxnFilter())

    init {
        // Open on the Start Screen tab and on the month that holds today under the Monthly Start Date. A changed
        // start day or weekend rule (Configuration, a restore) moves to that month again; this view model lives
        // for the whole session.
        viewModelScope.launch {
            if (settings.current().startScreen == StartScreen.CALENDAR) tab.value = TransTab.CALENDAR
            settings.settings.map { it.monthStartDay to it.monthStartWeekend }.distinctUntilChanged().collect { (day, weekend) ->
                val m = Periods.monthOf(time.today(), day, weekend)
                month.value = m
                expanded.value = m
            }
        }
    }

    val state: StateFlow<TransUiState> = combine(tab, month, expanded, settings.settings, filter) { t, m, e, s, f -> Params(t, m, e, s, f) }
        .flatMapLatest { p ->
            val base = TransUiState(
                tab = p.tab, month = p.month, currency = specOf(p.settings.mainCurrency), weekStart = p.settings.weekStart,
                filter = p.filter, swipe = p.settings.swipe, noteButton = p.settings.noteButton,
                memoDate = memoDate(p.month, p.settings),
            )
            when (p.tab) {
                TransTab.DAILY -> dailyFlow(base, p.settings, p.filter)
                TransTab.CALENDAR -> calendarFlow(base, p.settings, p.filter)
                TransTab.MONTHLY -> monthlyFlow(base, p.settings, p.expanded, p.filter)
                TransTab.TOTAL -> totalFlow(base, p.settings, p.filter)
                TransTab.NOTE -> noteFlow(base, p.settings)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransUiState(month = YearMonth.from(time.today())))

    /** The filter panel's lists for the shown month; their amounts ignore the filter. */
    val filterOptions: StateFlow<FilterOptions> = combine(month, settings.settings, filter) { m, s, f -> Triple(m, s, f) }
        .flatMapLatest { (m, s, f) ->
            combine(txns.observeRange(Periods.month(m, s.monthStartDay, s.monthStartWeekend)), accounts.observeBalances()) { rows, balances ->
                filterOptions(rows, categories.top(CategoryType.INCOME), categories.top(CategoryType.EXPENSE), balances, f)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FilterOptions())

    /**
     * The Carry-over value before [period], or null when the setting is off or a Trans filter is active.
     * ponytail: reads every earlier entry; add a SQL sum if a very long history makes Daily slow.
     */
    private fun carryOverFlow(s: AppSettings, f: TxnFilter, period: DateRange): Flow<Long?> =
        if (!s.carryOver || f.isActive) {
            flowOf(null)
        } else {
            txns.observeRange(DateRange(LocalDate.of(1, 1, 1), period.start)).map { carryOver(it, s.cardExpenseMode) }
        }

    private fun dailyFlow(base: TransUiState, s: AppSettings, f: TxnFilter): Flow<TransUiState> {
        val period = Periods.month(base.month, s.monthStartDay, s.monthStartWeekend)
        return combine(txns.observeRange(period), carryOverFlow(s, f, period)) { all, carry ->
            val rows = all.filter(f::matches)
            val days = groupByDay(rows, s.timeInput.ascending, s.cardExpenseMode)
            base.copy(
                summary = totalsOf(rows, s.cardExpenseMode) + carryTotals(carry),
                dayGroups = if (carry == null) days else withCarryOver(days, period.start, carry, s.mainCurrency, appendLast = true),
            )
        }
    }

    private fun calendarFlow(base: TransUiState, s: AppSettings, f: TxnFilter): Flow<TransUiState> {
        val period = Periods.month(base.month, s.monthStartDay, s.monthStartWeekend)
        return combine(txns.observeRange(calendarGrid(period, s.weekStart)), carryOverFlow(s, f, period)) { all, carry ->
            val rows = all.filter(f::matches)
            val cells = buildCalendar(period, s.weekStart, time.today(), rows, s.cardExpenseMode)
            val days = groupByDay(rows, s.timeInput.ascending, s.cardExpenseMode)
            base.copy(
                summary = totalsOf(rows.filter { period.contains(it.localDate()) }, s.cardExpenseMode) + carryTotals(carry),
                calendar = if (carry == null) cells else cells.withCarryOver(period.start, carry),
                calendarDays = (if (carry == null) days else withCarryOver(days, period.start, carry, s.mainCurrency, appendLast = false))
                    .associateBy { it.date },
            )
        }
    }

    private fun totalFlow(base: TransUiState, s: AppSettings, f: TxnFilter): Flow<TransUiState> {
        val period = Periods.month(base.month, s.monthStartDay, s.monthStartWeekend)
        val previous = Periods.month(base.month.minusMonths(1), s.monthStartDay, s.monthStartWeekend)
        return combine(txns.observeRange(DateRange(previous.start, period.endExclusive)), budgets.observe()) { all, items ->
            val rows = all.filter(f::matches)
            val current = rows.filter { period.contains(it.localDate()) }
            base.copy(
                summary = totalsOf(current, s.cardExpenseMode),
                total = totalBreakdown(current, rows.filter { previous.contains(it.localDate()) }, s.cardExpenseMode),
                totalRange = period,
                // Budgets ignore the Trans filter and always count card expenses on their own date (Realbyte).
                budgets = budgetLines(items, Budgets.monthOf(period), all.filter { period.contains(it.localDate()) }),
                budgetToday = Budgets.todayFraction(period, time.today()),
            )
        }
    }

    private fun monthlyFlow(base: TransUiState, s: AppSettings, expanded: YearMonth?, f: TxnFilter): Flow<TransUiState> {
        val year = base.month.year
        val yearRange = Periods.year(year, s.monthStartDay, s.monthStartWeekend)
        return txns.observeRange(monthlyQueryRange(year, s.monthStartDay, s.weekStart, s.monthStartWeekend)).map { all ->
            val rows = all.filter(f::matches)
            base.copy(
                summary = totalsOf(rows.filter { yearRange.contains(it.localDate()) }, s.cardExpenseMode),
                monthly = buildMonthly(year, s.monthStartDay, s.weekStart, time.today(), expanded, rows, s.monthStartWeekend, s.cardExpenseMode),
            )
        }
    }

    /** Memos are not entries, so the Trans filter does not apply to them. */
    private fun noteFlow(base: TransUiState, s: AppSettings): Flow<TransUiState> {
        val period = Periods.month(base.month, s.monthStartDay, s.monthStartWeekend)
        val today = time.today()
        return memos.observeRange(period).map {
            base.copy(memos = it, memoDate = if (period.contains(today)) today else period.start)
        }
    }

    /** A new memo's date (Note tab and the Daily note button): today in the shown month, else the month's first day. */
    private fun memoDate(m: YearMonth, s: AppSettings): LocalDate {
        val period = Periods.month(m, s.monthStartDay, s.monthStartWeekend)
        val today = time.today()
        return if (period.contains(today)) today else period.start
    }

    /** Swipe "To Change Tab": the neighbouring sub-tab; it stops at the ends. */
    fun previousTab() {
        TransTab.entries.getOrNull(tab.value.ordinal - 1)?.let { tab.value = it }
    }

    fun nextTab() {
        TransTab.entries.getOrNull(tab.value.ordinal + 1)?.let { tab.value = it }
    }

    fun toggleCategory(id: String) {
        filter.value = filter.value.toggleCategory(id)
    }

    fun toggleAccount(id: String) {
        filter.value = filter.value.toggleAccount(id)
    }

    /** The panel's "All" row: tick or untick every id of one list. */
    fun setAllCategories(ids: List<String>, ticked: Boolean) {
        filter.value = filter.value.withCategories(ids, ticked)
    }

    fun setAllAccounts(ids: List<String>, ticked: Boolean) {
        filter.value = filter.value.withAccounts(ids, ticked)
    }

    fun selectTab(t: TransTab) {
        tab.value = t
    }

    fun previous() = step(-1)

    fun next() = step(1)

    private fun step(direction: Long) {
        month.value = if (tab.value == TransTab.MONTHLY) month.value.plusYears(direction) else month.value.plusMonths(direction)
        expanded.value = month.value
    }

    fun toggleMonth(ym: YearMonth) {
        expanded.value = if (expanded.value == ym) null else ym
    }
}
