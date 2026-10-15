package com.moneybook.shared.xlsx

import java.io.OutputStream
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A cell to write. */
sealed interface OutCell {
    data class Text(val value: String) : OutCell

    /** A plain decimal such as "525.00" or "-15", so no Double rounding happens on the way out. */
    data class Number(val value: String) : OutCell {
        init {
            require(value.matches(Regex("-?[0-9]+(\\.[0-9]+)?"))) { "Not a plain decimal: $value" }
        }
    }

    /** Stored as an Excel serial number with a date-time format. */
    data class Date(val value: LocalDateTime) : OutCell
}

/**
 * Writes one worksheet as a minimal .xlsx: inline strings, numbers and one date-time style.
 * XlsxReader, Excel and LibreOffice all read it.
 */
object XlsxWriter {
    private const val MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val PACKAGE_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val XML_HEAD = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""
    private const val DATE_STYLE = 1

    fun write(sheetName: String, rows: List<List<OutCell?>>, out: OutputStream) {
        val zip = ZipOutputStream(out)
        fun part(name: String, xml: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(xml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        part("[Content_Types].xml", CONTENT_TYPES)
        part("_rels/.rels", "$XML_HEAD<Relationships xmlns=\"$PACKAGE_REL_NS\"><Relationship Id=\"rId1\" Type=\"$REL_NS/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
        part("xl/workbook.xml", "$XML_HEAD<workbook xmlns=\"$MAIN_NS\" xmlns:r=\"$REL_NS\"><sheets><sheet name=\"${escape(sheetName)}\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>")
        part(
            "xl/_rels/workbook.xml.rels",
            "$XML_HEAD<Relationships xmlns=\"$PACKAGE_REL_NS\">" +
                "<Relationship Id=\"rId1\" Type=\"$REL_NS/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
                "<Relationship Id=\"rId2\" Type=\"$REL_NS/styles\" Target=\"styles.xml\"/></Relationships>",
        )
        part("xl/styles.xml", STYLES)
        part("xl/worksheets/sheet1.xml", sheet(rows))
        zip.finish()
    }

    /** 0 → "A", 25 → "Z", 26 → "AA". */
    fun columnName(index: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) {
            sb.append('A' + (n - 1) % 26)
            n = (n - 1) / 26
        }
        return sb.reverse().toString()
    }

    private fun sheet(rows: List<List<OutCell?>>): String {
        val sb = StringBuilder("$XML_HEAD<worksheet xmlns=\"$MAIN_NS\"><sheetData>")
        rows.forEachIndexed { r, row ->
            val rowNumber = r + 1
            sb.append("<row r=\"").append(rowNumber).append("\">")
            row.forEachIndexed { c, cell ->
                val ref = columnName(c) + rowNumber
                when (cell) {
                    null -> Unit
                    is OutCell.Text -> sb.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                        .append(escape(cell.value)).append("</t></is></c>")
                    is OutCell.Number -> sb.append("<c r=\"").append(ref).append("\"><v>").append(cell.value).append("</v></c>")
                    is OutCell.Date -> sb.append("<c r=\"").append(ref).append("\" s=\"").append(DATE_STYLE).append("\"><v>")
                        .append(ExcelDates.toSerial(cell.value)).append("</v></c>")
                }
            }
            sb.append("</row>")
        }
        return sb.append("</sheetData></worksheet>").toString()
    }

    /** XML-escapes text and drops the control characters that XML 1.0 cannot carry. */
    private fun escape(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '"' -> sb.append("&quot;")
                ch < ' ' && ch != '\t' && ch != '\n' && ch != '\r' -> Unit
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private val CONTENT_TYPES = XML_HEAD +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
        "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
        "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
        "</Types>"

    /** Style 0 is plain; style 1 ([DATE_STYLE]) shows a date-time. */
    private val STYLES = XML_HEAD +
        "<styleSheet xmlns=\"$MAIN_NS\">" +
        "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"yyyy-mm-dd hh:mm:ss\"/></numFmts>" +
        "<fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
        "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>" +
        "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
        "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
        "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
        "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/></cellXfs>" +
        "</styleSheet>"
}
