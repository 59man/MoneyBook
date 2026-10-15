package com.moneybook.shared.importer

import com.moneybook.shared.model.TxnType
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.shared.money.MoneyFormat
import com.moneybook.shared.xlsx.Cell
import com.moneybook.shared.xlsx.ExcelDates
import com.moneybook.shared.xlsx.XlsxReader
import java.io.InputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class ImportedTxn(
    /** Deterministic identity of the row; the same file always yields the same keys. */
    val sourceKey: String,
    val type: TxnType,
    val dateTime: LocalDateTime,
    val accountName: String,
    /** Transfers only. */
    val toAccountName: String?,
    /** Null for transfers and for rows without a category. */
    val categoryName: String?,
    val subcategoryName: String?,
    val note: String,
    val description: String,
    /** Minor units in [currency]; negative values are kept as entered in Realbyte. */
    val amountMinor: Long,
    val currency: String,
    /** The main currency column; null when the file has none or it is not a number (then 1:1). */
    val amountMainMinor: Long? = null,
    /** The last Account column when it differs from the amount: the From/only account's side. */
    val accountAmountMinor: Long? = null,
)

data class SkippedRow(val rowNumber: Int, val reason: String)

data class ImportBatch(val mainCurrency: String?, val txns: List<ImportedTxn>, val skipped: List<SkippedRow>)

class ImportFormatException(message: String) : Exception(message)

/** Parses Realbyte Money Manager's "Export data to Excel" sheet. Pure; no database access. */
object RealbyteExcelImporter {
    private val REQUIRED = listOf("Date", "Account", "Category", "Subcategory", "Note", "Income/Expense", "Description", "Amount", "Currency")
    private val TEXT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm[:ss]")

    fun parse(input: InputStream): ImportBatch = parse(XlsxReader.readFirstSheet(input))

    fun parse(rows: List<List<Cell?>>): ImportBatch {
        val header = rows.firstOrNull()?.map { (it as? Cell.Text)?.value?.trim() }
            ?: throw ImportFormatException("The file is empty.")
        val missing = REQUIRED.filter { it !in header }
        if (missing.isNotEmpty()) {
            throw ImportFormatException("Not a Money Manager export. Missing columns: ${missing.joinToString()}")
        }
        val iDate = header.indexOf("Date")
        val iAccount = header.indexOf("Account")
        val iCategory = header.indexOf("Category")
        val iSub = header.indexOf("Subcategory")
        val iNote = header.indexOf("Note")
        val iMain = iNote + 1
        val iKind = header.indexOf("Income/Expense")
        val iDescription = header.indexOf("Description")
        val iAmount = header.indexOf("Amount")
        val iCurrency = header.indexOf("Currency")
        val mainCurrency = header.getOrNull(iMain)?.takeIf { it.length == 3 && it.all(Char::isLetter) }?.uppercase()
        val iAccountAmount = header.lastIndexOf("Account").takeIf { it != iAccount }

        val txns = ArrayList<ImportedTxn>()
        val skipped = ArrayList<SkippedRow>()
        val transferIns = ArrayList<ImportedTxn>()
        val keyCounts = HashMap<String, Int>()

        fun withKey(t: ImportedTxn): ImportedTxn {
            val base = listOf(t.type, t.dateTime, t.accountName, t.toAccountName, t.categoryName, t.subcategoryName, t.note, t.amountMinor, t.currency)
                .joinToString("\u001F")
            val n = keyCounts.merge(base, 1) { a, b -> a + b }!!
            return t.copy(sourceKey = if (n == 1) base else "$base\u001F#$n")
        }

        rows.drop(1).forEachIndexed { index, row ->
            val rowNumber = index + 2
            if (row.all { it == null || (it is Cell.Text && it.value.isBlank()) }) return@forEachIndexed
            fun text(i: Int): String = when (val c = row.getOrNull(i)) {
                is Cell.Text -> c.value.trim()
                is Cell.Num -> BigDecimal.valueOf(c.value).stripTrailingZeros().toPlainString()
                is Cell.Bool -> c.value.toString()
                null -> ""
            }

            val dateTime = dateOf(row.getOrNull(iDate))
                ?: return@forEachIndexed run { skipped += SkippedRow(rowNumber, "Unreadable date") }
            val currency = text(iCurrency).ifEmpty { mainCurrency ?: "" }.uppercase()
            if (currency.isEmpty()) return@forEachIndexed run { skipped += SkippedRow(rowNumber, "Missing currency") }
            val amountText = text(iAmount).ifEmpty { text(iMain) }
            val amount = runCatching { MoneyFormat.parseMinor(amountText, CurrencySpec.of(currency).decimals) }.getOrNull()
                ?: return@forEachIndexed run { skipped += SkippedRow(rowNumber, "Unreadable amount '$amountText'") }
            val account = text(iAccount)
            if (account.isEmpty()) return@forEachIndexed run { skipped += SkippedRow(rowNumber, "Missing account") }
            val category = text(iCategory).ifEmpty { null }
            val base = ImportedTxn(
                sourceKey = "", type = TxnType.EXPENSE, dateTime = dateTime, accountName = account,
                toAccountName = null, categoryName = category, subcategoryName = text(iSub).ifEmpty { null },
                note = text(iNote), description = text(iDescription), amountMinor = amount, currency = currency,
                amountMainMinor = mainCurrency?.let { runCatching { MoneyFormat.parseMinor(text(iMain), CurrencySpec.of(it).decimals) }.getOrNull() },
                // ponytail: read at the entry's scale (new accounts are created in the row's currency); add an
                // account-currency map if real multi-currency imports appear.
                accountAmountMinor = iAccountAmount?.let { i ->
                    runCatching { MoneyFormat.parseMinor(text(i), CurrencySpec.of(currency).decimals) }.getOrNull()
                }?.takeIf { it != amount },
            )
            val kind = text(iKind)
            when (kind.lowercase()) {
                "expense", "exp." -> txns += withKey(base)
                "income" -> txns += withKey(base.copy(type = TxnType.INCOME))
                "transfer-out", "transfer-in" -> {
                    if (category == null) {
                        skipped += SkippedRow(rowNumber, "Missing transfer account")
                    } else if (kind.lowercase() == "transfer-out") {
                        txns += withKey(base.copy(type = TxnType.TRANSFER, toAccountName = category, categoryName = null, subcategoryName = null))
                    } else {
                        transferIns += base.copy(
                            // An In row's last column is the receiving side, not the From account's.
                            type = TxnType.TRANSFER, accountName = category, toAccountName = account, accountAmountMinor = null,
                            categoryName = null, subcategoryName = null,
                        )
                    }
                }
                else -> skipped += SkippedRow(rowNumber, "Unknown type '$kind'")
            }
        }

        // Realbyte writes a transfer as an Out row and an In row. Each Out row mirrors one In row;
        // In rows left over are transfers of their own (e.g. a partial export).
        val unmatchedOuts = txns.filter { it.type == TxnType.TRANSFER }
            .groupingBy(::transferKey).eachCount().toMutableMap()
        for (incoming in transferIns) {
            val key = transferKey(incoming)
            val left = unmatchedOuts[key] ?: 0
            if (left > 0) unmatchedOuts[key] = left - 1 else txns += withKey(incoming)
        }
        return ImportBatch(mainCurrency, txns, skipped)
    }

    private fun transferKey(t: ImportedTxn): List<Any?> = listOf(t.dateTime, t.amountMinor, t.accountName, t.toAccountName)

    private fun dateOf(cell: Cell?): LocalDateTime? = when (cell) {
        is Cell.Num -> ExcelDates.toLocalDateTime(cell.value)
        is Cell.Text -> cell.value.trim().let { s ->
            runCatching { LocalDateTime.parse(s, TEXT_DATE) }.getOrNull()
                ?: runCatching { LocalDateTime.parse(s) }.getOrNull()
                ?: runCatching { LocalDate.parse(s).atTime(12, 0) }.getOrNull()
        }
        else -> null
    }
}
