package com.moneybook.shared.money

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MoneyFormatTest {
    private val czk = CurrencySpec.of("CZK")
    private val usd = CurrencySpec.of("USD")

    @Test fun `formats like Realbyte with symbol at end`() {
        assertEquals("525.00Kč", MoneyFormat.format(52_500, czk))
        assertEquals("1,256.00Kč", MoneyFormat.format(125_600, czk))
        assertEquals("0.00Kč", MoneyFormat.format(0, czk))
    }

    @Test fun `formats without symbol for summary rows`() {
        assertEquals("52,300.00", MoneyFormat.format(5_230_000, czk, withSymbol = false))
    }

    @Test fun `formats large and negative values`() {
        assertEquals("-4,715.00Kč", MoneyFormat.format(-471_500, czk))
        assertEquals("999,999,999,999.99Kč", MoneyFormat.format(99_999_999_999_999, czk))
        assertEquals("-$1,234.56", MoneyFormat.format(-123_456, usd))
    }

    @Test fun `symbol at start for dollars`() {
        assertEquals("$1,234.56", MoneyFormat.format(123_456, usd))
    }

    @Test fun `zero decimal currency`() {
        val jpy = CurrencySpec("JPY", "¥", SymbolPosition.START, 0)
        assertEquals("¥1,234", MoneyFormat.format(1_234, jpy))
    }

    @Test fun `parses decimal text into minor units`() {
        assertEquals(52_500, MoneyFormat.parseMinor("525.0", 2))
        assertEquals(-1_500, MoneyFormat.parseMinor("-15", 2))
        assertEquals(1, MoneyFormat.parseMinor("0.005", 2))
        assertEquals(12, MoneyFormat.parseMinor("12", 0))
        assertFailsWith<NumberFormatException> { MoneyFormat.parseMinor("abc", 2) }
    }

    @Test fun `groups digits`() {
        assertEquals("1,234,567", MoneyFormat.groupDigits("1234567"))
        assertEquals("123", MoneyFormat.groupDigits("123"))
        assertEquals("", MoneyFormat.groupDigits(""))
    }

    @Test fun `unknown currency falls back to java currency data`() {
        val chf = CurrencySpec.of("chf")
        assertEquals("CHF", chf.code)
        assertEquals(2, chf.decimals)
    }

    @Test fun `display decimals differ from stored decimals`() {
        val czk = CurrencySpec("CZK", "Kč", SymbolPosition.END, 2)
        assertEquals("1,235Kč", MoneyFormat.format(123_450, czk.copy(display = 0)))
        assertEquals("1,234.5Kč", MoneyFormat.format(123_449, czk.copy(display = 1)))
        assertEquals("-1,234.500Kč", MoneyFormat.format(-123_450, czk.copy(display = 3)))
    }
}
