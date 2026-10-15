package com.moneybook.data.sync

import com.moneybook.data.backup.toDto
import com.moneybook.data.backup.toEntity
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.shared.dto.AccountDto
import com.moneybook.shared.dto.AccountGroupDto
import com.moneybook.shared.dto.AttachmentDto
import com.moneybook.shared.dto.BookmarkDto
import com.moneybook.shared.dto.BudgetDto
import com.moneybook.shared.dto.CategoryDto
import com.moneybook.shared.dto.CurrencyDto
import com.moneybook.shared.dto.MemoDto
import com.moneybook.shared.dto.RepeatRuleDto
import com.moneybook.shared.dto.TxnDto
import com.moneybook.shared.sync.SyncChange
import com.moneybook.shared.sync.SyncEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Payloads are the backup DTOs as JSON; unknown keys from a newer app are ignored. */
internal val SyncJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/**
 * True when the incoming change must be written: the local row is clean, or it does not beat the incoming one.
 * Must equal the server's `(updated_at, device_id collate "C") <`: device ids are ASCII, so String.compareTo is "C".
 * An equal pair (the echo of our own push) is written, which cleans the row.
 */
internal fun incomingWins(localUpdatedAt: Long, localDirty: Boolean, myDeviceId: String, c: SyncChange): Boolean =
    !localDirty || compareValuesBy(localUpdatedAt to myDeviceId, c.updatedAt to c.deviceId, { it.first }, { it.second }) <= 0

internal class RowMeta(val id: String, val updatedAt: Long, val deleted: Boolean, val dirty: Boolean)

/** One synced Room table, so the engine never names an entity type. */
internal class SyncTable<E : Any, D>(
    val entity: SyncEntity,
    private val serializer: KSerializer<D>,
    private val meta: (E) -> RowMeta,
    private val toDto: (E) -> D,
    /** The DTO as a clean row carrying the envelope's updatedAt and deleted. */
    private val fromDto: (D, SyncChange) -> E,
    private val get: suspend (String) -> E?,
    private val write: suspend (List<E>) -> Unit,
    private val dirtyRows: suspend () -> List<E>,
    val clearDirty: suspend (id: String, updatedAt: Long) -> Unit,
    val markAllDirty: suspend () -> Unit,
    val dirtyCount: Flow<Int>,
    val wipe: suspend () -> Unit,
) {
    suspend fun dirty(): List<SyncChange> = dirtyRows().map { row ->
        val m = meta(row)
        SyncChange(entity.wire, m.id, m.updatedAt, m.deleted, payload = SyncJson.encodeToJsonElement(serializer, toDto(row)).jsonObject)
    }

    /**
     * Decodes every change now (throws IllegalArgumentException when one does not fit this app version, before anything
     * is written) and returns the writes, to run inside the caller's transaction. Rows go one at a time, each decided
     * against the local row as it is then.
     */
    fun prepare(changes: List<SyncChange>, myDeviceId: String): suspend () -> Unit {
        val rows = changes.map { it to fromDto(SyncJson.decodeFromJsonElement(serializer, it.payload), it) }
        return {
            for ((c, row) in rows) {
                val local = get(c.id)?.let(meta)
                if (local == null || incomingWins(local.updatedAt, local.dirty, myDeviceId, c)) write(listOf(row))
            }
        }
    }
}

/** The ten Room tables; settings are the eleventh entity, handled by the engine (DataStore, not Room). */
internal fun syncTables(db: MoneyBookDatabase): List<SyncTable<*, *>> {
    val accounts = db.accountDao()
    val categories = db.categoryDao()
    val txns = db.txnDao()
    val memos = db.memoDao()
    val bookmarks = db.bookmarkDao()
    val repeats = db.repeatRuleDao()
    val currencies = db.currencyDao()
    val budgets = db.budgetDao()
    val attachments = db.attachmentDao()
    return listOf(
        SyncTable(
            SyncEntity.ACCOUNT_GROUP, AccountGroupDto.serializer(), { RowMeta(it.id, it.updatedAt, it.deleted, it.dirty) }, { it.toDto() },
            { d, c -> d.toEntity().copy(updatedAt = c.updatedAt, deleted = c.deleted, dirty = false) },
            accounts::getGroup, accounts::applyRemoteGroups, accounts::dirtyGroups, accounts::clearDirtyGroup,
            accounts::markAllGroupsDirty, accounts.observeDirtyGroupCount(), accounts::deleteAllGroups,
        ),
        SyncTable(
            SyncEntity.ACCOUNT, AccountDto.serializer(), { RowMeta(it.id, it.updatedAt, it.deleted, it.dirty) }, { it.toDto() },
            { d, c -> d.toEntity().copy(updatedAt = c.updatedAt, deleted = c.deleted, dirty = false) },
            accounts::get, accounts::applyRemoteAccounts, accounts::dirtyAccounts, accounts::clearDirtyAccount,
            accounts::markAllAccountsDirty, accounts.observeDirtyAccountCount(), accounts::deleteAllAccounts,
        ),
        SyncTable(
            SyncEntity.CATEGORY, CategoryDto.serializer(), { RowMeta(it.id, it.updatedAt, it.deleted, it.dirty) }, { it.toDto() },
            { d, c -> d.toEntity().copy(updatedAt = c.updatedAt, deleted = c.deleted, dirty = false) },
            categories::get, categories::applyRemoteAll, categories::dirtyRows, categories::clearDirty,
            categories::markAllDirty, categories.observeDirtyCount(), categories::deleteAll,
        ),
        SyncTable(
            SyncEntity.TXN, TxnDto.serializer(), { RowMeta(it.id, it.updatedAt, it.deleted, it.dirty) }, { it.toDto() },
            { d, c -> d.toEntity().copy(updatedAt = c.updatedAt, deleted = c.deleted, dirty = false) },
            txns::get, txns::applyRemoteAll, txns::dirtyRows, txns::clearDirty, txns::markAllDirty, txns.observeDirtyCount(), txns::deleteAll,
        ),
        SyncTable(
            SyncEntity.MEMO, MemoDto.serializer(), { RowMeta(it.id, it.updatedAt, it.deleted, it.dirty) }, { it.toDto() },
            { d, c -> d.toEntity().copy(updatedAt = c.updatedAt, deleted = c.deleted, dirty = false) },
            memos::get, memos::applyRemoteAll, memos::dirtyRows, memos::clearDirty, memos::markAllDirty, memos.observeDirtyCount(), memos::deleteAll,
        ),
        SyncTable(
            SyncEntity.BOOKMARK, BookmarkDto.serializer(), { RowMeta(it.id, it.updatedAt, it.deleted, it.dirty) }, { it.toDto() },
            { d, c -> d.toEntity().copy(updatedAt = c.updatedAt, deleted = c.deleted, dirty = false) },
            bookmarks::get, bookmarks::applyRemoteAll, bookmarks::dirtyRows, bookmarks::clearDirty,
            bookmarks::markAllDirty, bookmarks.observeDirtyCount(), bookmarks::deleteAll,
        ),
        SyncTable(
            SyncEntity.REPEAT_RULE, RepeatRuleDto.serializer(), { RowMeta(it.id, it.updatedAt, it.deleted, it.dirty) }, { it.toDto() },
            { d, c -> d.toEntity().copy(updatedAt = c.updatedAt, deleted = c.deleted, dirty = false) },
            repeats::get, repeats::applyRemoteAll, repeats::dirtyRows, repeats::clearDirty,
            repeats::markAllDirty, repeats.observeDirtyCount(), repeats::deleteAll,
        ),
        SyncTable(
            SyncEntity.CURRENCY, CurrencyDto.serializer(), { RowMeta(it.code, it.updatedAt, it.deleted, it.dirty) }, { it.toDto() },
            // toEntity's SymbolPosition.valueOf throws IllegalArgumentException on a position this app doesn't know
            { d, c -> d.toEntity().copy(updatedAt = c.updatedAt, deleted = c.deleted, dirty = false) },
            currencies::get, currencies::applyRemoteAll, currencies::dirtyRows, currencies::clearDirty,
            currencies::markAllDirty, currencies.observeDirtyCount(), currencies::deleteAll,
        ),
        SyncTable(
            SyncEntity.BUDGET, BudgetDto.serializer(), { RowMeta(it.id, it.updatedAt, it.deleted, it.dirty) }, { it.toDto() },
            { d, c -> d.toEntity().copy(updatedAt = c.updatedAt, deleted = c.deleted, dirty = false) },
            budgets::get, budgets::applyRemoteAll, budgets::dirtyRows, budgets::clearDirty,
            budgets::markAllDirty, budgets.observeDirtyCount(), budgets::deleteAll,
        ),
        SyncTable(
            SyncEntity.ATTACHMENT, AttachmentDto.serializer(), { RowMeta(it.id, it.updatedAt, it.deleted, it.dirty) }, { it.toDto() },
            { d, c -> d.toEntity().copy(updatedAt = c.updatedAt, deleted = c.deleted, dirty = false) },
            attachments::get, attachments::applyRemoteAll, attachments::dirtyRows, attachments::clearDirty,
            attachments::markAllDirty, attachments.observeDirtyCount(), attachments::deleteAll,
        ),
    )
}
