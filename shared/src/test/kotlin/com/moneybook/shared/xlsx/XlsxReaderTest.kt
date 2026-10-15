package com.moneybook.shared.xlsx

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XlsxReaderTest {

    private fun zip(vararg parts: Pair<String, String>): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { z ->
            for ((name, xml) in parts) {
                z.putNextEntry(ZipEntry(name))
                z.write(xml.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }

    private val ns = """xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships""""

    /** Same structure as Realbyte's export: sheet via rId3, shared strings, custom date format 165 on style index 2. */
    private fun realbyteLike(sheetData: String, strings: List<String>) = zip(
        "xl/workbook.xml" to """<?xml version="1.0" encoding="UTF-8"?><workbook $ns><sheets><sheet name="Money Manager" r:id="rId3" sheetId="1"/></sheets></workbook>""",
        "xl/_rels/workbook.xml.rels" to """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Target="sharedStrings.xml" Type="x"/><Relationship Id="rId3" Target="worksheets/sheet1.xml" Type="y"/></Relationships>""",
        "xl/styles.xml" to """<?xml version="1.0" encoding="UTF-8"?><styleSheet $ns><numFmts count="1"><numFmt numFmtId="165" formatCode="MM/dd/yyyy HH:mm:ss"/></numFmts><cellStyleXfs count="1"><xf numFmtId="0"/></cellStyleXfs><cellXfs count="3"><xf numFmtId="0"/><xf numFmtId="0"/><xf numFmtId="165"/></cellXfs></styleSheet>""",
        "xl/sharedStrings.xml" to """<?xml version="1.0" encoding="UTF-8"?><sst $ns>${strings.joinToString("") { "<si><t xml:space=\"preserve\">$it</t></si>" }}</sst>""",
        "xl/worksheets/sheet1.xml" to """<?xml version="1.0" encoding="UTF-8"?><worksheet $ns><sheetData>$sheetData</sheetData></worksheet>""",
    )

    @Test fun `reads shared strings, numbers and date styled numbers`() {
        val when_ = LocalDateTime.of(2026, 9, 26, 18, 40, 12, 345_000_000)
        val serial = ExcelDates.toSerial(when_)
        val input = realbyteLike(
            sheetData = """<row r="1"><c r="A1" s="1" t="s"><v>0</v></c><c r="B1" s="1" t="s"><v>1</v></c></row>""" +
                """<row r="2"><c r="A2" s="2" t="n"><v>$serial</v></c><c r="B2" t="s"><v>2</v></c><c r="C2" t="n"><v>525.0</v></c></row>""",
            strings = listOf("Date", "Account", "🍜 Food"),
        )
        val rows = XlsxReader.readFirstSheet(input)
        assertEquals(listOf(Cell.Text("Date"), Cell.Text("Account")), rows[0])
        val date = rows[1][0] as Cell.Num
        assertTrue(date.isDate)
        assertEquals(when_, ExcelDates.toLocalDateTime(date.value))
        assertEquals(Cell.Text("🍜 Food"), rows[1][1])
        assertEquals(Cell.Num(525.0, isDate = false), rows[1][2])
    }

    @Test fun `missing cells are null at the right column and skipped rows are empty`() {
        val input = realbyteLike(
            sheetData = """<row r="1"><c r="A1" t="inlineStr"><is><t>a</t></is></c><c r="C1" t="b"><v>1</v></c></row>""" +
                """<row r="3"><c r="B3" t="str"><v>formula</v></c></row>""",
            strings = emptyList(),
        )
        val rows = XlsxReader.readFirstSheet(input)
        assertEquals(listOf(Cell.Text("a"), null, Cell.Bool(true)), rows[0])
        assertEquals(emptyList(), rows[1])
        assertNull(rows[2][0])
        assertEquals(Cell.Text("formula"), rows[2][1])
    }

    @Test fun `rich text shared strings are concatenated`() {
        val input = zip(
            "xl/sharedStrings.xml" to """<sst $ns><si><r><t>Hel</t></r><r><t>lo</t></r></si></sst>""",
            "xl/worksheets/sheet1.xml" to """<worksheet $ns><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c></row></sheetData></worksheet>""",
        )
        assertEquals(Cell.Text("Hello"), XlsxReader.readFirstSheet(input)[0][0])
    }

    @Test fun `reads zips whose data descriptors report zero sizes like Realbyte exports`() {
        val good = zip(
            "xl/sharedStrings.xml" to """<sst $ns><si><t>Cash</t></si></sst>""",
            "xl/worksheets/sheet1.xml" to """<worksheet $ns><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c></row></sheetData></worksheet>""",
        ).readBytes()
        // Realbyte's writer leaves 0 in each data descriptor's size fields; only the central directory is right.
        val broken = good.copyOf()
        var i = 0
        while (i + 16 <= broken.size) {
            if (broken[i] == 0x50.toByte() && broken[i + 1] == 0x4b.toByte() && broken[i + 2] == 0x07.toByte() && broken[i + 3] == 0x08.toByte()) {
                for (k in 8 until 16) broken[i + k] = 0
                i += 16
            } else {
                i++
            }
        }
        assertEquals(Cell.Text("Cash"), XlsxReader.readFirstSheet(ByteArrayInputStream(broken))[0][0])
    }

    @Test fun `spool file for imports is readable only by its owner`() {
        val file = XlsxReader.createSpoolFile()
        try {
            val perms = java.nio.file.Files.getPosixFilePermissions(file.toPath())
            assertEquals(
                setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE),
                perms,
            )
        } finally {
            file.delete()
        }
    }

    @Test fun `row numbers beyond Excel's limit are rejected`() {
        val input = realbyteLike("""<row r="2000000"><c r="A2000000" t="s"><v>0</v></c></row>""", listOf("x"))
        val e = assertFailsWith<XlsxException> { XlsxReader.readFirstSheet(input) }
        assertEquals("Row number out of range", e.message)
    }

    @Test fun `column references beyond Excel's limit are rejected`() {
        val input = realbyteLike("""<row r="1"><c r="AAAAA1" t="s"><v>0</v></c></row>""", listOf("x"))
        val e = assertFailsWith<XlsxException> { XlsxReader.readFirstSheet(input) }
        assertEquals("Column out of range", e.message)
    }

    @Test fun `unpacked XML is capped in total, not only per part`() {
        val big = "<!--" + "x".repeat(600) + "-->"
        val input = zip("xl/a.xml" to big, "xl/b.xml" to big)
        val e = assertFailsWith<XlsxException> { XlsxReader.readFirstSheet(input, maxTotalBytes = 1_000) }
        assertEquals("Spreadsheet is too large", e.message)
    }

    @Test fun `non xlsx input is rejected`() {
        assertFailsWith<XlsxException> { XlsxReader.readFirstSheet(ByteArrayInputStream("hello".toByteArray())) }
    }

    @Test fun `detects date number formats`() {
        assertTrue(XlsxReader.isDateFormat("MM/dd/yyyy HH:mm:ss"))
        assertTrue(XlsxReader.isDateFormat("d.m.yyyy"))
        assertTrue(!XlsxReader.isDateFormat("0.00"))
        assertTrue(!XlsxReader.isDateFormat("#,##0\\ \"Kč\""))
        assertTrue(!XlsxReader.isDateFormat("[Red]0.00"))
    }

    @Test fun `excel serial dates`() {
        assertEquals(LocalDateTime.of(2023, 3, 15, 0, 0), ExcelDates.toLocalDateTime(45000.0))
        val t = LocalDateTime.of(2019, 8, 1, 23, 59, 59, 999_000_000)
        assertEquals(t, ExcelDates.toLocalDateTime(ExcelDates.toSerial(t)))
    }

    @Test fun `column letters map to indexes`() {
        assertEquals(0, XlsxReader.columnIndex("A1"))
        assertEquals(10, XlsxReader.columnIndex("K2"))
        assertEquals(26, XlsxReader.columnIndex("AA10"))
    }
}
