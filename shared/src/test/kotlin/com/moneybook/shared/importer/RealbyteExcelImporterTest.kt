package com.moneybook.shared.importer

import com.moneybook.shared.model.TxnType
import com.moneybook.shared.xlsx.Cell
import com.moneybook.shared.xlsx.ExcelDates
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RealbyteExcelImporterTest {
    private val header = listOf("Date", "Account", "Category", "Subcategory", "Note", "CZK", "Income/Expense", "Description", "Amount", "Currency", "Account")
        .map { Cell.Text(it) }

    private fun row(
        date: Cell?, account: String, category: String, sub: String, note: String,
        main: Double, kind: String, description: String, amount: String, currency: String = "CZK",
    ): List<Cell?> = listOf(
        date, Cell.Text(account), Cell.Text(category), Cell.Text(sub), Cell.Text(note),
        Cell.Num(main, false), Cell.Text(kind), Cell.Text(description), Cell.Text(amount), Cell.Text(currency), Cell.Num(main, false),
    )

    private fun at(dt: LocalDateTime) = Cell.Num(ExcelDates.toSerial(dt), isDate = true)
    private val t1 = LocalDateTime.of(2026, 9, 26, 18, 40, 12, 345_000_000)
    private val t2 = LocalDateTime.of(2026, 9, 1, 8, 0)

    @Test fun `rejects files without Realbyte columns`() {
        val e = assertFailsWith<ImportFormatException> {
            RealbyteExcelImporter.parse(listOf(listOf(Cell.Text("Date"), Cell.Text("Memo"))))
        }
        assertTrue(e.message!!.contains("Missing columns"))
    }

    @Test fun `parses expense and income rows`() {
        val batch = RealbyteExcelImporter.parse(listOf(
            header,
            row(at(t1), "Cash", "🍜 Food", "", "Zelenina z trhu", 525.0, "Expense", "", "525.0"),
            row(at(t2), "Cash", "💰 Salary", "", "Výplata", 47100.0, "Income", "září", "47100.0"),
        ))
        assertEquals("CZK", batch.mainCurrency)
        assertEquals(2, batch.txns.size)
        val expense = batch.txns[0]
        assertEquals(TxnType.EXPENSE, expense.type)
        assertEquals(t1, expense.dateTime)
        assertEquals("Cash", expense.accountName)
        assertEquals("🍜 Food", expense.categoryName)
        assertNull(expense.subcategoryName)
        assertEquals("Zelenina z trhu", expense.note)
        assertEquals(52_500, expense.amountMinor)
        assertEquals("CZK", expense.currency)
        val income = batch.txns[1]
        assertEquals(TxnType.INCOME, income.type)
        assertEquals("září", income.description)
        assertEquals(4_710_000, income.amountMinor)
        assertTrue(batch.skipped.isEmpty())
    }

    @Test fun `subcategory is kept`() {
        val batch = RealbyteExcelImporter.parse(listOf(header, row(at(t1), "Cash", "🍜 Food", "Lunch", "", 10.0, "Expense", "", "10")))
        assertEquals("Lunch", batch.txns.single().subcategoryName)
    }

    @Test fun `blank rows are ignored and bad rows are reported`() {
        val batch = RealbyteExcelImporter.parse(listOf(
            header,
            listOf(null, Cell.Text(" "), null),
            row(Cell.Text("yesterday"), "Cash", "🍜 Food", "", "", 1.0, "Expense", "", "1"),
            row(at(t1), "Cash", "🍜 Food", "", "", 1.0, "Expense", "", "abc"),
            row(at(t1), "", "🍜 Food", "", "", 1.0, "Expense", "", "1"),
        ))
        assertEquals(0, batch.txns.size)
        assertEquals(
            listOf(SkippedRow(3, "Unreadable date"), SkippedRow(4, "Unreadable amount 'abc'"), SkippedRow(5, "Missing account")),
            batch.skipped,
        )
    }

    @Test fun `unknown kind is skipped with row number`() {
        val batch = RealbyteExcelImporter.parse(listOf(header, row(at(t1), "Cash", "x", "", "", 1.0, "Refund?", "", "1")))
        assertEquals(listOf(SkippedRow(2, "Unknown type 'Refund?'")), batch.skipped)
    }

    @Test fun `transfer without counterpart account is skipped`() {
        val batch = RealbyteExcelImporter.parse(listOf(header, row(at(t1), "Cash", "", "", "", 1.0, "Transfer-Out", "", "1")))
        assertEquals(listOf(SkippedRow(2, "Missing transfer account")), batch.skipped)
    }

    @Test fun `text dates are accepted`() {
        val batch = RealbyteExcelImporter.parse(listOf(header, row(Cell.Text("09/26/2026 18:40:12"), "Cash", "🍜 Food", "", "", 1.0, "Expense", "", "1")))
        assertEquals(LocalDateTime.of(2026, 9, 26, 18, 40, 12), batch.txns.single().dateTime)
    }

    @Test fun `empty Amount column falls back to main currency column`() {
        val batch = RealbyteExcelImporter.parse(listOf(header, row(at(t1), "Cash", "🍜 Food", "", "", 15.5, "Expense", "", "")))
        assertEquals(1_550, batch.txns.single().amountMinor)
    }

    @Test fun `transfer out and in rows become one transfer`() {
        val batch = RealbyteExcelImporter.parse(listOf(
            header,
            row(at(t1), "Cash", "Bank", "", "deposit", 1000.0, "Transfer-Out", "", "1000"),
            row(at(t1), "Bank", "Cash", "", "deposit", 1000.0, "Transfer-In", "", "1000"),
            row(at(t2), "Savings", "Bank", "", "", 50.0, "Transfer-In", "", "50"),
        ))
        assertEquals(2, batch.txns.size)
        val out = batch.txns[0]
        assertEquals(TxnType.TRANSFER, out.type)
        assertEquals("Cash", out.accountName)
        assertEquals("Bank", out.toAccountName)
        assertNull(out.categoryName)
        val unmatchedIn = batch.txns[1]
        assertEquals("Bank", unmatchedIn.accountName)
        assertEquals("Savings", unmatchedIn.toAccountName)
    }

    @Test fun `each Transfer-Out mirrors only one identical Transfer-In`() {
        val batch = RealbyteExcelImporter.parse(listOf(
            header,
            row(at(t1), "Cash", "Bank", "", "", 1000.0, "Transfer-Out", "", "1000"),
            row(at(t1), "Bank", "Cash", "", "", 1000.0, "Transfer-In", "", "1000"),
            row(at(t1), "Bank", "Cash", "", "", 1000.0, "Transfer-In", "", "1000"),
        ))
        assertEquals(2, batch.txns.size)
        assertTrue(batch.txns.all { it.type == TxnType.TRANSFER && it.accountName == "Cash" && it.toAccountName == "Bank" })
        assertNotEquals(batch.txns[0].sourceKey, batch.txns[1].sourceKey)
    }

    @Test fun `identical rows get distinct but stable keys`() {
        val rows = listOf(
            header,
            row(at(t1), "Cash", "🍜 Food", "", "Káva", 15.0, "Expense", "", "15"),
            row(at(t1), "Cash", "🍜 Food", "", "Káva", 15.0, "Expense", "", "15"),
        )
        val first = RealbyteExcelImporter.parse(rows).txns.map { it.sourceKey }
        val second = RealbyteExcelImporter.parse(rows).txns.map { it.sourceKey }
        assertEquals(2, first.size)
        assertNotEquals(first[0], first[1])
        assertEquals(first, second)
    }
}
