package com.moneybook.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class DayInputTest {
    @Test fun `first digit replaces, then digits append up to 31`() {
        assertEquals(2, DayInput(1).digit(2).value)
        assertEquals(25, DayInput(1).digit(2).digit(5).value)
        assertEquals(3, DayInput(1).digit(3).digit(2).value)
        assertEquals(31, DayInput(1).digit(3).digit(1).value)
        assertEquals(20, DayInput(20).digit(2).digit(0).value)
    }

    @Test fun `zero alone is refused`() {
        assertEquals(DayInput(1, fresh = true), DayInput(1).digit(0))
    }

    @Test fun `backspace drops a digit, then returns to a fresh 1`() {
        val one = DayInput(1).digit(1).digit(9).backspace()
        assertEquals(DayInput(1, fresh = false), one)
        assertEquals(DayInput(1, fresh = true), one.backspace())
        assertEquals(2, one.backspace().digit(2).value)
    }

    @Test fun `clear gives a fresh 1`() {
        assertEquals(DayInput(1, fresh = true), DayInput(1).digit(2).digit(5).clear())
    }
}
