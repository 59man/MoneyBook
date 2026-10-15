package com.moneybook.shared.importer

import com.moneybook.shared.model.TxnType
import com.moneybook.shared.money.MoneyFormat
import com.moneybook.shared.xlsx.Cell
import com.moneybook.shared.xlsx.ExcelDates
import com.moneybook.shared.xlsx.XlsxReader
import java.io.InputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** One row of the old app's sheet: Date | Income/Expenses | Category | Memo | Amount. */
data class LegacyRow(
    val rowNumber: Int,
    /** Deterministic identity: the same file always yields the same keys. */
    val key: String,
    val date: LocalDate,
    val type: TxnType,
    /** As written in the file, trimmed. */
    val category: String,
    val memo: String,
    /** Minor units. The file writes expenses negative: -300 becomes 30000, and a positive expense (a refund) becomes negative. */
    val amountMinor: Long,
)

data class LegacyBatch(val rows: List<LegacyRow>, val skipped: List<SkippedRow>)

/** Reads the 5-column export of the user's pre-2023 app, after a one-time .xls → .xlsx conversion with LibreOffice. */
object LegacyExcelImporter {
    private val COLUMNS = listOf("date", "income/expenses", "category", "memo", "amount")
    private val DOTTED_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d.M.yyyy")

    fun parse(input: InputStream, decimals: Int = 2): LegacyBatch = parse(XlsxReader.readFirstSheet(input), decimals)

    fun parse(rows: List<List<Cell?>>, decimals: Int = 2): LegacyBatch {
        val header = rows.firstOrNull()?.map { headerName((it as? Cell.Text)?.value) }
            ?: throw ImportFormatException("The file is empty.")
        val missing = COLUMNS.filter { it !in header }
        if (missing.isNotEmpty()) throw ImportFormatException("Not an old-app export. Missing columns: ${missing.joinToString()}")
        val (iDate, iType, iCategory, iMemo, iAmount) = COLUMNS.map { header.indexOf(it) }

        val result = ArrayList<LegacyRow>()
        val skipped = ArrayList<SkippedRow>()
        val keyCounts = HashMap<String, Int>()
        rows.drop(1).forEachIndexed { index, row ->
            val rowNumber = index + 2
            if (row.all { it == null || (it is Cell.Text && it.value.isBlank()) }) return@forEachIndexed
            fun text(i: Int): String = when (val c = row.getOrNull(i)) {
                is Cell.Text -> c.value.trim()
                is Cell.Num -> BigDecimal.valueOf(c.value).stripTrailingZeros().toPlainString()
                is Cell.Bool -> c.value.toString()
                null -> ""
            }
            fun skip(reason: String) {
                skipped += SkippedRow(rowNumber, reason)
            }

            val date = dateOf(row.getOrNull(iDate)) ?: return@forEachIndexed skip("Unreadable date")
            val type = when (text(iType).lowercase()) {
                "expenses", "expense" -> TxnType.EXPENSE
                "income" -> TxnType.INCOME
                else -> return@forEachIndexed skip("Unknown type '${text(iType)}'")
            }
            val category = text(iCategory).ifEmpty { return@forEachIndexed skip("Missing category") }
            val value = amountOf(text(iAmount), decimals) ?: return@forEachIndexed skip("Unreadable amount '${text(iAmount)}'")
            val amount = if (type == TxnType.EXPENSE) -value else value
            val memo = text(iMemo)
            val base = listOf(date, type, category, memo, amount).joinToString("\u001F")
            val n = keyCounts.merge(base, 1, Int::plus)!!
            result += LegacyRow(rowNumber, if (n == 1) base else "$base\u001F#$n", date, type, category, memo, amount)
        }
        return LegacyBatch(result, skipped)
    }

    private fun headerName(s: String?): String? =
        s?.trim()?.lowercase()?.let { if (it == "income/expense") "income/expenses" else it }

    private fun dateOf(cell: Cell?): LocalDate? = when (cell) {
        is Cell.Num -> ExcelDates.toLocalDateTime(cell.value).toLocalDate()
        is Cell.Text -> cell.value.trim().let { s ->
            runCatching { LocalDate.parse(s) }.getOrNull() ?: runCatching { LocalDate.parse(s, DOTTED_DATE) }.getOrNull()
        }
        else -> null
    }

    /** "-300", "-12.50", "-12,50", "1 234,50" → minor units; null when unreadable. */
    private fun amountOf(s: String, decimals: Int): Long? {
        val compact = s.replace(" ", "").replace(" ", "")
        val plain = if (',' in compact && '.' !in compact) compact.replace(',', '.') else compact.replace(",", "")
        return runCatching { MoneyFormat.parseMinor(plain, decimals) }.getOrNull()
    }
}
