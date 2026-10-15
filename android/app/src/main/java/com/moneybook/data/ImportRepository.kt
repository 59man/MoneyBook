package com.moneybook.data

import androidx.room.withTransaction
import com.moneybook.data.db.AccountEntity
import com.moneybook.data.db.CategoryEntity
import com.moneybook.data.db.DbTime
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.data.db.TxnEntity
import com.moneybook.shared.importer.ImportBatch
import com.moneybook.shared.importer.RealbyteExcelImporter
import com.moneybook.shared.importer.SkippedRow
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.CategoryType
import com.moneybook.shared.model.TxnType
import java.io.InputStream
import java.util.UUID

data class ImportResult(
    val imported: Int,
    val duplicates: Int,
    val skipped: List<SkippedRow>,
    val accountsCreated: Int,
    val categoriesCreated: Int,
)

/** Writes a parsed Realbyte export into Room: matches accounts/categories by name, creates missing ones. */
class ImportRepository(
    private val db: MoneyBookDatabase,
    private val settings: SettingsRepository,
    private val seeder: Seeder,
    private val time: TimeSource,
) {
    suspend fun importRealbyte(input: InputStream): ImportResult = apply(RealbyteExcelImporter.parse(input))

    suspend fun apply(batch: ImportBatch): ImportResult {
        val fileCurrency = batch.mainCurrency
        val firstImport = fileCurrency != null && db.txnDao().count() == 0
        // A first import adopts the file's currency, but the setting is stored only once the rows are safely written.
        seeder.seedIfEmpty(if (firstImport) fileCurrency!! else settings.current().mainCurrency)
        val now = time.millis()
        val result = db.withTransaction {
            val accountDao = db.accountDao()
            val categoryDao = db.categoryDao()
            val txnDao = db.txnDao()
            if (firstImport) {
                // Accounts seeded at first launch took the device-locale currency; with no transactions yet they follow the file.
                accountDao.allAccounts().filter { it.currency != fileCurrency }.forEach {
                    accountDao.upsert(it.copy(currency = fileCurrency!!, updatedAt = now, dirty = true))
                }
            }
            // Normal groups only, the first of a type (sort order) wins, so a user-added Others group does not take the default's place.
            val groupIds = accountDao.groups().reversed().associate { it.type to it.id }
            // On a shared name a live account wins over a deleted one, and a deleted one over a purged one (written last wins).
            val accounts = accountDao.allAccounts().sortedByDescending { it.state.ordinal }.associateByTo(HashMap()) { it.name.trim().lowercase() }
            val categories = categoryDao.all().associateByTo(HashMap()) { categoryKey(it.type, it.parentId, it.name) }
            var accountsCreated = 0
            var categoriesCreated = 0

            suspend fun accountFor(name: String, currency: String): AccountEntity {
                accounts[name.trim().lowercase()]?.let { return it }
                val groupId = groupIds[guessGroupType(name)] ?: groupIds.getValue(AccountGroupType.ACCOUNTS)
                val created = AccountEntity(
                    id = UUID.randomUUID().toString(), groupId = groupId, name = name.trim(), currency = currency,
                    initialBalance = 0, sortOrder = accountDao.nextSortOrder(groupId), updatedAt = now,
                )
                accountDao.upsert(created)
                accounts[name.trim().lowercase()] = created
                accountsCreated++
                return created
            }

            suspend fun categoryFor(type: CategoryType, name: String, parentId: String?): CategoryEntity {
                val key = categoryKey(type, parentId, name)
                categories[key]?.let { return it }
                val created = CategoryEntity(
                    id = UUID.randomUUID().toString(), type = type, parentId = parentId, name = name.trim(),
                    sortOrder = categoryDao.nextSortOrder(type.name), updatedAt = now,
                )
                categoryDao.upsert(created)
                categories[key] = created
                categoriesCreated++
                return created
            }

            val ids = batch.txns.map { txnId(it.sourceKey) }
            val existing = ids.chunked(500).flatMap { txnDao.liveIds(it) }.toHashSet()
            val toInsert = ArrayList<TxnEntity>()
            var duplicates = 0
            batch.txns.forEachIndexed { i, t ->
                if (ids[i] in existing) {
                    duplicates++
                    return@forEachIndexed
                }
                val account = accountFor(t.accountName, t.currency)
                val toAccount = t.toAccountName?.let { accountFor(it, t.currency) }
                val categoryId = if (t.type == TxnType.TRANSFER) null else t.categoryName?.let { name ->
                    val type = if (t.type == TxnType.INCOME) CategoryType.INCOME else CategoryType.EXPENSE
                    val parent = categoryFor(type, name, null)
                    t.subcategoryName?.let { categoryFor(type, it, parent.id).id } ?: parent.id
                }
                toInsert += TxnEntity(
                    id = ids[i], type = t.type, dateTime = DbTime.format(t.dateTime),
                    accountId = account.id, toAccountId = toAccount?.id, categoryId = categoryId,
                    amount = t.amountMinor, currency = t.currency,
                    // A file without a numeric main column counts 1:1.
                    amountMain = t.amountMainMinor ?: t.amountMinor, accountAmount = t.accountAmountMinor,
                    note = t.note, description = t.description, updatedAt = now,
                )
            }
            toInsert.chunked(500).forEach { txnDao.upsertAll(it) }
            ImportResult(toInsert.size, duplicates, batch.skipped, accountsCreated, categoriesCreated)
        }
        if (firstImport) settings.setMainCurrency(fileCurrency!!)
        return result
    }

    companion object {
        fun txnId(sourceKey: String): String =
            UUID.nameUUIDFromBytes("realbyte:$sourceKey".toByteArray(Charsets.UTF_8)).toString()

        fun categoryKey(type: CategoryType, parentId: String?, name: String): String =
            "${type.name}|${parentId.orEmpty()}|${name.trim().lowercase()}"

        fun guessGroupType(name: String): AccountGroupType {
            val n = name.lowercase()
            return when {
                "cash" in n || "hotovost" in n -> AccountGroupType.CASH
                "debit" in n -> AccountGroupType.DEBIT_CARD
                "card" in n || "credit" in n || "karta" in n -> AccountGroupType.CARD
                "saving" in n || "spoř" in n -> AccountGroupType.SAVINGS
                else -> AccountGroupType.ACCOUNTS
            }
        }
    }
}
