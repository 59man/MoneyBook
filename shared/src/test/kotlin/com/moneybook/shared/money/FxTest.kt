package com.moneybook.shared.money

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FxTest {
    private val fx = Fx("USD", mapOf("EUR" to BigDecimal("1.1"), "JPY" to BigDecimal("0.0067")))

    @Test fun `measured on Realbyte`() {
        assertEquals(2200, fx.toMain(2000, "EUR"))           // €20 at 1.1 → $22.00
        assertEquals(4545, fx.fromMain(5000, "EUR"))         // $50 → €45.45
        val fx12 = Fx("USD", mapOf("EUR" to BigDecimal("1.2")))
        assertEquals(417, fx12.fromMain(500, "EUR"))         // $5 → €4.17
        assertEquals(17454, fx12.toMain(14545, "EUR"))       // €145.45 → $174.54
        assertEquals("1.3", Fx.formatRate(Fx.rateOf(2000, "EUR", 2600, "USD")))
    }

    @Test fun `zero decimal currencies and half up`() {
        assertEquals(670, fx.toMain(1000, "JPY"))             // ¥1000 × 0.0067 = $6.70
        assertEquals(149, fx.fromMain(100, "JPY"))           // $1 / 0.0067 = 149.25 → ¥149
        assertEquals(6, Fx("CZK", mapOf("EUR" to BigDecimal("0.025"))).toMain(250, "EUR")) // 2.50 × 0.025 = 0.0625 → 0.06
    }

    @Test fun `same currency and unknown rate count one to one`() {
        assertEquals(1234, fx.convert(1234, "EUR", "EUR"))
        assertEquals(1234, fx.toMain(1234, "XXX"))
        assertEquals(2600, Fx.mainAt(2000, "EUR", BigDecimal("1.3"), "USD"))
    }

    @Test fun `rate text`() {
        assertEquals("1.0", Fx.formatRate(BigDecimal.ONE))
        assertEquals("1.25", Fx.formatRate(BigDecimal("1.2500")))
        assertEquals(BigDecimal("1.1"), Fx.parseRate("1.1"))
        assertNull(Fx.parseRate("0"))
        assertNull(Fx.parseRate(""))
        assertNull(Fx.parseRate("1.12345678901"))            // more than 10 decimals
    }
}
