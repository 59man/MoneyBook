package com.moneybook.domain

import com.moneybook.shared.model.RepeatFrequency
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** One date of a repeat rule; [index] 0 is the rule's start (the entry the user saved). */
data class Occurrence(val index: Int, val date: LocalDate)

/** The dates repeat rules fall on (Rep/Inst. → Repeat, "On the date"). */
object RepeatSchedule {
    /** At most this many occurrences are added per rule and run; the next run continues. */
    const val MAX_PER_RUN = 3660

    private val weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

    /**
     * Every date of a rule starting on [start], in order. Monthly dates are counted from [start], so a
     * rule from the 31st stays on the last day of shorter months and returns to the 31st after them.
     */
    fun dates(frequency: RepeatFrequency, start: LocalDate): Sequence<LocalDate> = when (frequency) {
        RepeatFrequency.WEEKDAYS, RepeatFrequency.WEEKEND -> {
            val wantWeekend = frequency == RepeatFrequency.WEEKEND
            sequenceOf(start) + generateSequence(start.plusDays(1)) { it.plusDays(1) }.filter { (it.dayOfWeek in weekend) == wantWeekend }
        }
        else -> generateSequence(0L) { it + 1 }.map { n -> nth(frequency, start, n) }
    }

    private fun nth(frequency: RepeatFrequency, start: LocalDate, n: Long): LocalDate = when (frequency) {
        RepeatFrequency.DAILY -> start.plusDays(n)
        RepeatFrequency.WEEKLY -> start.plusWeeks(n)
        RepeatFrequency.EVERY_2_WEEKS -> start.plusWeeks(2 * n)
        RepeatFrequency.EVERY_4_WEEKS -> start.plusWeeks(4 * n)
        RepeatFrequency.MONTHLY -> start.plusMonths(n)
        RepeatFrequency.EVERY_2_MONTHS -> start.plusMonths(2 * n)
        RepeatFrequency.EVERY_3_MONTHS -> start.plusMonths(3 * n)
        RepeatFrequency.EVERY_4_MONTHS -> start.plusMonths(4 * n)
        RepeatFrequency.EVERY_6_MONTHS -> start.plusMonths(6 * n)
        RepeatFrequency.YEARLY -> start.plusYears(n)
        RepeatFrequency.END_OF_MONTH -> {
            val month = YearMonth.from(start)
            // From a day before the month's end, the month's own last day comes first.
            val shift = if (start == month.atEndOfMonth()) n else n - 1
            if (n == 0L) start else month.plusMonths(shift).atEndOfMonth()
        }
        RepeatFrequency.WEEKDAYS, RepeatFrequency.WEEKEND -> error("day-filtered frequencies have no nth date")
    }

    private fun indexed(frequency: RepeatFrequency, start: LocalDate, installments: Int?): Sequence<Occurrence> =
        dates(frequency, start).mapIndexed { i, date -> Occurrence(i, date) }
            .takeWhile { installments == null || it.index < installments }

    /** The occurrences after [last] up to [today], oldest first; at most [cap]. A plan stops after [installments] parts. */
    fun due(frequency: RepeatFrequency, start: LocalDate, last: LocalDate, installments: Int?, today: LocalDate, cap: Int = MAX_PER_RUN): List<Occurrence> =
        indexed(frequency, start, installments).takeWhile { !it.date.isAfter(today) }.filter { it.date.isAfter(last) }.take(cap).toList()

    /** The first occurrence after [last], or null when an installment plan is complete. */
    fun next(frequency: RepeatFrequency, start: LocalDate, last: LocalDate, installments: Int?): Occurrence? =
        indexed(frequency, start, installments).firstOrNull { it.date.isAfter(last) }
}

/** [total] split into [parts] monthly parts; the first part takes the remainder (100.00 / 3 = 33.34 + 33.33 + 33.33). */
fun splitInstallments(total: Long, parts: Int): List<Long> {
    require(parts > 0) { "parts must be positive: $parts" }
    val base = total / parts
    val remainder = total - base * parts
    return List(parts) { if (it == 0) base + remainder else base }
}
