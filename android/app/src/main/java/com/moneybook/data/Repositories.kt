package com.moneybook.data

import androidx.room.withTransaction
import com.moneybook.data.db.AccountBalanceRow
import com.moneybook.data.db.AccountEntity
import com.moneybook.data.db.AccountGroupEntity
import com.moneybook.data.db.BookmarkEntity
import com.moneybook.data.db.BookmarkRow
import com.moneybook.data.db.CategoryEntity
import com.moneybook.data.db.DeletedGroupRow
import com.moneybook.data.db.DbTime
import com.moneybook.data.db.MemoEntity
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.data.db.RepeatRuleEntity
import com.moneybook.data.db.RepeatRuleRow
import com.moneybook.data.db.TxnEntity
import com.moneybook.data.db.TxnRow
import com.moneybook.domain.CardCharge
import com.moneybook.domain.DateRange
import com.moneybook.domain.RepeatSchedule
import com.moneybook.domain.StoredAmounts
import com.moneybook.domain.entryAmounts
import com.moneybook.domain.rankSuggestions
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.ArchiveState
import com.moneybook.shared.model.CategoryType
import com.moneybook.shared.model.RepeatFrequency
import com.moneybook.shared.model.TxnType
import com.moneybook.shared.money.Fx
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class TxnRepository(private val db: MoneyBookDatabase, private val time: TimeSource) {
    private val dao get() = db.txnDao()

    fun observeRange(range: DateRange): Flow<List<TxnRow>> =
        dao.observeRange(DbTime.startOf(range.start), DbTime.startOf(range.endExclusive))

    fun observeRangeForAccount(accountId: String, range: DateRange): Flow<List<TxnRow>> =
        dao.observeRangeForAccount(accountId, DbTime.startOf(range.start), DbTime.startOf(range.endExclusive))

    /** Every live entry, newest first (Search). */
    fun observeAll(): Flow<List<TxnRow>> = dao.observeRange(DbTime.MIN, DbTime.MAX)

    /** Every entry of an account, newest first (account detail → Annually). */
    fun observeAllForAccount(accountId: String): Flow<List<TxnRow>> = dao.observeRangeForAccount(accountId, DbTime.MIN, DbTime.MAX)

    /** Autocomplete for the Note field: earlier notes of [type] containing [typed]. */
    suspend fun noteSuggestions(type: TxnType, typed: String, since: Long): List<String> =
        rankSuggestions(dao.noteUses(type.name, since), typed)

    suspend fun get(id: String): TxnEntity? = dao.get(id)

    /** [photos] are the entry's photo file names in order; null leaves its photos as they are. They are written in the same transaction. */
    suspend fun save(txn: TxnEntity, photos: List<String>? = null) {
        val now = time.millis()
        db.withTransaction {
            dao.upsert(txn.copy(updatedAt = now, dirty = true))
            if (photos != null) db.attachmentDao().replaceForTxn(txn.id, photos, now)
        }
    }

    /** Tombstones the entry and its photo rows; the files go with the next sweep. */
    suspend fun delete(id: String) {
        val now = time.millis()
        db.withTransaction {
            dao.softDelete(id, now)
            db.attachmentDao().tombstoneForTxn(id, now)
        }
    }

    /** The entry's live photo file names, in order. */
    suspend fun photos(id: String): List<String> = db.attachmentDao().forTxn(id).map { it.fileName }
}

class AccountRepository(private val db: MoneyBookDatabase, private val time: TimeSource) {
    private val dao get() = db.accountDao()

    fun observeBalances(until: String = DbTime.MAX): Flow<List<AccountBalanceRow>> = dao.observeBalances(until)

    /** [id]'s row and balance whatever its state: a deleted account's detail is still readable. */
    fun observeAnyBalance(id: String, until: String = DbTime.MAX): Flow<AccountBalanceRow?> = dao.observeAnyBalance(id, until)

    /** Each card account's charges (expenses and transfers out), for its Balance Payable and Outst. Balance. */
    fun observeCardCharges(): Flow<Map<String, List<CardCharge>>> = dao.observeCardCharges().map { rows ->
        rows.groupBy({ it.accountId }) { CardCharge(DbTime.parse(it.dateTime).toLocalDate(), it.amount) }
    }

    suspend fun groups(): List<AccountGroupEntity> = dao.groups()
    /** Normal accounts in normal groups: pickers, the filter panel, Search, the Accounts tab. */
    suspend fun accounts(): List<AccountEntity> = dao.accounts()

    /** Every non-tombstoned account in any state: importers match by name, so a deleted account is reused, not duplicated. */
    suspend fun allAccounts(): List<AccountEntity> = dao.allAccounts()
    suspend fun get(id: String): AccountEntity? = dao.get(id)
    suspend fun nextSortOrder(groupId: String): Int = dao.nextSortOrder(groupId)

    suspend fun balanceOf(accountId: String): Long =
        observeBalances().first().firstOrNull { it.id == accountId }?.balance ?: 0

    suspend fun save(account: AccountEntity) = dao.upsert(account.copy(updatedAt = time.millis(), dirty = true))

    fun observeGroups(): Flow<List<AccountGroupEntity>> = dao.observeGroups()

    suspend fun getGroup(id: String): AccountGroupEntity? = dao.getGroup(id)?.takeIf { !it.deleted }

    suspend fun renameGroup(id: String, name: String) {
        getGroup(id)?.let { dao.upsertGroups(listOf(it.copy(name = name.trim(), updatedAt = time.millis(), dirty = true))) }
    }

    suspend fun updateGroup(id: String, name: String, type: AccountGroupType) {
        getGroup(id)?.let { dao.upsertGroups(listOf(it.copy(name = name.trim(), type = type, updatedAt = time.millis(), dirty = true))) }
    }

    /** [orderedIds] in display order; only rows whose position changed are written. */
    suspend fun reorderGroups(orderedIds: List<String>) = db.withTransaction {
        val now = time.millis()
        orderedIds.forEachIndexed { i, id ->
            getGroup(id)?.takeIf { it.sortOrder != i }?.let { dao.upsertGroups(listOf(it.copy(sortOrder = i, updatedAt = now, dirty = true))) }
        }
    }

    suspend fun setHidden(id: String, hidden: Boolean) {
        get(id)?.takeIf { !it.deleted }?.let { save(it.copy(hidden = hidden)) }
    }

    /** [orderedIds] in display order within one group; only rows whose position changed are written. */
    suspend fun reorder(orderedIds: List<String>) = db.withTransaction {
        orderedIds.forEachIndexed { i, id -> get(id)?.takeIf { !it.deleted && it.sortOrder != i }?.let { save(it.copy(sortOrder = i)) } }
    }

    /** Like Realbyte: the account moves to Deleted accounts. Its entries and repeat rules stay (a rule waits). */
    suspend fun delete(accountId: String) = dao.setState(accountId, ArchiveState.DELETED, time.millis())

    /** Deleted accounts → Restore: normal again, and no longer hidden. */
    suspend fun restore(accountId: String) = dao.restore(accountId, time.millis())

    /** Deleted accounts → the red minus: never shown again; the row stays so entries keep a valid account. */
    suspend fun purge(accountId: String) = dao.setState(accountId, ArchiveState.PURGED, time.millis())

    suspend fun setTransferExpense(accountId: String, on: Boolean) = dao.setTransferExpense(accountId, on, time.millis())

    /** Deleted accounts whose group is normal (a deleted group's accounts are listed with the group). */
    fun observeDeletedAccounts(): Flow<List<AccountEntity>> = dao.observeDeletedAccounts()

    fun observeDeletedGroups(): Flow<List<DeletedGroupRow>> = dao.observeDeletedGroups()

    /**
     * Transfer-Expense setting: normal accounts in groups whose type is not Cash, Accounts, Card or Debit Card,
     * plus any account that is already ticked, in group order.
     */
    fun observeTransferExpenseAccounts(): Flow<List<AccountEntity>> = combine(dao.observeActiveAccounts(), dao.observeGroups()) { accounts, groups ->
        val type = groups.associate { it.id to it.type }
        accounts.filter { it.transferExpense || type[it.groupId] !in NO_TRANSFER_EXPENSE_GROUPS }
    }

    /** A new group of [type] after the last one. */
    suspend fun addGroup(name: String, type: AccountGroupType = AccountGroupType.OTHERS): AccountGroupEntity {
        val group = AccountGroupEntity(
            id = UUID.randomUUID().toString(), name = name.trim(), type = type, sortOrder = dao.nextGroupSortOrder(),
            updatedAt = time.millis(),
        )
        dao.upsertGroups(listOf(group))
        return group
    }

    /** Account Group → Delete: the group and, with it, its accounts leave every list; entries are untouched. */
    suspend fun deleteGroup(id: String) = dao.setGroupState(id, ArchiveState.DELETED, time.millis())

    suspend fun restoreGroup(id: String) = dao.setGroupState(id, ArchiveState.ACTIVE, time.millis())

    suspend fun purgeGroup(id: String) = dao.setGroupState(id, ArchiveState.PURGED, time.millis())

    companion object {
        private val NO_TRANSFER_EXPENSE_GROUPS = setOf(
            AccountGroupType.CASH, AccountGroupType.ACCOUNTS, AccountGroupType.CARD, AccountGroupType.DEBIT_CARD,
        )
    }
}

class CategoryRepository(private val db: MoneyBookDatabase, private val time: TimeSource) {
    private val dao get() = db.categoryDao()

    suspend fun top(type: CategoryType): List<CategoryEntity> = dao.top(type.name)
    suspend fun all(): List<CategoryEntity> = dao.all()
    /** Tombstones included: entries keep showing a deleted category's name. */
    suspend fun allRows(): List<CategoryEntity> = dao.allRows()
    suspend fun save(category: CategoryEntity) = dao.upsert(category.copy(updatedAt = time.millis(), dirty = true))

    fun observeTop(type: CategoryType): Flow<List<CategoryEntity>> = dao.observeTop(type.name)

    suspend fun get(id: String): CategoryEntity? = dao.get(id)?.takeIf { !it.deleted }

    suspend fun add(type: CategoryType, name: String): CategoryEntity {
        val category = CategoryEntity(
            id = UUID.randomUUID().toString(), type = type, name = name.trim(),
            sortOrder = dao.nextSortOrder(type.name), updatedAt = time.millis(),
        )
        save(category)
        return category
    }

    suspend fun rename(id: String, name: String) {
        get(id)?.let { save(it.copy(name = name.trim())) }
    }

    /** Like Realbyte: the category and its subcategories go. Entries keep their category id, so lists still show the name. */
    suspend fun delete(id: String) = db.withTransaction {
        (dao.children(id) + listOfNotNull(get(id))).forEach { save(it.copy(deleted = true)) }
    }

    /** Deletes [id] like [delete], after moving its entries, bookmarks and repeats (subcategories' too) to [targetId]. */
    suspend fun moveAndDelete(id: String, targetId: String) = db.withTransaction {
        val ids = dao.children(id).map { it.id } + id
        val now = time.millis()
        db.txnDao().moveCategory(ids, targetId, now)
        db.bookmarkDao().moveCategory(ids, targetId, now)
        db.repeatRuleDao().moveCategory(ids, targetId, now)
        delete(id)
    }

    /** [orderedIds] in display order; only rows whose position changed are written. */
    suspend fun reorder(orderedIds: List<String>) = db.withTransaction {
        orderedIds.forEachIndexed { i, id -> get(id)?.takeIf { it.sortOrder != i }?.let { save(it.copy(sortOrder = i)) } }
    }

    fun observeChildren(parentId: String): Flow<List<CategoryEntity>> = dao.observeChildren(parentId)
    fun observeSubs(type: CategoryType): Flow<List<CategoryEntity>> = dao.observeSubs(type.name)

    /** A new subcategory of [parentId] (a main category), last in its list and of the parent's type. */
    suspend fun addSub(parentId: String, name: String): CategoryEntity = db.withTransaction {
        val parent = checkNotNull(get(parentId)) { "no category $parentId" }
        CategoryEntity(
            id = UUID.randomUUID().toString(), type = parent.type, parentId = parentId, name = name.trim(),
            sortOrder = dao.nextChildSortOrder(parentId), updatedAt = time.millis(),
        ).also { save(it) }
    }

    /**
     * "→ Subcategory": the main category [id] and its own subcategories become subcategories of the main category [newParentId]
     * of the same type, after that parent's own, in their old order. Entries keep their category. Anything else is ignored.
     */
    suspend fun toSubcategory(id: String, newParentId: String) = db.withTransaction {
        val moving = get(id)
        val parent = get(newParentId)
        if (id == newParentId || moving == null || parent == null || moving.parentId != null || parent.parentId != null || moving.type != parent.type) {
            return@withTransaction
        }
        var order = dao.nextChildSortOrder(newParentId)
        (listOf(moving) + dao.children(id).sortedWith(compareBy({ it.sortOrder }, { it.name }))).forEach {
            save(it.copy(parentId = newParentId, sortOrder = order++))
        }
    }

    /** "→ Main Category": the subcategory [id] becomes a main category, last of its type. */
    suspend fun toMain(id: String) = db.withTransaction {
        val sub = get(id)?.takeIf { it.parentId != null } ?: return@withTransaction
        save(sub.copy(parentId = null, sortOrder = dao.nextSortOrder(sub.type.name)))
    }
}

class MemoRepository(private val db: MoneyBookDatabase, private val time: TimeSource) {
    private val dao get() = db.memoDao()

    fun observeRange(range: DateRange): Flow<List<MemoEntity>> =
        dao.observeRange(range.start.toString(), range.endExclusive.toString())

    suspend fun get(id: String): MemoEntity? = dao.get(id)?.takeIf { !it.deleted }

    suspend fun save(memo: MemoEntity) = dao.upsert(memo.copy(updatedAt = time.millis(), dirty = true))

    suspend fun delete(id: String) = dao.softDelete(id, time.millis())
}

class BookmarkRepository(private val db: MoneyBookDatabase, private val time: TimeSource) {
    private val dao get() = db.bookmarkDao()

    fun observeRows(): Flow<List<BookmarkRow>> = dao.observeRows()

    suspend fun get(id: String): BookmarkEntity? = dao.get(id)?.takeIf { !it.deleted }

    /** Saves [from]'s form values (not its date) as a new bookmark. */
    suspend fun add(from: TxnEntity): BookmarkEntity {
        val now = time.millis()
        val name = from.note.ifBlank { from.categoryId?.let { db.categoryDao().get(it)?.name }.orEmpty() }
        val bookmark = BookmarkEntity(
            id = UUID.randomUUID().toString(), name = name, type = from.type, accountId = from.accountId,
            toAccountId = from.toAccountId, categoryId = from.categoryId, amount = from.amount, currency = from.currency,
            note = from.note, description = from.description, createdAt = now, updatedAt = now,
        )
        dao.upsert(bookmark)
        return bookmark
    }

    suspend fun delete(id: String) = dao.softDelete(id, time.millis())
}

/**
 * Repeats and installment plans. Their occurrences are ordinary entries that [catchUp] adds when they come due.
 * [fx] gives the current rates; without it (older tests) amounts count 1:1.
 */
class RepeatRepository(private val db: MoneyBookDatabase, private val time: TimeSource, private val fx: (suspend () -> Fx)? = null) {
    private val dao get() = db.repeatRuleDao()

    fun observeRows(): Flow<List<RepeatRuleRow>> = dao.observeRows()

    suspend fun get(id: String): RepeatRuleEntity? = dao.get(id)?.takeIf { !it.deleted }

    /**
     * Starts rule [id] from [entry], which the caller has saved as occurrence 0. Later occurrences copy the
     * entry, with [laterAmount] (an installment plan's regular part).
     */
    suspend fun create(
        id: String,
        entry: TxnEntity,
        frequency: RepeatFrequency,
        installments: Int? = null,
        laterAmount: Long = entry.amount,
    ): RepeatRuleEntity {
        val at = DbTime.parse(entry.dateTime)
        val rule = RepeatRuleEntity(
            id = id, frequency = frequency, startDate = at.toLocalDate().toString(), lastDate = at.toLocalDate().toString(),
            installments = installments, type = entry.type, accountId = entry.accountId, toAccountId = entry.toAccountId,
            categoryId = entry.categoryId, amount = laterAmount, currency = entry.currency, note = entry.note,
            description = entry.description, time = at.toLocalTime().format(TIME), updatedAt = time.millis(),
        )
        dao.upsert(rule)
        return rule
    }

    suspend fun save(rule: RepeatRuleEntity) = dao.upsert(rule.copy(updatedAt = time.millis(), dirty = true))

    /** Stops the rule; the entries it already added stay. */
    suspend fun delete(id: String) = dao.softDelete(id, time.millis())

    /**
     * Adds every occurrence that has come due by [today] ("On the date") and returns how many. A rule whose
     * account is hidden or gone, or whose category was deleted, waits. An occurrence that already exists,
     * even as a tombstone, is skipped, so this is safe to run any number of times.
     */
    suspend fun catchUp(today: LocalDate): Int {
        val rates = fx?.invoke()
        return db.withTransaction { catchUpWith(today, rates) }
    }

    private suspend fun catchUpWith(today: LocalDate, rates: Fx?): Int {
        var added = 0
        for (rule in dao.live()) {
            if (!usable(rule)) continue
            // A rule with a damaged date or time is skipped, so it cannot stop the others (or crash every start).
            val start = runCatching { LocalDate.parse(rule.startDate) }.getOrNull() ?: continue
            val last = runCatching { LocalDate.parse(rule.lastDate) }.getOrNull() ?: continue
            val at = runCatching { LocalTime.parse(rule.time) }.getOrNull() ?: continue
            val due = RepeatSchedule.due(rule.frequency, start, last, rule.installments, today)
            if (due.isEmpty()) continue
            val ids = due.map { occurrenceId(rule.id, it.date) }
            val existing = ids.chunked(500).flatMap { db.txnDao().existingIds(it) }.toSet()
            val amounts = rates?.let { r ->
                val to = rule.toAccountId?.let { db.accountDao().get(it)?.currency }
                entryAmounts(rule.amount, rule.currency, null, db.accountDao().get(rule.accountId)!!.currency, to, r)
            } ?: StoredAmounts(rule.amount, rule.currency, rule.amount, null, null)
            val rows = due.filter { occurrenceId(rule.id, it.date) !in existing }.map { o ->
                TxnEntity(
                    id = occurrenceId(rule.id, o.date), type = rule.type, dateTime = DbTime.format(o.date.atTime(at)),
                    accountId = rule.accountId, toAccountId = rule.toAccountId, categoryId = rule.categoryId,
                    amount = amounts.amount, currency = amounts.currency, amountMain = amounts.amountMain,
                    accountAmount = amounts.accountAmount, toAmount = amounts.toAmount, note = rule.note,
                    description = rule.description, repeatRuleId = rule.id, installmentTotal = rule.installments,
                    installmentIndex = rule.installments?.let { o.index + 1 }, updatedAt = 0,
                )
            }
            rows.chunked(500).forEach { db.txnDao().upsertAll(it) }
            added += rows.size
            // Stamped 0 / unchanged, not now: a device that has not synced yet must not overwrite another device's edit,
            // tombstone or rule delete. A real edit wins; the monotonic trigger lifts the rule to old + 1 on this dirty update.
            dao.upsert(rule.copy(lastDate = due.last().date.toString(), dirty = true))
        }
        return added
    }

    private suspend fun usable(rule: RepeatRuleEntity): Boolean {
        suspend fun open(id: String): Boolean {
            val account = db.accountDao().get(id) ?: return false
            val group = db.accountDao().getGroup(account.groupId) ?: return false
            return !account.deleted && !account.hidden && account.state == ArchiveState.ACTIVE && !group.deleted && group.state == ArchiveState.ACTIVE
        }
        if (!open(rule.accountId)) return false
        if (rule.toAccountId != null && !open(rule.toAccountId)) return false
        if (rule.categoryId != null && db.categoryDao().get(rule.categoryId)?.deleted != false) return false
        return true
    }

    companion object {
        /** Occurrence ids are derived, so two devices adding the same occurrence write the same row. */
        fun occurrenceId(ruleId: String, date: LocalDate) = "rep-$ruleId-$date"

        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
