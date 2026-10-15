package com.moneybook.shared.xlsx

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.time.Duration
import java.time.LocalDateTime
import java.util.zip.ZipException
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

sealed interface Cell {
    data class Text(val value: String) : Cell
    data class Num(val value: Double, val isDate: Boolean) : Cell
    data class Bool(val value: Boolean) : Cell
}

class XlsxException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Excel stores dates as days since 1899-12-30 (1900 date system). */
object ExcelDates {
    private val EPOCH: LocalDateTime = LocalDateTime.of(1899, 12, 30, 0, 0)

    fun toLocalDateTime(serial: Double): LocalDateTime =
        EPOCH.plus(Duration.ofMillis(Math.round(serial * 86_400_000.0)))

    fun toSerial(dateTime: LocalDateTime): Double =
        Duration.between(EPOCH, dateTime).toMillis() / 86_400_000.0
}

/**
 * Minimal reader for the first worksheet of an .xlsx file. Enough for Realbyte exports and
 * LibreOffice conversions: shared/inline strings, numbers, booleans, date-styled numbers.
 * Works on the JVM and on Android (SAX only).
 */
object XlsxReader {
    private const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val DEFAULT_SHEET = "xl/worksheets/sheet1.xml"
    private const val MAX_PART_BYTES = 64L * 1024 * 1024
    private const val MAX_FILE_BYTES = 256L * 1024 * 1024
    private const val MAX_TOTAL_PART_BYTES = 128L * 1024 * 1024
    /** Excel's own sheet limits; anything beyond them is a crafted or broken file. */
    private const val MAX_ROWS = 1_048_576
    private const val MAX_COLUMNS = 16_384
    private val BUILTIN_DATE_FORMATS = (14..22).toSet() + setOf(45, 46, 47)

    fun readFirstSheet(input: InputStream): List<List<Cell?>> = readFirstSheet(input, MAX_TOTAL_PART_BYTES)

    internal fun readFirstSheet(input: InputStream, maxTotalBytes: Long): List<List<Cell?>> {
        val parts = unzipXmlParts(input, maxTotalBytes)
        if (parts.isEmpty()) throw XlsxException("Not an .xlsx file")
        val sheetPath = firstSheetPath(parts)
        val shared = parts["xl/sharedStrings.xml"]?.let(::parseSharedStrings) ?: emptyList()
        val dateStyles = parts["xl/styles.xml"]?.let(::parseDateStyles) ?: emptySet()
        val sheet = parts[sheetPath] ?: throw XlsxException("Worksheet $sheetPath not found")
        return parseSheet(sheet, shared, dateStyles)
    }

    internal fun isDateFormat(code: String): Boolean {
        val cleaned = code
            .replace(Regex("\"[^\"]*\""), "")
            .replace(Regex("\\[[^\\]]*\\]"), "")
            .lowercase()
        return Regex("[ydhs]").containsMatchIn(cleaned)
    }

    internal fun columnIndex(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (ch !in 'A'..'Z') break
            n = n * 26 + (ch - 'A' + 1)
        }
        return n - 1
    }

    /**
     * Reads the zip through its central directory (ZipFile). ZipInputStream would trust each entry's data
     * descriptor, and Realbyte's exports write zeros there, so the stream is spooled to a temp file first.
     */
    private fun unzipXmlParts(input: InputStream, maxTotalBytes: Long): Map<String, ByteArray> {
        val tmp = createSpoolFile()
        try {
            tmp.outputStream().use { out -> input.copyLimited(out, MAX_FILE_BYTES) }
            val parts = HashMap<String, ByteArray>()
            var total = 0L
            try {
                ZipFile(tmp).use { zip ->
                    for (entry in zip.entries()) {
                        val name = entry.name.removePrefix("/")
                        if (entry.isDirectory || !(name.endsWith(".xml") || name.endsWith(".rels"))) continue
                        val bytes = ByteArrayOutputStream()
                        zip.getInputStream(entry).use { it.copyLimited(bytes, minOf(MAX_PART_BYTES, maxTotalBytes - total)) }
                        total += bytes.size()
                        parts[name] = bytes.toByteArray()
                    }
                }
            } catch (e: ZipException) {
                throw XlsxException("Not an .xlsx file", e)
            }
            return parts
        } finally {
            tmp.delete()
        }
    }

    /** Owner-only (rw-------) on POSIX: the spooled file holds the user's financial data. */
    internal fun createSpoolFile(): File = Files.createTempFile("moneybook-import", ".xlsx").toFile()

    private fun InputStream.copyLimited(out: OutputStream, limit: Long) {
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = read(chunk)
            if (n < 0) break
            total += n
            if (total > limit) throw XlsxException("Spreadsheet is too large")
            out.write(chunk, 0, n)
        }
    }

    private fun parse(bytes: ByteArray, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        try {
            factory.newSAXParser().parse(ByteArrayInputStream(bytes), handler)
        } catch (e: Exception) {
            // The SAX parser wraps what the handler throws; pass our own message through unchanged.
            generateSequence<Throwable>(e) { it.cause }.filterIsInstance<XlsxException>().firstOrNull()?.let { throw it }
            throw XlsxException("Malformed spreadsheet XML", e)
        }
    }

    private fun firstSheetPath(parts: Map<String, ByteArray>): String {
        val workbook = parts["xl/workbook.xml"] ?: return DEFAULT_SHEET
        var relId: String? = null
        parse(workbook, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                if (relId == null && localName == "sheet") relId = attributes.getValue(REL_NS, "id")
            }
        })
        val rels = parts["xl/_rels/workbook.xml.rels"] ?: return DEFAULT_SHEET
        var target: String? = null
        parse(rels, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                if (localName == "Relationship" && attributes.getValue("Id") == relId) target = attributes.getValue("Target")
            }
        })
        val t = target ?: return DEFAULT_SHEET
        return if (t.startsWith("/")) t.removePrefix("/") else "xl/" + t.removePrefix("./")
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val result = ArrayList<String>()
        parse(bytes, object : DefaultHandler() {
            private val text = StringBuilder()
            private var inItem = false
            private var inText = false
            private var phoneticDepth = 0

            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                when (localName) {
                    "si" -> { inItem = true; text.setLength(0) }
                    "rPh" -> phoneticDepth++
                    "t" -> inText = inItem && phoneticDepth == 0
                }
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (localName) {
                    "si" -> { result.add(text.toString()); inItem = false }
                    "rPh" -> phoneticDepth--
                    "t" -> inText = false
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inText) text.append(ch, start, length)
            }
        })
        return result
    }

    private fun parseDateStyles(bytes: ByteArray): Set<Int> {
        val customDateFormats = HashSet<Int>()
        val cellFormats = ArrayList<Int>()
        parse(bytes, object : DefaultHandler() {
            private var inCellXfs = false

            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                when (localName) {
                    "numFmt" -> {
                        val id = attributes.getValue("numFmtId")?.toIntOrNull()
                        if (id != null && isDateFormat(attributes.getValue("formatCode") ?: "")) customDateFormats.add(id)
                    }
                    "cellXfs" -> inCellXfs = true
                    "xf" -> if (inCellXfs) cellFormats.add(attributes.getValue("numFmtId")?.toIntOrNull() ?: 0)
                }
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                if (localName == "cellXfs") inCellXfs = false
            }
        })
        return cellFormats.indices
            .filter { cellFormats[it] in BUILTIN_DATE_FORMATS || cellFormats[it] in customDateFormats }
            .toSet()
    }

    private fun parseSheet(bytes: ByteArray, shared: List<String>, dateStyles: Set<Int>): List<List<Cell?>> {
        val rows = ArrayList<MutableList<Cell?>>()
        parse(bytes, object : DefaultHandler() {
            private var row: MutableList<Cell?>? = null
            private var nextColumn = 0
            private var column = 0
            private var type: String? = null
            private var style = 0
            private val value = StringBuilder()
            private var capture = false

            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                when (localName) {
                    "row" -> {
                        val index = attributes.getValue("r")?.toIntOrNull()?.minus(1) ?: rows.size
                        if (index !in 0 until MAX_ROWS) throw XlsxException("Row number out of range")
                        while (rows.size < index) rows.add(ArrayList())
                        row = ArrayList<Cell?>().also { rows.add(it) }
                        nextColumn = 0
                    }
                    "c" -> {
                        column = attributes.getValue("r")?.let(::columnIndex) ?: nextColumn
                        if (column !in 0 until MAX_COLUMNS) throw XlsxException("Column out of range")
                        type = attributes.getValue("t")
                        style = attributes.getValue("s")?.toIntOrNull() ?: 0
                        value.setLength(0)
                    }
                    "v", "t" -> capture = true
                }
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (localName) {
                    "v", "t" -> capture = false
                    "c" -> {
                        val current = row ?: return
                        while (current.size <= column) current.add(null)
                        current[column] = toCell(value.toString(), type, style)
                        nextColumn = column + 1
                    }
                    "row" -> row = null
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (capture) value.append(ch, start, length)
            }

            private fun toCell(raw: String, t: String?, s: Int): Cell? = when (t) {
                "s" -> raw.trim().toIntOrNull()?.let { shared.getOrNull(it) }?.let { Cell.Text(it) }
                "inlineStr", "str", "e" -> Cell.Text(raw)
                "b" -> Cell.Bool(raw.trim() == "1")
                else -> raw.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.let { Cell.Num(it, s in dateStyles) }
            }
        })
        return rows
    }
}
