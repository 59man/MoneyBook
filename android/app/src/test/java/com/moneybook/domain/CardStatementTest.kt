package com.moneybook.domain

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Test

class CardStatementTest {
    private fun d(s: String) = LocalDate.parse(s)
    private fun r(a: String, lastDay: String) = DateRange(d(a), d(lastDay).plusDays(1))

    @Test fun `periods measured on Realbyte`() {
        assertEquals(r("2026-09-01", "2026-09-30"), CardStatement.containing(d("2026-09-15"), 1))
        assertEquals(r("2026-08-20", "2026-09-19"), CardStatement.containing(d("2026-09-19"), 20))
        assertEquals(r("2026-08-30", "2026-09-29"), CardStatement.containing(d("2026-09-01"), 30))
        assertEquals(r("2026-08-31", "2026-09-29"), CardStatement.containing(d("2026-09-10"), 31))
        assertEquals(r("2026-09-30", "2026-10-30"), CardStatement.containing(d("2026-10-07"), 31))
    }

    @Test fun `a month shows the period that ends in it`() {
        assertEquals(r("2026-09-20", "2026-10-19"), CardStatement.ofMonth(YearMonth.of(2026, 10), 20))
        assertEquals(r("2026-10-01", "2026-10-31"), CardStatement.ofMonth(YearMonth.of(2026, 10), 1))
        assertEquals(r("2027-01-31", "2027-02-27"), CardStatement.ofMonth(YearMonth.of(2027, 2), 31))
    }

    @Test fun `payment dates measured on Realbyte`() {
        val p = r("2026-08-20", "2026-09-19")
        assertEquals(d("2026-09-20"), CardStatement.paymentDate(p, 20))
        assertEquals(d("2026-10-19"), CardStatement.paymentDate(p, 19))
        assertEquals(d("2026-10-10"), CardStatement.paymentDate(p, 10))
        assertEquals(d("2026-09-30"), CardStatement.paymentDate(p, 31))
        val q = r("2026-09-01", "2026-09-30")
        assertEquals(d("2026-10-01"), CardStatement.paymentDate(q, 1))
        assertEquals(d("2026-10-25"), CardStatement.paymentDate(q, 25))
        assertEquals(d("2026-10-31"), CardStatement.paymentDate(q, 31))
    }

    @Test fun `periods are contiguous for every day and month`() {
        for (s in 1..31) {
            var p = CardStatement.containing(d("2025-12-15"), s)
            repeat(26) {
                val next = CardStatement.containing(p.endExclusive, s)
                assertEquals("S $s after $p", p.endExclusive, next.start)
                assertEquals(p, CardStatement.previous(next, s))
                p = next
            }
        }
    }

    private val charges = listOf(
        CardCharge(d("2026-08-05"), 400), CardCharge(d("2026-09-10"), 3000), CardCharge(d("2026-10-07"), 5000),
    )
    private fun status(balance: Long) = CardStatement.status(balance, charges, d("2026-10-07"), settlement = 1, payment = 15)

    @Test fun `realbyte steps - no payment`() {
        val s = status(-8400)
        assertEquals(CardStatus(payable = 3400, outstanding = 5000, overdue = 400), s)
        assertEquals(400, s.payPrefill)
    }

    @Test fun `realbyte steps - paid the overdue part`() {
        val s = status(-8000)
        assertEquals(CardStatus(3000, 5000, 0), s)
        assertEquals(3000, s.payPrefill)
    }

    @Test fun `realbyte steps - paid into the current period`() {
        val s = status(-4000)
        assertEquals(CardStatus(0, 4000, 0), s)
        assertEquals(0, s.payPrefill)
    }

    @Test fun `credit`() {
        assertEquals(CardStatus(0, -1000, 0), status(1000))
        assertEquals(CardStatus(0, 0, 0), CardStatement.status(0, emptyList(), d("2026-10-07"), 1, 1))
    }
}
