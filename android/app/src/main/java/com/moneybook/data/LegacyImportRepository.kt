package com.moneybook.data

import androidx.room.withTransaction
import com.moneybook.data.db.CategoryEntity
import com.moneybook.data.db.DbTime
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.data.db.TxnEntity
import com.moneybook.shared.importer.CategoryMatcher
import com.moneybook.shared.importer.CategoryOption
import com.moneybook.shared.importer.LegacyBatch
import com.moneybook.shared.importer.SkippedRow
import com.moneybook.shared.model.CategoryType
import com.moneybook.shared.model.TxnType
import java.time.LocalDate
import java.util.UUID

data class LegacyCategoryKey(val type: CategoryType, val name: String)

data class LegacyCategoryGroup(val key: LegacyCategoryKey, val count: Int, val suggestionId: String?)

data class LegacyPreview(
    /** Rows that will be written, unless an earlier import already wrote them. */
    val importable: Int,
    /** Rows dated on or after [cutoff], whose days the existing data already covers. */
    val overlapping: Int,
    val cutoff: LocalDate?,
    val unreadable: List<SkippedRow>,
    /** Expense groups first, each by count, largest first. */
    val categories: List<LegacyCategoryGroup>,
)

sealed interface CategoryChoice {
    data class Existing(val id: String) : CategoryChoice
    data object CreateNew : CategoryChoice
}

data class LegacyImportResult(
    val imported: Int,
    val duplicates: Int,
    val overlapping: Int,
    val categoriesCreated: Int,
    /** Added to the account's initial balance so its current balance stays the same (0 when not kept). */
    val balanceAdjustment: Long,
)

/** Imports the old app's history into one account, mapped onto the current categories. */
class LegacyImportRepository(private val db: MoneyBookDatabase, private val time: TimeSource) {

    suspend fun preview(batch: LegacyBatch): LegacyPreview {
        val cutoff = cutoff(batch)
        val (kept, overlapping) = batch.rows.partition { cutoff == null || it.date.isBefore(cutoff) }
        val topLevel = db.categoryDao().all().filter { it.parentId == null }.sortedBy { it.sortOrder }
        val groups = kept.groupBy { LegacyCategoryKey(it.type.categoryType(), it.category) }.map { (key, rows) ->
            val options = topLevel.filter { it.type == key.type }.map { CategoryOption(it.id, it.name) }
            LegacyCategoryGroup(key, rows.size, CategoryMatcher.suggest(key.name, options)?.id)
        }
        return LegacyPreview(
            importable = kept.size,
            overlapping = overlapping.size,
            cutoff = cutoff,
            unreadable = batch.skipped,
            categories = groups.sortedWith(compareBy<LegacyCategoryGroup> { it.key.type != CategoryType.EXPENSE }.thenByDescending { it.count }),
        )
    }

    suspend fun apply(
        batch: LegacyBatch,
        accountId: String,
        keepBalance: Boolean,
        choices: Map<LegacyCategoryKey, CategoryChoice>,
    ): LegacyImportResult {
        val cutoff = cutoff(batch)
        val now = time.millis()
        return db.withTransaction {
            val account = db.accountDao().get(accountId)?.takeIf { !it.deleted } ?: error("The account no longer exists.")
            val kept = batch.rows.filter { cutoff == null || it.date.isBefore(cutoff) }
            val ids = kept.map { txnId(it.key) }
            val existing = ids.chunked(500).flatMap { db.txnDao().liveIds(it) }.toHashSet()
            val categoryIds = HashMap<LegacyCategoryKey, String>()
            var created = 0

            suspend fun categoryFor(key: LegacyCategoryKey): String = categoryIds.getOrPut(key) {
                when (val choice = choices[key] ?: CategoryChoice.CreateNew) {
                    is CategoryChoice.Existing -> choice.id
                    CategoryChoice.CreateNew -> {
                        val sameName = db.categoryDao().all().firstOrNull {
                            it.type == key.type && it.parentId == null && it.name.trim().equals(key.name, ignoreCase = true)
                        }
                        sameName?.id ?: CategoryEntity(
                            id = UUID.randomUUID().toString(), type = key.type, name = key.name,
                            sortOrder = db.categoryDao().nextSortOrder(key.type.name), updatedAt = now,
                        ).also { db.categoryDao().upsert(it); created++ }.id
                    }
                }
            }

            val toInsert = kept.filterIndexed { i, _ -> ids[i] !in existing }.map { row ->
                TxnEntity(
                    id = txnId(row.key), type = row.type, dateTime = DbTime.format(row.date.atTime(12, 0)),
                    accountId = account.id, categoryId = categoryFor(LegacyCategoryKey(row.type.categoryType(), row.category)),
                    amount = row.amountMinor, currency = account.currency, amountMain = row.amountMinor,
                    note = row.memo, updatedAt = now,
                )
            }
            toInsert.chunked(500).forEach { db.txnDao().upsertAll(it) }
            val net = toInsert.sumOf { if (it.type == TxnType.INCOME) it.amount else -it.amount }
            val adjustment = if (keepBalance) -net else 0L
            if (adjustment != 0L) {
                db.accountDao().upsert(account.copy(initialBalance = account.initialBalance + adjustment, updatedAt = now, dirty = true))
            }
            LegacyImportResult(
                imported = toInsert.size,
                duplicates = kept.size - toInsert.size,
                overlapping = batch.rows.size - kept.size,
                categoriesCreated = created,
                balanceAdjustment = adjustment,
            )
        }
    }

    /** The first day other data covers: the earliest live entry that did not come from this file. */
    private suspend fun cutoff(batch: LegacyBatch): LocalDate? {
        val ours = batch.rows.mapTo(HashSet()) { txnId(it.key) }
        return db.txnDao().idsByDate().firstOrNull { it.id !in ours }?.let { DbTime.parse(it.dateTime).toLocalDate() }
    }

    companion object {
        fun txnId(key: String): String = UUID.nameUUIDFromBytes("legacy:$key".toByteArray(Charsets.UTF_8)).toString()
    }
}

private fun TxnType.categoryType(): CategoryType = if (this == TxnType.INCOME) CategoryType.INCOME else CategoryType.EXPENSE
