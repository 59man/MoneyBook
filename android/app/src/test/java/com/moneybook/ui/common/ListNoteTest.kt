package com.moneybook.ui.common

import com.moneybook.data.db.TxnRow
import com.moneybook.shared.model.TxnType
import org.junit.Assert.assertEquals
import org.junit.Test

class ListNoteTest {
    private fun row(note: String, index: Int? = null, total: Int? = null) = TxnRow(
        id = "a", type = TxnType.EXPENSE, dateTime = "2026-10-06T12:00:00.000", amount = 100, toAmount = null, amountMain = 100,
        currency = "CZK", fee = 0, note = note, description = "", accountId = "cash", accountName = "Cash", toAccountId = null,
        toAccountName = null, categoryId = null, categoryName = null, parentCategoryName = null,
        installmentTotal = total, installmentIndex = index,
    )

    @Test fun `installment parts show n of N after the note`() {
        assertEquals("Telefon 2/6", row("Telefon", 2, 6).listNote())
        assertEquals("1/3", row("", 1, 3).listNote())
        assertEquals("Oběd", row("Oběd").listNote())
    }
}
