package com.moneybook.domain

import java.time.LocalDate
import java.time.YearMonth

/** Money that left a card (an expense, or a transfer out with its fee) on [date]. */
data class CardCharge(val date: LocalDate, val amount: Long)

/**
 * A card's Accounts-tab figures. Positive means owed; [outstanding] is negative for a credit.
 * [overdue] is the part of [payable] whose payment date has come.
 */
data class CardStatus(val payable: Long, val outstanding: Long, val overdue: Long) {
    /** Pay's amount: the overdue part first, otherwise Balance Payable. */
    val payPrefill: Long get() = if (overdue > 0) overdue else payable.coerceAtLeast(0)
}

/** Realbyte's card statements: Account Info → Settlement Date (S) and Payment Date (P), both "Every 1"–"Every 31". */
object CardStatement {
    private fun day(ym: YearMonth, day: Int): LocalDate = ym.atDay(minOf(day, ym.lengthOfMonth()))

    /** The day a period starts in [ym]: S, or the month's last day when the month is shorter. */
    fun start(ym: YearMonth, settlement: Int): LocalDate = day(ym, settlement)

    fun containing(date: LocalDate, settlement: Int): DateRange {
        val ym = YearMonth.from(date)
        val s = start(ym, settlement)
        return if (date.isBefore(s)) DateRange(start(ym.minusMonths(1), settlement), s)
        else DateRange(s, start(ym.plusMonths(1), settlement))
    }

    /** The period account detail shows for [ym]: the one that ends in that month. */
    fun ofMonth(ym: YearMonth, settlement: Int): DateRange = containing(ym.atDay(1), settlement)

    fun previous(period: DateRange, settlement: Int): DateRange = containing(period.start.minusDays(1), settlement)

    /** The first day after [period] whose day of month is P (the month's last day when P is larger). */
    fun paymentDate(period: DateRange, payment: Int): LocalDate {
        val ym = YearMonth.from(period.lastDay)
        val same = day(ym, payment)
        return if (same.isAfter(period.lastDay)) same else day(ym.plusMonths(1), payment)
    }

    /** [balance] is the card's Accounts-tab balance (negative when owed). */
    fun status(balance: Long, charges: List<CardCharge>, today: LocalDate, settlement: Int, payment: Int): CardStatus {
        val owed = -balance
        val current = containing(today, settlement)
        val currentCharges = charges.filter { current.contains(it.date) }.sumOf { it.amount }
        val outstanding = if (owed > 0) minOf(owed, currentCharges) else owed
        // The last period whose payment date has come; payments go to the oldest debt first.
        var due = previous(current, settlement)
        while (paymentDate(due, payment).isAfter(today)) due = previous(due, settlement)
        val later = charges.filter { !it.date.isBefore(due.endExclusive) }.sumOf { it.amount }
        return CardStatus(payable = owed - outstanding, outstanding = outstanding, overdue = (owed - later).coerceAtLeast(0))
    }
}
