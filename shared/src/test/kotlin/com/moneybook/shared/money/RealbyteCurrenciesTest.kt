package com.moneybook.shared.money

import kotlin.test.Test
import kotlin.test.assertEquals

class RealbyteCurrenciesTest {
    @Test fun `list matches Realbyte`() {
        assertEquals(123, RealbyteCurrencies.all.size)
        val czk = RealbyteCurrencies.find("CZK")!!
        assertEquals("CZK - Czech (Kč)", czk.label)
        assertEquals("Czech", czk.name)
        assertEquals("Kč", czk.symbol)
        assertEquals("Angola", RealbyteCurrencies.find("AOA")!!.name) // "AOA - Angola(Kz)" has no space
    }

    @Test fun `defaults measured on Realbyte`() {
        assertEquals(CurrencySpec("CZK", "Kč", SymbolPosition.END, 2), RealbyteCurrencies.defaultSpec("CZK"))
        assertEquals(CurrencySpec("USD", "$", SymbolPosition.START, 2), RealbyteCurrencies.defaultSpec("USD"))
        assertEquals(CurrencySpec("EUR", "€", SymbolPosition.START, 2), RealbyteCurrencies.defaultSpec("EUR"))
        assertEquals(CurrencySpec("JPY", "JP￥", SymbolPosition.START, 0), RealbyteCurrencies.defaultSpec("JPY"))
        assertEquals(2, RealbyteCurrencies.defaultSpec("NTD").decimals) // not ISO
    }
}
