package com.moneybook.domain

import com.moneybook.shared.model.RepeatFrequency
import com.moneybook.shared.model.RepeatFrequency.DAILY
import com.moneybook.shared.model.RepeatFrequency.END_OF_MONTH
import com.moneybook.shared.model.RepeatFrequency.EVERY_2_WEEKS
import com.moneybook.shared.model.RepeatFrequency.EVERY_3_MONTHS
import com.moneybook.shared.model.RepeatFrequency.MONTHLY
import com.moneybook.shared.model.RepeatFrequency.WEEKDAYS
import com.moneybook.shared.model.RepeatFrequency.WEEKEND
import com.moneybook.shared.model.RepeatFrequency.YEARLY
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RepeatScheduleTest {
    private fun d(text: String) = LocalDate.parse(text)

    /** The dates added when the rule started on [start] and nothing after it was added yet. */
    private fun dueDates(f: RepeatFrequency, start: String, today: String, last: String = start, installments: Int? = null) =
        RepeatSchedule.due(f, d(start), d(last), installments, d(today)).map { it.date.toString() }

    @Test fun `monthly rules keep their day, clamped in short months`() {
        assertEquals(listOf("2026-02-28", "2026-03-31", "2026-04-30"), dueDates(MONTHLY, "2026-01-31", "2026-05-15"))
        assertEquals(listOf("2026-04-30", "2026-07-31", "2026-10-31"), dueDates(EVERY_3_MONTHS, "2026-01-31", "2026-12-31"))
    }

    @Test fun `the end of the month is always the last day`() {
        assertEquals(listOf("2026-01-31", "2026-02-28", "2026-03-31"), dueDates(END_OF_MONTH, "2026-01-15", "2026-03-31"))
        assertEquals(listOf("2026-02-28", "2026-03-31"), dueDates(END_OF_MONTH, "2026-01-31", "2026-03-31"))
    }

    @Test fun `annual rules from the 29th of February fall on the 28th in other years`() {
        assertEquals(listOf("2025-02-28", "2026-02-28", "2027-02-28", "2028-02-29"), dueDates(YEARLY, "2024-02-29", "2028-03-01"))
    }

    @Test fun `weekdays and weekends skip the other days`() {
        assertEquals(listOf("2026-10-05", "2026-10-06", "2026-10-07"), dueDates(WEEKDAYS, "2026-10-02", "2026-10-07")) // from a Friday
        assertEquals(listOf("2026-10-10", "2026-10-11"), dueDates(WEEKEND, "2026-10-05", "2026-10-12")) // from a Monday
    }

    @Test fun `catching up after a gap adds every missed day with its index`() {
        val due = RepeatSchedule.due(DAILY, d("2026-10-01"), d("2026-10-03"), null, d("2026-10-06"))
        assertEquals(listOf(3 to "2026-10-04", 4 to "2026-10-05", 5 to "2026-10-06"), due.map { it.index to it.date.toString() })
        assertEquals(listOf("2026-10-15", "2026-10-29", "2026-11-12", "2026-11-26"), dueDates(EVERY_2_WEEKS, "2026-10-01", "2026-11-30"))
    }

    @Test fun `nothing is due before its date or when the clock went back`() {
        assertEquals(emptyList<String>(), dueDates(MONTHLY, "2026-09-26", "2026-10-25"))
        assertEquals(emptyList<String>(), dueDates(DAILY, "2026-10-01", "2026-10-02", last = "2026-10-05"))
    }

    @Test fun `an installment plan stops after its parts`() {
        val due = RepeatSchedule.due(MONTHLY, d("2026-01-10"), d("2026-01-10"), 3, d("2027-01-01"))
        assertEquals(listOf(1 to "2026-02-10", 2 to "2026-03-10"), due.map { it.index to it.date.toString() })
        assertNull(RepeatSchedule.next(MONTHLY, d("2026-01-10"), d("2026-03-10"), 3))
    }

    @Test fun `next is the first date after the last one added`() {
        assertEquals(Occurrence(2, d("2026-03-31")), RepeatSchedule.next(MONTHLY, d("2026-01-31"), d("2026-02-28"), null))
        assertEquals(Occurrence(1, d("2026-10-03")), RepeatSchedule.next(DAILY, d("2026-10-02"), d("2026-10-02"), null))
    }

    @Test fun `one run adds at most the safety cap`() {
        assertEquals(RepeatSchedule.MAX_PER_RUN, RepeatSchedule.due(DAILY, d("2000-01-01"), d("2000-01-01"), null, d("2026-10-06")).size)
    }

    @Test fun `installment parts give the remainder to the first`() {
        assertEquals(listOf(33_334L, 33_333L, 33_333L), splitInstallments(100_000, 3))
        assertEquals(listOf(500L, 500L), splitInstallments(1_000, 2))
    }
}
