package com.moneybook.domain

import com.moneybook.data.db.AccountBalanceRow
import com.moneybook.data.db.TxnRow
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.TxnType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

data class Totals(val income: Long = 0, val expense: Long = 0) {
    val total: Long get() = income - expense
    operator fun plus(other: Totals) = Totals(income + other.income, expense + other.expense)
}

fun TxnRow.localDate(): LocalDate = LocalDate.parse(dateTime.substring(0, 10))

/** Income and expense in main-currency minor units; transfers are not income or expense. */
/** More → Accounts → Card expenses display config. B leaves card spending out of Trans's sums and counts the card's payments instead. */
enum class CardExpenseMode(val label: String) { AT_THE_TIME("A. At the time"), LUMP_SUM("B. Lump sum") }

/**
 * What Trans counts [this] row as, or null when it counts as neither income nor expense. A transfer counts as an expense
 * when it enters a Transfer-Expense account (or, in mode B, a card from a non-card account) and as income when it leaves one.
 */
fun TxnRow.countedAs(mode: CardExpenseMode = CardExpenseMode.AT_THE_TIME): TxnType? = when (type) {
    TxnType.INCOME -> TxnType.INCOME
    TxnType.EXPENSE -> if (mode == CardExpenseMode.LUMP_SUM && accountGroupType == AccountGroupType.CARD) null else TxnType.EXPENSE
    TxnType.TRANSFER -> {
        val lump = mode == CardExpenseMode.LUMP_SUM
        val toCard = lump && toAccountGroupType == AccountGroupType.CARD
        val fromCard = accountGroupType == AccountGroupType.CARD
        when {
            toCard && !fromCard && !fromTransferExpense -> TxnType.EXPENSE
            !fromTransferExpense && toTransferExpense && !(lump && fromCard) -> TxnType.EXPENSE // Realbyte's mode B never counts a transfer out of a card
            fromTransferExpense && !toTransferExpense && !toCard -> TxnType.INCOME
            else -> null
        }
    }
}

fun totalsOf(rows: Iterable<TxnRow>, mode: CardExpenseMode = CardExpenseMode.AT_THE_TIME): Totals {
    var income = 0L
    var expense = 0L
    for (r in rows) when (r.countedAs(mode)) {
        TxnType.INCOME -> income += r.amountMain
        TxnType.EXPENSE -> expense += r.amountMain
        else -> Unit
    }
    return Totals(income, expense)
}

data class DayGroup(val date: LocalDate, val totals: Totals, val rows: List<TxnRow>)

/** Entries by day, newest day first; within a day newest first, or oldest first when [ascending] (Time Input "Asc."). */
fun groupByDay(rows: List<TxnRow>, ascending: Boolean = false, mode: CardExpenseMode = CardExpenseMode.AT_THE_TIME): List<DayGroup> =
    rows.groupBy { it.localDate() }
        .map { (date, dayRows) ->
            DayGroup(date, totalsOf(dayRows, mode), if (ascending) dayRows.sortedBy { it.dateTime } else dayRows.sortedByDescending { it.dateTime })
        }
        .sortedByDescending { it.date }

data class CalendarCell(val date: LocalDate, val inPeriod: Boolean, val isToday: Boolean, val totals: Totals)

/** Six full weeks starting at the week containing the period start, like Realbyte's calendar. */
fun calendarGrid(period: DateRange, weekStart: DayOfWeek): DateRange {
    val start = Periods.weekStart(period.start, weekStart)
    return DateRange(start, start.plusDays(42))
}

fun buildCalendar(
    period: DateRange, weekStart: DayOfWeek, today: LocalDate, rows: List<TxnRow>, mode: CardExpenseMode = CardExpenseMode.AT_THE_TIME,
): List<CalendarCell> {
    val grid = calendarGrid(period, weekStart)
    val byDay = rows.groupBy { it.localDate() }
    return (0L until 42L).map { i ->
        val date = grid.start.plusDays(i)
        CalendarCell(date, period.contains(date), date == today, totalsOf(byDay[date].orEmpty(), mode))
    }
}

data class WeekRow(val range: DateRange, val totals: Totals, val isCurrent: Boolean)

data class MonthRow(val month: YearMonth, val range: DateRange, val totals: Totals, val weeks: List<WeekRow>)

/** Rows for the Monthly tab must cover the year plus the partial weeks at both ends. */
fun monthlyQueryRange(year: Int, startDay: Int, weekStart: DayOfWeek, weekend: WeekendShift = WeekendShift.NONE): DateRange {
    val y = Periods.year(year, startDay, weekend)
    return DateRange(Periods.weekStart(y.start, weekStart), Periods.weekStart(y.lastDay, weekStart).plusDays(7))
}

fun buildMonthly(
    year: Int, startDay: Int, weekStart: DayOfWeek, today: LocalDate, expanded: YearMonth?, rows: List<TxnRow>,
    weekend: WeekendShift = WeekendShift.NONE,
    mode: CardExpenseMode = CardExpenseMode.AT_THE_TIME,
): List<MonthRow> {
    val byDay = rows.groupBy { it.localDate() }
    fun totalsIn(range: DateRange) = byDay.entries.fold(Totals()) { acc, (date, dayRows) ->
        if (range.contains(date)) acc + totalsOf(dayRows, mode) else acc
    }
    val current = Periods.monthOf(today, startDay, weekend)
    val lastMonth = if (year == current.year) current.monthValue else 12
    return (lastMonth downTo 1).map { m ->
        val ym = YearMonth.of(year, m)
        val range = Periods.month(ym, startDay, weekend)
        val weeks = if (ym == expanded) {
            Periods.weeksOverlapping(range, weekStart).map { WeekRow(it, totalsIn(it), it.contains(today)) }
        } else {
            emptyList()
        }
        MonthRow(ym, range, totalsIn(range), weeks)
    }
}

/** [card] is set for card accounts (Balance Payable / Outst. Balance). */
data class AccountItem(
    val id: String, val name: String, val balance: Long, val currency: String, val hidden: Boolean = false, val card: CardStatus? = null,
)

/** [total] and the card sums are in the main currency; each [AccountItem] keeps its own. */
data class AccountGroupItem(
    val id: String, val name: String, val type: AccountGroupType, val total: Long, val accounts: List<AccountItem>,
    /** Converts an account's amount to the main currency at the current rate. */
    private val toMain: (Long, String) -> Long = { minor, _ -> minor },
) {
    /** A card group's header sums. */
    val cardPayable: Long get() = accounts.filter { !it.hidden }.sumOf { toMain(it.card?.payable ?: 0, it.currency) }
    val cardOutstanding: Long get() = accounts.filter { !it.hidden }.sumOf { toMain(it.card?.outstanding ?: 0, it.currency) }
}

data class AccountsOverview(val assets: Long, val liabilities: Long, val groups: List<AccountGroupItem>) {
    val total: Long get() = assets - liabilities
}

/**
 * Assets = positive balances, liabilities = negative balances (shown positive), both only for visible
 * "Include in totals" accounts. [includeHidden] also lists hidden accounts (Show/Hide mode); they never count.
 * Card accounts get their [CardStatus] for [today] from their [charges] (none when [today] is null).
 * Accounts in another currency keep their balance; [toMain] converts it (at the current rate) wherever it is summed.
 */
fun buildAccountsOverview(
    rows: List<AccountBalanceRow>,
    includeHidden: Boolean = false,
    charges: Map<String, List<CardCharge>> = emptyMap(),
    today: LocalDate? = null,
    toMain: (Long, String) -> Long = { minor, _ -> minor },
): AccountsOverview {
    val listed = rows.filter { includeHidden || !it.hidden }
    val counted = rows.filter { !it.hidden && it.includeInTotals }.map { toMain(it.balance, it.currency) }
    val assets = counted.filter { it > 0 }.sum()
    val liabilities = -counted.filter { it < 0 }.sum()
    val groups = listed.groupBy { it.groupId }.values.map { groupRows ->
        val first = groupRows.first()
        AccountGroupItem(
            id = first.groupId, name = first.groupName, type = first.groupType,
            total = groupRows.filter { !it.hidden }.sumOf { toMain(it.balance, it.currency) },
            accounts = groupRows.map {
                val card = if (it.groupType == AccountGroupType.CARD && today != null) {
                    CardStatement.status(it.balance, charges[it.id].orEmpty(), today, it.statementDay ?: 1, it.paymentDay ?: 1)
                } else {
                    null
                }
                AccountItem(it.id, it.name, it.balance, it.currency, it.hidden, card)
            },
            toMain = toMain,
        )
    }
    return AccountsOverview(assets, liabilities, groups)
}

data class AccountPeriodSummary(val deposit: Long, val withdrawal: Long) {
    val total: Long get() = deposit - withdrawal
}

fun accountPeriodSummary(accountId: String, rows: List<TxnRow>): AccountPeriodSummary {
    var deposit = 0L
    var withdrawal = 0L
    for (r in rows) when (r.type) {
        TxnType.INCOME -> if (r.accountId == accountId) deposit += r.accountSide
        TxnType.EXPENSE -> if (r.accountId == accountId) withdrawal += r.accountSide
        TxnType.TRANSFER -> {
            if (r.accountId == accountId) withdrawal += r.accountSide + r.fee
            if (r.toAccountId == accountId) deposit += r.toAmount ?: r.amount
        }
    }
    return AccountPeriodSummary(deposit, withdrawal)
}
