package com.moneybook.domain

import com.moneybook.shared.money.Fx
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

/** The numbers measured on Realbyte (main USD, EUR at 1.1). */
class EntryAmountsTest {
    private val fx = Fx("USD", mapOf("EUR" to BigDecimal("1.1")))

    @Test fun subCurrencyExpenseFromAMainCurrencyAccount() =
        assertEquals(StoredAmounts(2_000, "EUR", 2_200, 2_200, null), entryAmounts(2_000, "EUR", null, "USD", null, fx))

    @Test fun rateSheetOverride() =
        assertEquals(StoredAmounts(2_000, "EUR", 2_600, 2_600, null), entryAmounts(2_000, "EUR", 2_600, "USD", null, fx))

    @Test fun mainCurrencyExpenseFromAEuroAccount() =
        assertEquals(StoredAmounts(500, "USD", 500, 455, null), entryAmounts(500, "USD", null, "EUR", null, fx))

    @Test fun dollarToEuroTransfer() =
        assertEquals(StoredAmounts(5_000, "USD", 5_000, null, 4_545), entryAmounts(5_000, "USD", null, "USD", "EUR", fx))

    @Test fun singleCurrencyStaysAsBefore() =
        assertEquals(StoredAmounts(5_000, "CZK", 5_000, null, null), entryAmounts(5_000, "CZK", null, "CZK", "CZK", Fx("CZK", emptyMap())))
}
