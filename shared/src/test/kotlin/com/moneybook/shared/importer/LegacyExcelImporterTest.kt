package com.moneybook.shared.importer

import com.moneybook.shared.model.TxnType
import com.moneybook.shared.xlsx.Cell
import com.moneybook.shared.xlsx.ExcelDates
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LegacyExcelImporterTest {
    private val header = listOf("Date", "Income/Expenses", "Category", "Memo", "Amount").map { Cell.Text(it) }

    private fun row(date: String, kind: String, category: String, memo: String?, amount: String): List<Cell?> =
        listOf(Cell.Text(date), Cell.Text(kind), Cell.Text(category), memo?.let { Cell.Text(it) }, Cell.Text(amount))

    @Test fun `reads rows the way the old app wrote them`() {
        val batch = LegacyExcelImporter.parse(listOf(
            header,
            row("2023-02-28", "Expenses", "Others", "drobné na parkování ", "-6"),
            row("2019-08-01", "Expenses", "Sport", null, "-89"),
            row("2020-01-15", "Income", "vrácení zálohy ", "", "1500"),
        ))
        assertEquals(emptyList(), batch.skipped)
        val (tip, sport, parents) = batch.rows
        assertEquals(LegacyRow(2, tip.key, LocalDate.of(2023, 2, 28), TxnType.EXPENSE, "Others", "drobné na parkování", 600), tip)
        assertEquals(8_900L, sport.amountMinor)
        assertEquals("", sport.memo)
        assertEquals(TxnType.INCOME, parents.type)
        assertEquals("vrácení zálohy", parents.category)
        assertEquals(150_000L, parents.amountMinor)
    }

    @Test fun `a positive expense is a refund`() {
        val batch = LegacyExcelImporter.parse(listOf(header, row("2021-05-05", "Expenses", "Food", "vratka", "50")))
        assertEquals(-5_000L, batch.rows.single().amountMinor)
    }

    @Test fun `decimal commas, dotted dates and numeric cells are accepted`() {
        val numericRow: List<Cell?> = listOf(
            Cell.Num(ExcelDates.toSerial(LocalDateTime.of(2021, 5, 6, 0, 0)), isDate = true),
            Cell.Text("Expenses"), Cell.Text("Food"), null, Cell.Num(-3.0, isDate = false),
        )
        val batch = LegacyExcelImporter.parse(listOf(header, row("5.5.2021", "Expenses", "Food", "", "-12,50"), numericRow))
        assertEquals(emptyList(), batch.skipped)
        assertEquals(listOf(1_250L, 300L), batch.rows.map { it.amountMinor })
        assertEquals(listOf(LocalDate.of(2021, 5, 5), LocalDate.of(2021, 5, 6)), batch.rows.map { it.date })
    }

    @Test fun `bad rows are reported with their row numbers and blank rows are ignored`() {
        val batch = LegacyExcelImporter.parse(listOf(
            header,
            row("yesterday", "Expenses", "Food", "", "-1"),
            listOf(null, null, null, null, null),
            row("2021-01-01", "Transfer", "Food", "", "-1"),
            row("2021-01-01", "Expenses", " ", "x", "-1"),
            row("2021-01-01", "Expenses", "Food", "x", "abc"),
        ))
        assertEquals(emptyList(), batch.rows)
        assertEquals(
            listOf(
                SkippedRow(2, "Unreadable date"),
                SkippedRow(4, "Unknown type 'Transfer'"),
                SkippedRow(5, "Missing category"),
                SkippedRow(6, "Unreadable amount 'abc'"),
            ),
            batch.skipped,
        )
    }

    @Test fun `identical rows get distinct but stable keys`() {
        val rows = listOf(header, row("2021-01-01", "Expenses", "Food", "", "-90"), row("2021-01-01", "Expenses", "Food", "", "-90"))
        val first = LegacyExcelImporter.parse(rows).rows.map { it.key }
        assertEquals(2, first.size)
        assertNotEquals(first[0], first[1])
        assertEquals(first, LegacyExcelImporter.parse(rows).rows.map { it.key })
    }

    @Test fun `the singular Income-Expense header is accepted`() {
        val singular = listOf("Date", "Income/Expense", "Category", "Memo", "Amount").map { Cell.Text(it) }
        assertEquals(1, LegacyExcelImporter.parse(listOf(singular, row("2021-01-01", "Income", "Salary", "", "10"))).rows.size)
    }

    @Test fun `other sheets are rejected`() {
        val e = assertFailsWith<ImportFormatException> { LegacyExcelImporter.parse(listOf(listOf(Cell.Text("Date"), Cell.Text("Account")))) }
        assertTrue(e.message!!.startsWith("Not an old-app export. Missing columns:"))
    }
}
