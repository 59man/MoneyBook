package com.moneybook.shared.importer

import com.moneybook.shared.model.TxnType
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.shared.xlsx.OutCell
import com.moneybook.shared.xlsx.XlsxWriter
import java.io.OutputStream
import java.math.BigDecimal
import java.time.LocalDateTime

/** One entry for the Realbyte-format export. Amounts are minor units. */
data class ExportTxn(
    val type: TxnType,
    val dateTime: LocalDateTime,
    val accountName: String,
    /** Transfers only. */
    val toAccountName: String?,
    /** For a subcategory entry, the parent's name; the entry's own name goes in [subcategoryName]. */
    val categoryName: String?,
    val subcategoryName: String?,
    val note: String,
    val description: String,
    /** In [currency]. */
    val amountMinor: Long,
    val currency: String,
    /** In the main currency. */
    val amountMainMinor: Long,
    /** The From/only account's side, in [accountCurrency]; null = [amountMinor] in [currency]. */
    val accountAmountMinor: Long? = null,
    val accountCurrency: String? = null,
    /** A transfer's receiving side, in [toAccountCurrency]; null = [amountMinor] in [currency]. */
    val toAmountMinor: Long? = null,
    val toAccountCurrency: String? = null,
)

/** Writes entries in Realbyte's "Export data to Excel" layout, which RealbyteExcelImporter reads back. */
object RealbyteExcelExporter {
    fun write(txns: List<ExportTxn>, mainCurrency: String, out: OutputStream) {
        val main = CurrencySpec.of(mainCurrency)
        val rows = ArrayList<List<OutCell?>>(txns.size * 2 + 1)
        rows += listOf("Date", "Account", "Category", "Subcategory", "Note", main.code, "Income/Expense", "Description", "Amount", "Currency", "Account")
            .map { OutCell.Text(it) }
        for (t in txns) {
            val spec = CurrencySpec.of(t.currency)
            val amount = decimal(t.amountMinor, spec.decimals)
            // The last column is the account's own side: what the entry moved in that account's currency.
            fun side(minor: Long?, currency: String?) = OutCell.Number(decimal(minor ?: t.amountMinor, CurrencySpec.of(currency ?: t.currency).decimals))
            fun row(kind: String, account: String, category: String?, sub: String?, accountSide: OutCell): List<OutCell?> = listOf(
                OutCell.Date(t.dateTime), OutCell.Text(account), OutCell.Text(category.orEmpty()), OutCell.Text(sub.orEmpty()),
                OutCell.Text(t.note), OutCell.Number(decimal(t.amountMainMinor, main.decimals)), OutCell.Text(kind),
                OutCell.Text(t.description), OutCell.Text(amount), OutCell.Text(spec.code), accountSide,
            )
            val from = side(t.accountAmountMinor, t.accountCurrency)
            when (t.type) {
                TxnType.INCOME -> rows += row("Income", t.accountName, t.categoryName, t.subcategoryName, from)
                TxnType.EXPENSE -> rows += row("Expense", t.accountName, t.categoryName, t.subcategoryName, from)
                TxnType.TRANSFER -> {
                    val to = t.toAccountName.orEmpty()
                    rows += row("Transfer-Out", t.accountName, to, null, from)
                    rows += row("Transfer-In", to, t.accountName, null, side(t.toAmountMinor, t.toAccountCurrency))
                }
            }
        }
        XlsxWriter.write("MoneyBook", rows, out)
    }

    /** 52500, 2 → "525.00". */
    fun decimal(minor: Long, decimals: Int): String = BigDecimal.valueOf(minor, decimals).toPlainString()
}
