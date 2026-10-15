package com.moneybook.shared.xlsx

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XlsxWriterTest {
    private fun roundTrip(rows: List<List<OutCell?>>): List<List<Cell?>> {
        val out = ByteArrayOutputStream()
        XlsxWriter.write("MoneyBook", rows, out)
        return XlsxReader.readFirstSheet(ByteArrayInputStream(out.toByteArray()))
    }

    @Test fun `written sheet reads back with text, numbers and dates`() {
        val at = LocalDateTime.of(2026, 9, 26, 18, 40, 12, 345_000_000)
        val rows = roundTrip(listOf(
            listOf(OutCell.Text("Date"), OutCell.Text("Note"), OutCell.Text("CZK")),
            listOf(OutCell.Date(at), OutCell.Text(" Trh & <zelenina> \"🍜\" "), OutCell.Number("-525.50")),
            listOf(null, OutCell.Text(""), OutCell.Number("0")),
        ))
        assertEquals(Cell.Text("Date"), rows[0][0])
        val date = rows[1][0] as Cell.Num
        assertTrue(date.isDate)
        assertEquals(at, ExcelDates.toLocalDateTime(date.value))
        assertEquals(Cell.Text(" Trh & <zelenina> \"🍜\" "), rows[1][1])
        assertEquals(Cell.Num(-525.5, false), rows[1][2])
        assertNull(rows[2][0])
        assertEquals(Cell.Text(""), rows[2][1])
    }

    @Test fun `control characters are dropped instead of breaking the XML`() {
        val rows = roundTrip(listOf(listOf(OutCell.Text("a\u0001b\tc"))))
        assertEquals(Cell.Text("ab\tc"), rows[0][0])
    }

    @Test fun `numbers must be plain decimals`() {
        assertFailsWith<IllegalArgumentException> { OutCell.Number("1e5") }
        assertFailsWith<IllegalArgumentException> { OutCell.Number("12</v>") }
    }

    @Test fun `column names follow Excel lettering`() {
        assertEquals(listOf("A", "Z", "AA", "AZ", "BA", "XFD"), listOf(0, 25, 26, 51, 52, 16_383).map(XlsxWriter::columnName))
    }
}
