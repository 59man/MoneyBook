package com.moneybook.data

import com.moneybook.data.db.DbTime
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.data.db.TxnRow
import com.moneybook.shared.importer.ExportTxn
import com.moneybook.shared.importer.RealbyteExcelExporter
import java.io.OutputStream
import kotlinx.coroutines.flow.first

/** Exports every live entry, oldest first, in Realbyte's Excel layout. */
class ExportRepository(private val db: MoneyBookDatabase, private val settings: SettingsRepository) {
    suspend fun exportExcel(out: OutputStream): Int {
        val s = settings.current()
        val txns = db.txnDao().observeRange(DbTime.MIN, DbTime.MAX).first().sortedBy { it.dateTime }.map { it.toExportTxn(s.subcategory) }
        RealbyteExcelExporter.write(txns, s.mainCurrency, out)
        return txns.size
    }
}

/** A subcategory entry exports its parent as Category; with Subcategory ON also itself as Subcategory (OFF leaves it empty, as Realbyte does). */
internal fun TxnRow.toExportTxn(withSubcategory: Boolean): ExportTxn {
    val category = parentCategoryName ?: categoryName
    val subcategory = if (withSubcategory && parentCategoryName != null) categoryName else null
    return ExportTxn(
        type = type, dateTime = DbTime.parse(dateTime), accountName = accountName, toAccountName = toAccountName,
        categoryName = category, subcategoryName = subcategory, note = note, description = description,
        amountMinor = amount, currency = currency, amountMainMinor = amountMain,
        accountAmountMinor = accountAmount, accountCurrency = accountCurrency, toAmountMinor = toAmount, toAccountCurrency = toAccountCurrency,
    )
}
