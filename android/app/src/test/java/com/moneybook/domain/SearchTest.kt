package com.moneybook.domain

import com.moneybook.data.db.NoteUse
import com.moneybook.data.db.TxnRow
import com.moneybook.shared.model.TxnType
import com.moneybook.shared.model.TxnType.EXPENSE
import com.moneybook.shared.model.TxnType.INCOME
import com.moneybook.shared.model.TxnType.TRANSFER
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    private fun row(
        id: String, type: TxnType = EXPENSE, date: String = "2026-09-01", amount: Long = 1_000, note: String = "",
        description: String = "", account: String = "cash", to: String? = null, category: String? = null, parent: String? = null,
    ) = TxnRow(
        id = id, type = type, dateTime = "${date}T12:00:00.000", amount = amount, toAmount = null, amountMain = amount,
        currency = "CZK", fee = 0, note = note, description = description, accountId = account, accountName = account,
        toAccountId = to, toAccountName = to, categoryId = category, categoryName = category, parentCategoryName = parent,
        parentCategoryId = parent,
    )

    private val rows = listOf(
        row("1", note = "Nákup jídlo", category = "food"),
        row("2", note = "Oběd", description = "JÍDLO v práci", category = "lunch", parent = "food", date = "2026-08-15", amount = 17_400),
        row("3", note = "50% sleva", category = "shop", account = "card"),
        row("4", type = INCOME, note = "Výplata", category = "salary", amount = 4_000_000),
        row("5", type = TRANSFER, note = "Na kartu", account = "cash", to = "card", amount = 50_000),
    )

    private fun ids(filter: SearchFilter) = search(rows, filter).map { it.id }

    @Test fun `text matches note or description ignoring case, Czech letters included`() {
        assertEquals(listOf("1", "2"), ids(SearchFilter(text = "jídlo")))
        assertEquals(listOf("1", "2"), ids(SearchFilter(text = "  JÍDLO ")))
        assertEquals(listOf("3"), ids(SearchFilter(text = "%")))
        assertEquals(emptyList<String>(), ids(SearchFilter(text = "_")))
    }

    @Test fun `an empty search finds nothing`() {
        assertTrue(SearchFilter().isEmpty)
        assertEquals(emptyList<String>(), ids(SearchFilter(text = "   ")))
    }

    @Test fun `each filter narrows the results`() {
        assertEquals(listOf("2"), ids(SearchFilter(range = DateRange(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1)))))
        assertEquals(listOf("3", "5"), ids(SearchFilter(accountId = "card"))) // the transfer's target counts
        assertEquals(listOf("1", "2"), ids(SearchFilter(categoryId = "food"))) // with its subcategory
        assertEquals(listOf("2", "4", "5"), ids(SearchFilter(min = 10_000)))
        assertEquals(listOf("2"), ids(SearchFilter(min = 10_000, max = 20_000)))
        assertEquals(listOf("2"), ids(SearchFilter(text = "jídlo", min = 2_000)))
    }

    @Test fun `search totals split income, expenses and transfers`() {
        assertEquals(SearchTotals(income = 4_000_000, expense = 19_400, transfer = 50_000), searchTotals(rows))
    }

    @Test fun `suggestions rank by use then recency and skip what is typed`() {
        val uses = listOf(
            NoteUse("Market", uses = 2, lastUsed = "2026-09-01T10:00:00.000"),
            NoteUse("Meat", uses = 5, lastUsed = "2026-08-01T10:00:00.000"),
            NoteUse("Pin money", uses = 2, lastUsed = "2026-09-20T10:00:00.000"),
            NoteUse("Bread", uses = 9, lastUsed = "2026-09-25T10:00:00.000"),
            NoteUse("m", uses = 9, lastUsed = "2026-09-25T10:00:00.000"),
        )
        assertEquals(listOf("Meat", "Pin money", "Market"), rankSuggestions(uses, "M"))
        assertEquals(listOf("Meat"), rankSuggestions(uses, "m", limit = 1))
        assertEquals(emptyList<String>(), rankSuggestions(uses, " "))
        assertEquals(5, rankSuggestions(List(8) { NoteUse("note $it", 1, "2026-09-0${it + 1}T10:00:00.000") }, "note").size)
    }

    @Test fun `search totals follow the Transfer-Expense rule and keep plain transfers apart`() {
        val e = row("e", EXPENSE, amount = 100)
        val plain = row("p", TRANSFER, amount = 40)
        val toSave = row("s", TRANSFER, amount = 70).copy(toTransferExpense = true)
        val fromSave = row("f", TRANSFER, amount = 25).copy(fromTransferExpense = true)
        assertEquals(SearchTotals(income = 25, expense = 170, transfer = 40), searchTotals(listOf(e, plain, toSave, fromSave)))
    }
}
