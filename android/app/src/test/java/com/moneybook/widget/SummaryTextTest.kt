package com.moneybook.widget

import com.moneybook.domain.Mom
import com.moneybook.shared.money.CurrencySpec
import org.junit.Assert.assertEquals
import org.junit.Test

class SummaryTextTest {
    private val czk = CurrencySpec.of("CZK")

    @Test fun `the change reads like Realbyte's`() {
        assertEquals("▼ 50.00 (50%)", momText(Mom(-5_000, 50, good = true), czk))
        assertEquals("▲ 9,906.00 (52%)", momText(Mom(990_600, 52, good = false), czk))
        assertEquals("▲ 500.00", momText(Mom(50_000, null, good = true), czk))
        assertEquals("0.00", momText(Mom(0, null, null), czk))
    }

    @Test fun `the tone follows what is good for the user`() {
        assertEquals(MomTone.GOOD, Mom(-5_000, 50, good = true).tone())
        assertEquals(MomTone.BAD, Mom(990_600, 52, good = false).tone())
        assertEquals(MomTone.NONE, Mom(0, null, null).tone())
    }

    @Test fun `a new widget opens on Total`() {
        assertEquals(SummaryTab.TOTAL, SummaryTab.from(null))
        assertEquals(SummaryTab.TOTAL, SummaryTab.from("SOMETHING_OLD"))
        assertEquals(SummaryTab.EXPENSES, SummaryTab.from("EXPENSES"))
    }
}
