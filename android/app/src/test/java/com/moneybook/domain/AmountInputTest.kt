package com.moneybook.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmountInputTest {
    private fun type(vararg keys: String): AmountInput = keys.fold(AmountInput()) { acc, k ->
        when (k) {
            "." -> acc.dot()
            "<" -> acc.backspace()
            "-" -> acc.toggleSign()
            else -> k.fold(acc) { a, c -> a.digit(c) }
        }
    }

    @Test fun `digits build the amount`() {
        assertEquals(52_500L, type("525").minorUnits())
        assertEquals(52_550L, type("525", ".", "5").minorUnits())
    }

    @Test fun `leading zero is replaced`() {
        assertEquals("5", type("0", "5").text)
        assertEquals("0", type("0", "0").text)
    }

    @Test fun `decimals are limited by currency`() {
        assertEquals("1.25", type("1", ".", "259").text)
        assertEquals("12", AmountInput(decimals = 0).dot().digit('1').digit('2').text)
    }

    @Test fun `dot twice is ignored and dot first gives zero`() {
        assertEquals("0.5", type(".", ".", "5").text)
    }

    @Test fun `backspace removes the last character`() {
        assertEquals("52", type("525", "<").text)
        assertTrue(type("4", "<").isEmpty)
        assertNull(type("4", "<").minorUnits())
    }

    @Test fun `minus toggles sign`() {
        assertEquals(-1_500L, type("-", "15").minorUnits())
        assertEquals(1_500L, type("15", "-", "-").minorUnits())
        assertNull(type("-").minorUnits())
    }

    @Test fun `caps integer digits`() {
        val big = type("9".repeat(15))
        assertEquals(AmountInput.MAX_INT_DIGITS, big.text.length)
        assertEquals(99_999_999_999_900L, big.minorUnits())
    }

    @Test fun `trailing dot still parses`() {
        assertEquals(1_200L, type("12", ".").minorUnits())
    }

    @Test fun `display groups thousands`() {
        assertEquals("1,234,567.5", type("1234567", ".", "5").display())
        assertEquals("-1,000", type("-", "1000").display())
    }

    @Test fun `from minor strips trailing zeros`() {
        assertEquals("525", AmountInput.fromMinor(52_500, 2).text)
        assertEquals("525.5", AmountInput.fromMinor(52_550, 2).text)
        assertEquals("0.05", AmountInput.fromMinor(5, 2).text)
        assertEquals("-12", AmountInput.fromMinor(-1_200, 2).text)
        assertEquals("", AmountInput.fromMinor(0, 2).text)
    }
}
