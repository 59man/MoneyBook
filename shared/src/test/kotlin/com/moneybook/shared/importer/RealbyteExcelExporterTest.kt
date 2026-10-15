package com.moneybook.shared.importer

import com.moneybook.shared.model.TxnType
import com.moneybook.shared.xlsx.Cell
import com.moneybook.shared.xlsx.XlsxReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class RealbyteExcelExporterTest {
    private val t = LocalDateTime.of(2026, 9, 26, 18, 40, 12, 345_000_000)

    private fun export(txns: List<ExportTxn>): ByteArray =
        ByteArrayOutputStream().also { RealbyteExcelExporter.write(txns, "CZK", it) }.toByteArray()

    @Test fun `header matches Realbyte's layout with the main currency column`() {
        val rows = XlsxReader.readFirstSheet(ByteArrayInputStream(export(emptyList())))
        assertEquals(
            listOf("Date", "Account", "Category", "Subcategory", "Note", "CZK", "Income/Expense", "Description", "Amount", "Currency", "Account"),
            rows[0].map { (it as Cell.Text).value },
        )
    }

    @Test fun `export re-imports to the same entries`() {
        val txns = listOf(
            ExportTxn(TxnType.EXPENSE, t, "Cash", null, "🍜 Food", "Lunch", " Trh & <zelenina> \"x\" ", "desc, with; commas", 52_550, "CZK", 52_550),
            ExportTxn(TxnType.INCOME, t.minusDays(1), "Bank", null, "💰 Salary", null, "", "", 5_230_000, "CZK", 5_230_000),
            ExportTxn(TxnType.EXPENSE, t.minusDays(2), "Cash", null, "🍜 Food", null, "refund", "", -1_000, "CZK", -1_000),
            ExportTxn(TxnType.TRANSFER, t.minusDays(3), "Cash", "Bank", null, null, "deposit", "", 100_000, "CZK", 100_000),
            ExportTxn(TxnType.EXPENSE, t.minusDays(4), "Revolut", null, "🎁 Gift", null, "", "", 2_550, "EUR", 63_750),
        )
        val batch = RealbyteExcelImporter.parse(ByteArrayInputStream(export(txns)))
        assertEquals("CZK", batch.mainCurrency)
        assertEquals(emptyList(), batch.skipped)
        fun ImportedTxn.view() = listOf(type, dateTime, accountName, toAccountName, categoryName, subcategoryName, note, description, amountMinor, currency)
        // The importer trims text cells, so surrounding spaces do not survive a round trip.
        fun ExportTxn.view() = listOf(type, dateTime, accountName, toAccountName, categoryName, subcategoryName, note.trim(), description.trim(), amountMinor, currency)
        assertEquals(txns.map { it.view() }, batch.txns.map { it.view() })
    }

    /** Realbyte's rb_eur20: a €20 expense from a USD account at the entry's own rate 1.3. */
    @Test fun `a sub-currency entry keeps its main and account amounts through a round trip`() {
        val euro = ExportTxn(
            TxnType.EXPENSE, t, "Cash", null, "Other", null, "", "", 2_000, "EUR", 2_600,
            accountAmountMinor = 2_600, accountCurrency = "USD",
        )
        val bytes = ByteArrayOutputStream().also { RealbyteExcelExporter.write(listOf(euro), "USD", it) }.toByteArray()
        val row = XlsxReader.readFirstSheet(ByteArrayInputStream(bytes))[1]
        assertEquals(26.0, (row[5] as Cell.Num).value)
        assertEquals("20.00", (row[8] as Cell.Text).value)
        assertEquals(26.0, (row[10] as Cell.Num).value)
        val back = RealbyteExcelImporter.parse(ByteArrayInputStream(bytes)).txns.single()
        assertEquals(2_000L, back.amountMinor)
        assertEquals(2_600L, back.amountMainMinor)
        assertEquals(2_600L, back.accountAmountMinor)
    }

    @Test fun `a transfer's In row carries the receiving side`() {
        val tr = ExportTxn(
            TxnType.TRANSFER, t, "Cash", "EuroSave", null, null, "", "", 5_000, "USD", 5_000,
            toAmountMinor = 4_545, toAccountCurrency = "EUR",
        )
        val bytes = ByteArrayOutputStream().also { RealbyteExcelExporter.write(listOf(tr), "USD", it) }.toByteArray()
        val rows = XlsxReader.readFirstSheet(ByteArrayInputStream(bytes))
        assertEquals(50.0, (rows[1][10] as Cell.Num).value)
        assertEquals(45.45, (rows[2][10] as Cell.Num).value)
        // A single-currency entry re-imports with no account side of its own.
        assertEquals(null, RealbyteExcelImporter.parse(ByteArrayInputStream(bytes)).txns.single().accountAmountMinor)
    }

    @Test fun `amounts are written in the currency's decimals`() {
        assertEquals("525.00", RealbyteExcelExporter.decimal(52_500, 2))
        assertEquals("-0.05", RealbyteExcelExporter.decimal(-5, 2))
        assertEquals("1500", RealbyteExcelExporter.decimal(1_500, 0))
    }
}
