package com.moneybook.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.ArchiveState
import com.moneybook.shared.model.RepeatFrequency
import com.moneybook.shared.model.TxnType
import kotlinx.coroutines.flow.Flow

/** A transaction joined with the names the lists display. */
data class TxnRow(
    val id: String,
    val type: TxnType,
    val dateTime: String,
    val amount: Long,
    val toAmount: Long?,
    val amountMain: Long,
    val currency: String,
    val fee: Long,
    val note: String,
    val description: String,
    val accountId: String,
    val accountName: String,
    val toAccountId: String?,
    val toAccountName: String?,
    val categoryId: String?,
    val categoryName: String?,
    val parentCategoryName: String?,
    /** The parent's id when [categoryId] is a subcategory (Stats groups by it). */
    val parentCategoryId: String? = null,
    /** Group types of the two accounts (the Total tab splits card and non-card accounts). */
    val accountGroupType: AccountGroupType? = null,
    val toAccountGroupType: AccountGroupType? = null,
    /** Transfer-Expense flags of the two accounts (see `countedAs`). */
    val fromTransferExpense: Boolean = false,
    val toTransferExpense: Boolean = false,
    /** The two accounts' groups; Stats names a transfer's slice after one of them. */
    val accountGroupId: String? = null,
    val toAccountGroupId: String? = null,
    val accountGroupName: String? = null,
    val toAccountGroupName: String? = null,
    /** An installment plan's part: "2/6" in the lists. */
    val installmentTotal: Int? = null,
    val installmentIndex: Int? = null,
    /** The From (or only) account's side in its currency; null = [amount]. */
    val accountAmount: Long? = null,
    val accountCurrency: String? = null,
    val toAccountCurrency: String? = null,
    /** A live attachment exists (the day rows and Search show a photo icon). */
    val hasPhoto: Boolean = false,
) {
    /** What the entry does to its From (or only) account, before the sign. */
    val accountSide: Long get() = accountAmount ?: amount
}

data class TxnIdDate(val id: String, val dateTime: String)

/** A bookmark joined with the names the Bookmarks list displays. */
data class BookmarkRow(
    val id: String,
    val type: TxnType,
    val amount: Long,
    val currency: String,
    val note: String,
    val accountName: String?,
    val toAccountName: String?,
    val categoryName: String?,
    val parentCategoryName: String?,
) {
    /** The shape `TxnRowItem` draws; the date and ids are not shown. */
    fun asTxnRow() = TxnRow(
        id = id, type = type, dateTime = "", amount = amount, toAmount = null, amountMain = amount, currency = currency,
        fee = 0, note = note, description = "", accountId = "", accountName = accountName.orEmpty(), toAccountId = null,
        toAccountName = toAccountName, categoryId = null, categoryName = categoryName,
        parentCategoryName = parentCategoryName,
    )
}

/** How often and how recently a note was used (autocomplete). */
data class NoteUse(val note: String, val uses: Int, val lastUsed: String)

/** A repeat rule joined with the names Repeat Setting shows. */
data class RepeatRuleRow(
    val id: String,
    val frequency: RepeatFrequency,
    val startDate: String,
    val lastDate: String,
    val installments: Int?,
    val type: TxnType,
    val amount: Long,
    val currency: String,
    val note: String,
    val accountName: String?,
    val toAccountName: String?,
    val categoryName: String?,
    val parentCategoryName: String?,
)

data class AccountBalanceRow(
    val id: String,
    val groupId: String,
    val groupName: String,
    val groupType: AccountGroupType,
    val groupSortOrder: Int,
    val name: String,
    val currency: String,
    val includeInTotals: Boolean,
    val hidden: Boolean,
    val balance: Long,
    /** Card accounts: Account Info → Settlement Date and Payment Date (1–31), and the Pay account. */
    val statementDay: Int? = null,
    val paymentDay: Int? = null,
    val paymentAccountId: String? = null,
)

/** Money that left a card account (Accounts tab → Balance Payable / Outst. Balance). */
data class CardChargeRow(val accountId: String, val dateTime: String, val amount: Long)

private const val TXN_ROWS = """
    SELECT t.id, t.type, t.dateTime, t.amount, t.toAmount, t.amountMain, t.currency, t.fee, t.note, t.description,
           t.accountId, a.name AS accountName, t.toAccountId, ta.name AS toAccountName,
           t.categoryId, c.name AS categoryName, p.name AS parentCategoryName,
           c.parentId AS parentCategoryId, g.type AS accountGroupType, tg.type AS toAccountGroupType,
           t.installmentTotal, t.installmentIndex,
           t.accountAmount, a.currency AS accountCurrency, ta.currency AS toAccountCurrency,
           EXISTS(SELECT 1 FROM attachment x WHERE x.txnId = t.id AND x.deleted = 0) AS hasPhoto,
           a.transferExpense AS fromTransferExpense, ta.transferExpense AS toTransferExpense,
           g.id AS accountGroupId, tg.id AS toAccountGroupId, g.name AS accountGroupName, tg.name AS toAccountGroupName
    FROM txn t
    JOIN account a ON a.id = t.accountId
    LEFT JOIN account_group g ON g.id = a.groupId
    LEFT JOIN account ta ON ta.id = t.toAccountId
    LEFT JOIN account_group tg ON tg.id = ta.groupId
    LEFT JOIN category c ON c.id = t.categoryId
    LEFT JOIN category p ON p.id = c.parentId
    WHERE t.deleted = 0 AND t.dateTime >= :from AND t.dateTime < :to
"""

private const val BALANCE_SELECT = """
    SELECT a.id, a.groupId, g.name AS groupName, g.type AS groupType, g.sortOrder AS groupSortOrder,
           a.name, a.currency, a.includeInTotals, a.hidden, a.statementDay, a.paymentDay, a.paymentAccountId,
           a.initialBalance
           + COALESCE((SELECT SUM(CASE t.type WHEN 'INCOME' THEN COALESCE(t.accountAmount, t.amount)
                                              WHEN 'EXPENSE' THEN -COALESCE(t.accountAmount, t.amount)
                                              ELSE -(COALESCE(t.accountAmount, t.amount) + t.fee) END)
                       FROM txn t WHERE t.deleted = 0 AND t.accountId = a.id AND t.dateTime < :until), 0)
           + COALESCE((SELECT SUM(COALESCE(t.toAmount, t.amount)) FROM txn t
                       WHERE t.deleted = 0 AND t.type = 'TRANSFER' AND t.toAccountId = a.id
                         AND t.dateTime < :until), 0) AS balance
    FROM account a JOIN account_group g ON g.id = a.groupId
"""

private const val BALANCES = BALANCE_SELECT + """
    WHERE a.deleted = 0 AND a.state = 'ACTIVE' AND g.state = 'ACTIVE'
    ORDER BY g.sortOrder, a.sortOrder, a.name
"""

@Dao
abstract class TxnDao {
    /** Sync side: stores the rows exactly as given (a clean row stays clean). Only data/sync may call it. */
    @Upsert abstract suspend fun applyRemoteAll(rows: List<TxnEntity>)

    @Query("SELECT * FROM txn WHERE dirty != 0") abstract suspend fun dirtyRows(): List<TxnEntity>
    @Query("UPDATE txn SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt") abstract suspend fun clearDirty(id: String, updatedAt: Long)
    /** dirty = 2 on purpose: the monotonic trigger only fires for dirty = 1, so a mark-all keeps every updatedAt. */
    @Query("UPDATE txn SET dirty = 2 WHERE dirty = 0") abstract suspend fun markAllDirty()
    @Query("SELECT COUNT(*) FROM txn WHERE dirty != 0") abstract fun observeDirtyCount(): Flow<Int>

    /** Local writes: always dirty. */
    open suspend fun upsert(txn: TxnEntity) = applyRemoteAll(listOf(txn.copy(dirty = true)))
    open suspend fun upsertAll(rows: List<TxnEntity>) = applyRemoteAll(rows.map { it.copy(dirty = true) })

    @Query("SELECT * FROM txn WHERE id = :id")
    abstract suspend fun get(id: String): TxnEntity?

    /** Which of [ids] are live entries. Importers skip only these, so a re-import after a reset brings tombstoned entries back. */
    @Query("SELECT id FROM txn WHERE id IN (:ids) AND deleted = 0")
    abstract suspend fun liveIds(ids: List<String>): List<String>

    /** Which of [ids] exist at all, tombstones included (the repeat generator never re-adds a deleted occurrence). */
    @Query("SELECT id FROM txn WHERE id IN (:ids)")
    abstract suspend fun existingIds(ids: List<String>): List<String>

    @Query("SELECT COUNT(*) FROM txn WHERE deleted = 0")
    abstract suspend fun count(): Int

    /** Every row, tombstones included (backup). */
    @Query("SELECT * FROM txn")
    abstract suspend fun allRows(): List<TxnEntity>

    @Query("DELETE FROM txn")
    abstract suspend fun deleteAll()

    /** Live entries, oldest first (legacy import finds the first day other data covers). */
    @Query("SELECT id, dateTime FROM txn WHERE deleted = 0 ORDER BY dateTime")
    abstract suspend fun idsByDate(): List<TxnIdDate>

    @Query("UPDATE txn SET deleted = 1, dirty = 1, updatedAt = :now WHERE deleted = 0")
    abstract suspend fun softDeleteAll(now: Long)

    @Query("UPDATE txn SET deleted = 1, dirty = 1, updatedAt = :now WHERE id = :id")
    abstract suspend fun softDelete(id: String, now: Long)

    /** Live entries from or into [accountId] (changing its currency). */
    @Query("SELECT * FROM txn WHERE deleted = 0 AND (accountId = :accountId OR toAccountId = :accountId)")
    abstract suspend fun forAccount(accountId: String): List<TxnEntity>

    @Query(TXN_ROWS + " ORDER BY t.dateTime DESC")
    abstract fun observeRange(from: String, to: String): Flow<List<TxnRow>>

    @Query(TXN_ROWS + " AND (t.accountId = :accountId OR t.toAccountId = :accountId) ORDER BY t.dateTime DESC")
    abstract fun observeRangeForAccount(accountId: String, from: String, to: String): Flow<List<TxnRow>>

    /** Notes of live [type] (TxnType.name) entries saved at or after [since] (epoch ms), with their use counts. */
    @Query(
        "SELECT note, COUNT(*) AS uses, MAX(dateTime) AS lastUsed FROM txn " +
            "WHERE deleted = 0 AND type = :type AND note != '' AND updatedAt >= :since GROUP BY note",
    )
    abstract suspend fun noteUses(type: String, since: Long): List<NoteUse>

    /** Moves live entries of the categories [from] to [to] (deleting a category with "Move entries…"). */
    @Query("UPDATE txn SET categoryId = :to, dirty = 1, updatedAt = :now WHERE deleted = 0 AND categoryId IN (:from)")
    abstract suspend fun moveCategory(from: List<String>, to: String, now: Long)
}

/** A deleted group and how many of its accounts are still normal. */
data class DeletedGroupRow(val id: String, val name: String, val type: AccountGroupType, val sortOrder: Int, val accountCount: Int)

@Dao
abstract class AccountDao {
    /** Sync side: stores the rows exactly as given (a clean row stays clean). Only data/sync may call them. */
    @Upsert abstract suspend fun applyRemoteGroups(groups: List<AccountGroupEntity>)
    @Upsert abstract suspend fun applyRemoteAccounts(accounts: List<AccountEntity>)

    /** Local writes: always dirty. */
    open suspend fun upsertGroups(groups: List<AccountGroupEntity>) = applyRemoteGroups(groups.map { it.copy(dirty = true) })
    open suspend fun upsert(account: AccountEntity) = applyRemoteAccounts(listOf(account.copy(dirty = true)))
    open suspend fun upsertAll(accounts: List<AccountEntity>) = applyRemoteAccounts(accounts.map { it.copy(dirty = true) })

    @Query("SELECT * FROM account_group WHERE dirty != 0") abstract suspend fun dirtyGroups(): List<AccountGroupEntity>
    @Query("SELECT * FROM account WHERE dirty != 0") abstract suspend fun dirtyAccounts(): List<AccountEntity>
    @Query("UPDATE account_group SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt") abstract suspend fun clearDirtyGroup(id: String, updatedAt: Long)
    @Query("UPDATE account SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt") abstract suspend fun clearDirtyAccount(id: String, updatedAt: Long)
    /** dirty = 2 on purpose: the monotonic trigger only fires for dirty = 1, so a mark-all keeps every updatedAt. */
    @Query("UPDATE account_group SET dirty = 2 WHERE dirty = 0") abstract suspend fun markAllGroupsDirty()
    @Query("UPDATE account SET dirty = 2 WHERE dirty = 0") abstract suspend fun markAllAccountsDirty()
    @Query("SELECT COUNT(*) FROM account_group WHERE dirty != 0") abstract fun observeDirtyGroupCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM account WHERE dirty != 0") abstract fun observeDirtyAccountCount(): Flow<Int>

    /** Normal groups only (pickers, new accounts). */
    @Query("SELECT * FROM account_group WHERE deleted = 0 AND state = 'ACTIVE' ORDER BY sortOrder")
    abstract suspend fun groups(): List<AccountGroupEntity>

    /** Normal accounts in normal groups. */
    @Query(
        "SELECT a.* FROM account a JOIN account_group g ON g.id = a.groupId " +
            "WHERE a.deleted = 0 AND a.state = 'ACTIVE' AND g.state = 'ACTIVE' ORDER BY g.sortOrder, a.sortOrder, a.name",
    )
    abstract suspend fun accounts(): List<AccountEntity>

    /** Every non-tombstoned account in any state (importers match by name, so a re-import never duplicates a deleted account). */
    @Query("SELECT a.* FROM account a JOIN account_group g ON g.id = a.groupId WHERE a.deleted = 0 ORDER BY g.sortOrder, a.sortOrder, a.name")
    abstract suspend fun allAccounts(): List<AccountEntity>

    /** Deleted accounts whose group is normal (Deleted accounts). */
    @Query(
        "SELECT a.* FROM account a JOIN account_group g ON g.id = a.groupId " +
            "WHERE a.deleted = 0 AND a.state = 'DELETED' AND g.state = 'ACTIVE' AND g.deleted = 0 ORDER BY g.sortOrder, a.sortOrder, a.name",
    )
    abstract fun observeDeletedAccounts(): Flow<List<AccountEntity>>

    /** Deleted groups with how many of their accounts are neither deleted nor permanently deleted (Deleted account group). */
    @Query(
        "SELECT g.id, g.name, g.type, g.sortOrder, " +
            "(SELECT COUNT(*) FROM account a WHERE a.groupId = g.id AND a.deleted = 0 AND a.state = 'ACTIVE') AS accountCount " +
            "FROM account_group g WHERE g.deleted = 0 AND g.state = 'DELETED' ORDER BY g.sortOrder",
    )
    abstract fun observeDeletedGroups(): Flow<List<DeletedGroupRow>>

    /** Transfer-Expense setting: non-deleted accounts in normal groups, any visibility; the repository filters by group type. */
    @Query(
        "SELECT a.* FROM account a JOIN account_group g ON g.id = a.groupId " +
            "WHERE a.deleted = 0 AND a.state = 'ACTIVE' AND g.state = 'ACTIVE' AND g.deleted = 0 ORDER BY g.sortOrder, a.sortOrder, a.name",
    )
    abstract fun observeActiveAccounts(): Flow<List<AccountEntity>>

    @Query("UPDATE account SET state = :state, dirty = 1, updatedAt = :now WHERE id = :id")
    abstract suspend fun setState(id: String, state: ArchiveState, now: Long)

    @Query("UPDATE account SET hidden = 0, state = 'ACTIVE', dirty = 1, updatedAt = :now WHERE id = :id")
    abstract suspend fun restore(id: String, now: Long)

    @Query("UPDATE account SET transferExpense = :on, dirty = 1, updatedAt = :now WHERE id = :id")
    abstract suspend fun setTransferExpense(id: String, on: Boolean, now: Long)

    @Query("UPDATE account_group SET state = :state, dirty = 1, updatedAt = :now WHERE id = :id")
    abstract suspend fun setGroupState(id: String, state: ArchiveState, now: Long)

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM account_group WHERE deleted = 0")
    abstract suspend fun nextGroupSortOrder(): Int

    @Query("SELECT * FROM account WHERE id = :id")
    abstract suspend fun get(id: String): AccountEntity?

    @Query("SELECT * FROM account_group WHERE deleted = 0 AND state = 'ACTIVE' ORDER BY sortOrder")
    abstract fun observeGroups(): Flow<List<AccountGroupEntity>>

    @Query("SELECT * FROM account_group WHERE id = :id")
    abstract suspend fun getGroup(id: String): AccountGroupEntity?

    @Query("SELECT * FROM account_group")
    abstract suspend fun allGroupRows(): List<AccountGroupEntity>

    @Query("SELECT * FROM account")
    abstract suspend fun allAccountRows(): List<AccountEntity>

    @Query("DELETE FROM account_group")
    abstract suspend fun deleteAllGroups()

    @Query("DELETE FROM account")
    abstract suspend fun deleteAllAccounts()

    @Query("UPDATE account SET deleted = 1, dirty = 1, updatedAt = :now WHERE deleted = 0")
    abstract suspend fun softDeleteAllAccounts(now: Long)

    @Query("UPDATE account_group SET deleted = 1, dirty = 1, updatedAt = :now WHERE deleted = 0")
    abstract suspend fun softDeleteAllGroups(now: Long)

    @Query(BALANCES)
    abstract fun observeBalances(until: String): Flow<List<AccountBalanceRow>>

    /** One account in any state (the read-only detail of a deleted account); null when it does not exist. */
    @Query(BALANCE_SELECT + " WHERE a.deleted = 0 AND a.id = :id")
    abstract fun observeAnyBalance(id: String, until: String): Flow<AccountBalanceRow?>

    @Query(
        "SELECT t.accountId, t.dateTime, COALESCE(t.accountAmount, t.amount) + t.fee AS amount FROM txn t " +
            "JOIN account a ON a.id = t.accountId JOIN account_group g ON g.id = a.groupId " +
            "WHERE t.deleted = 0 AND a.deleted = 0 AND g.type = 'CARD' AND t.type IN ('EXPENSE', 'TRANSFER')",
    )
    abstract fun observeCardCharges(): Flow<List<CardChargeRow>>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM account WHERE groupId = :groupId AND deleted = 0 AND state = 'ACTIVE'")
    abstract suspend fun nextSortOrder(groupId: String): Int
}

@Dao
abstract class CategoryDao {
    /** Sync side: stores the rows exactly as given (a clean row stays clean). Only data/sync may call it. */
    @Upsert abstract suspend fun applyRemoteAll(rows: List<CategoryEntity>)

    @Query("SELECT * FROM category WHERE dirty != 0") abstract suspend fun dirtyRows(): List<CategoryEntity>
    @Query("UPDATE category SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt") abstract suspend fun clearDirty(id: String, updatedAt: Long)
    /** dirty = 2 on purpose: the monotonic trigger only fires for dirty = 1, so a mark-all keeps every updatedAt. */
    @Query("UPDATE category SET dirty = 2 WHERE dirty = 0") abstract suspend fun markAllDirty()
    @Query("SELECT COUNT(*) FROM category WHERE dirty != 0") abstract fun observeDirtyCount(): Flow<Int>

    /** Local writes: always dirty. */
    open suspend fun upsert(category: CategoryEntity) = applyRemoteAll(listOf(category.copy(dirty = true)))
    open suspend fun upsertAll(rows: List<CategoryEntity>) = applyRemoteAll(rows.map { it.copy(dirty = true) })

    /** [type] is CategoryType.name. */
    @Query("SELECT * FROM category WHERE deleted = 0 AND hidden = 0 AND parentId IS NULL AND type = :type ORDER BY sortOrder, name")
    abstract suspend fun top(type: String): List<CategoryEntity>

    @Query("SELECT * FROM category WHERE deleted = 0")
    abstract suspend fun all(): List<CategoryEntity>

    @Query("SELECT * FROM category")
    abstract suspend fun allRows(): List<CategoryEntity>

    @Query("DELETE FROM category")
    abstract suspend fun deleteAll()

    @Query("UPDATE category SET deleted = 1, dirty = 1, updatedAt = :now WHERE deleted = 0")
    abstract suspend fun softDeleteAll(now: Long)

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM category WHERE type = :type AND deleted = 0")
    abstract suspend fun nextSortOrder(type: String): Int

    /** Top-level categories of one type for the settings list, hidden ones included. */
    @Query("SELECT * FROM category WHERE deleted = 0 AND parentId IS NULL AND type = :type ORDER BY sortOrder, name")
    abstract fun observeTop(type: String): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM category WHERE id = :id")
    abstract suspend fun get(id: String): CategoryEntity?

    @Query("SELECT * FROM category WHERE parentId = :parentId AND deleted = 0")
    abstract suspend fun children(parentId: String): List<CategoryEntity>

    @Query("SELECT * FROM category WHERE parentId = :parentId AND deleted = 0 ORDER BY sortOrder, name")
    abstract fun observeChildren(parentId: String): Flow<List<CategoryEntity>>

    /** Every live subcategory of one type (the settings list's "Food (4)" counts and names). */
    @Query("SELECT * FROM category WHERE deleted = 0 AND parentId IS NOT NULL AND type = :type ORDER BY sortOrder, name")
    abstract fun observeSubs(type: String): Flow<List<CategoryEntity>>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM category WHERE parentId = :parentId AND deleted = 0")
    abstract suspend fun nextChildSortOrder(parentId: String): Int
}

@Dao
abstract class MemoDao {
    /** Sync side: stores the rows exactly as given (a clean row stays clean). Only data/sync may call it. */
    @Upsert abstract suspend fun applyRemoteAll(rows: List<MemoEntity>)

    @Query("SELECT * FROM memo WHERE dirty != 0") abstract suspend fun dirtyRows(): List<MemoEntity>
    @Query("UPDATE memo SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt") abstract suspend fun clearDirty(id: String, updatedAt: Long)
    /** dirty = 2 on purpose: the monotonic trigger only fires for dirty = 1, so a mark-all keeps every updatedAt. */
    @Query("UPDATE memo SET dirty = 2 WHERE dirty = 0") abstract suspend fun markAllDirty()
    @Query("SELECT COUNT(*) FROM memo WHERE dirty != 0") abstract fun observeDirtyCount(): Flow<Int>

    /** Local writes: always dirty. */
    open suspend fun upsert(memo: MemoEntity) = applyRemoteAll(listOf(memo.copy(dirty = true)))
    open suspend fun upsertAll(rows: List<MemoEntity>) = applyRemoteAll(rows.map { it.copy(dirty = true) })

    @Query("SELECT * FROM memo WHERE id = :id")
    abstract suspend fun get(id: String): MemoEntity?

    /** Live memos dated [from] ≤ date < [to] (yyyy-MM-dd): pinned first, then newest. */
    @Query("SELECT * FROM memo WHERE deleted = 0 AND date >= :from AND date < :to ORDER BY pinned DESC, date DESC, updatedAt DESC")
    abstract fun observeRange(from: String, to: String): Flow<List<MemoEntity>>

    /** Every row, tombstones included (backup). */
    @Query("SELECT * FROM memo")
    abstract suspend fun allRows(): List<MemoEntity>

    @Query("DELETE FROM memo")
    abstract suspend fun deleteAll()

    @Query("UPDATE memo SET deleted = 1, dirty = 1, updatedAt = :now WHERE deleted = 0")
    abstract suspend fun softDeleteAll(now: Long)

    @Query("UPDATE memo SET deleted = 1, dirty = 1, updatedAt = :now WHERE id = :id")
    abstract suspend fun softDelete(id: String, now: Long)
}

@Dao
abstract class BookmarkDao {
    /** Sync side: stores the rows exactly as given (a clean row stays clean). Only data/sync may call it. */
    @Upsert abstract suspend fun applyRemoteAll(rows: List<BookmarkEntity>)

    @Query("SELECT * FROM bookmark WHERE dirty != 0") abstract suspend fun dirtyRows(): List<BookmarkEntity>
    @Query("UPDATE bookmark SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt") abstract suspend fun clearDirty(id: String, updatedAt: Long)
    /** dirty = 2 on purpose: the monotonic trigger only fires for dirty = 1, so a mark-all keeps every updatedAt. */
    @Query("UPDATE bookmark SET dirty = 2 WHERE dirty = 0") abstract suspend fun markAllDirty()
    @Query("SELECT COUNT(*) FROM bookmark WHERE dirty != 0") abstract fun observeDirtyCount(): Flow<Int>

    /** Local writes: always dirty. */
    open suspend fun upsert(bookmark: BookmarkEntity) = applyRemoteAll(listOf(bookmark.copy(dirty = true)))
    open suspend fun upsertAll(rows: List<BookmarkEntity>) = applyRemoteAll(rows.map { it.copy(dirty = true) })

    @Query("SELECT * FROM bookmark WHERE id = :id")
    abstract suspend fun get(id: String): BookmarkEntity?

    @Query(
        """
        SELECT b.id, b.type, b.amount, b.currency, b.note, a.name AS accountName, ta.name AS toAccountName,
               c.name AS categoryName, p.name AS parentCategoryName
        FROM bookmark b
        LEFT JOIN account a ON a.id = b.accountId
        LEFT JOIN account ta ON ta.id = b.toAccountId
        LEFT JOIN category c ON c.id = b.categoryId
        LEFT JOIN category p ON p.id = c.parentId
        WHERE b.deleted = 0
        ORDER BY b.createdAt DESC
        """,
    )
    abstract fun observeRows(): Flow<List<BookmarkRow>>

    /** Every row, tombstones included (backup). */
    @Query("SELECT * FROM bookmark")
    abstract suspend fun allRows(): List<BookmarkEntity>

    @Query("UPDATE bookmark SET categoryId = :to, dirty = 1, updatedAt = :now WHERE deleted = 0 AND categoryId IN (:from)")
    abstract suspend fun moveCategory(from: List<String>, to: String, now: Long)

    @Query("DELETE FROM bookmark")
    abstract suspend fun deleteAll()

    @Query("UPDATE bookmark SET deleted = 1, dirty = 1, updatedAt = :now WHERE deleted = 0")
    abstract suspend fun softDeleteAll(now: Long)

    @Query("UPDATE bookmark SET deleted = 1, dirty = 1, updatedAt = :now WHERE id = :id")
    abstract suspend fun softDelete(id: String, now: Long)
}

@Dao
abstract class RepeatRuleDao {
    /** Sync side: stores the rows exactly as given (a clean row stays clean). Only data/sync may call it. */
    @Upsert abstract suspend fun applyRemoteAll(rows: List<RepeatRuleEntity>)

    @Query("SELECT * FROM repeat_rule WHERE dirty != 0") abstract suspend fun dirtyRows(): List<RepeatRuleEntity>
    @Query("UPDATE repeat_rule SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt") abstract suspend fun clearDirty(id: String, updatedAt: Long)
    /** dirty = 2 on purpose: the monotonic trigger only fires for dirty = 1, so a mark-all keeps every updatedAt. */
    @Query("UPDATE repeat_rule SET dirty = 2 WHERE dirty = 0") abstract suspend fun markAllDirty()
    @Query("SELECT COUNT(*) FROM repeat_rule WHERE dirty != 0") abstract fun observeDirtyCount(): Flow<Int>

    /** Local writes: always dirty. */
    open suspend fun upsert(rule: RepeatRuleEntity) = applyRemoteAll(listOf(rule.copy(dirty = true)))
    open suspend fun upsertAll(rows: List<RepeatRuleEntity>) = applyRemoteAll(rows.map { it.copy(dirty = true) })

    @Query("SELECT * FROM repeat_rule WHERE id = :id")
    abstract suspend fun get(id: String): RepeatRuleEntity?

    @Query("SELECT * FROM repeat_rule WHERE deleted = 0")
    abstract suspend fun live(): List<RepeatRuleEntity>

    @Query(
        """
        SELECT r.id, r.frequency, r.startDate, r.lastDate, r.installments, r.type, r.amount, r.currency, r.note,
               a.name AS accountName, ta.name AS toAccountName, c.name AS categoryName, p.name AS parentCategoryName
        FROM repeat_rule r
        LEFT JOIN account a ON a.id = r.accountId
        LEFT JOIN account ta ON ta.id = r.toAccountId
        LEFT JOIN category c ON c.id = r.categoryId AND c.deleted = 0
        LEFT JOIN category p ON p.id = c.parentId AND p.deleted = 0
        WHERE r.deleted = 0
        """,
    )
    abstract fun observeRows(): Flow<List<RepeatRuleRow>>

    /** Every row, tombstones included (backup). */
    @Query("SELECT * FROM repeat_rule")
    abstract suspend fun allRows(): List<RepeatRuleEntity>

    @Query("DELETE FROM repeat_rule")
    abstract suspend fun deleteAll()

    @Query("UPDATE repeat_rule SET deleted = 1, dirty = 1, updatedAt = :now WHERE deleted = 0")
    abstract suspend fun softDeleteAll(now: Long)

    @Query("UPDATE repeat_rule SET deleted = 1, dirty = 1, updatedAt = :now WHERE id = :id")
    abstract suspend fun softDelete(id: String, now: Long)

    @Query("UPDATE repeat_rule SET categoryId = :to, dirty = 1, updatedAt = :now WHERE deleted = 0 AND categoryId IN (:from)")
    abstract suspend fun moveCategory(from: List<String>, to: String, now: Long)
}

@Dao
abstract class CurrencyDao {
    @Query("SELECT * FROM currency ORDER BY sortOrder, code")
    abstract fun observeAll(): Flow<List<CurrencyEntity>>

    @Query("SELECT * FROM currency ORDER BY sortOrder, code")
    abstract suspend fun allRows(): List<CurrencyEntity>

    @Query("SELECT * FROM currency WHERE code = :code")
    abstract suspend fun get(code: String): CurrencyEntity?

    /** Sync side: stores the rows exactly as given (a clean row stays clean). Only data/sync may call it. */
    @Upsert abstract suspend fun applyRemoteAll(rows: List<CurrencyEntity>)

    @Query("SELECT * FROM currency WHERE dirty != 0") abstract suspend fun dirtyRows(): List<CurrencyEntity>
    @Query("UPDATE currency SET dirty = 0 WHERE code = :code AND updatedAt = :updatedAt") abstract suspend fun clearDirty(code: String, updatedAt: Long)
    /** dirty = 2 on purpose: the monotonic trigger only fires for dirty = 1, so a mark-all keeps every updatedAt. */
    @Query("UPDATE currency SET dirty = 2 WHERE dirty = 0") abstract suspend fun markAllDirty()
    @Query("SELECT COUNT(*) FROM currency WHERE dirty != 0") abstract fun observeDirtyCount(): Flow<Int>

    /** Local writes: always dirty. */
    open suspend fun upsert(row: CurrencyEntity) = applyRemoteAll(listOf(row.copy(dirty = true)))
    open suspend fun upsertAll(rows: List<CurrencyEntity>) = applyRemoteAll(rows.map { it.copy(dirty = true) })

    @Query("DELETE FROM currency")
    abstract suspend fun deleteAll()

    @Query("UPDATE currency SET deleted = 1, dirty = 1, updatedAt = :now WHERE deleted = 0")
    abstract suspend fun softDeleteAll(now: Long)
}
@Dao
abstract class BudgetDao {
    /** Sync side: stores the rows exactly as given (a clean row stays clean). Only data/sync may call it. */
    @Upsert abstract suspend fun applyRemoteAll(rows: List<BudgetEntity>)

    @Query("SELECT * FROM budget WHERE dirty != 0") abstract suspend fun dirtyRows(): List<BudgetEntity>
    @Query("UPDATE budget SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt") abstract suspend fun clearDirty(id: String, updatedAt: Long)
    /** dirty = 2 on purpose: the monotonic trigger only fires for dirty = 1, so a mark-all keeps every updatedAt. */
    @Query("UPDATE budget SET dirty = 2 WHERE dirty = 0") abstract suspend fun markAllDirty()
    @Query("SELECT COUNT(*) FROM budget WHERE dirty != 0") abstract fun observeDirtyCount(): Flow<Int>

    /** Local writes: always dirty. */
    open suspend fun upsertAll(rows: List<BudgetEntity>) = applyRemoteAll(rows.map { it.copy(dirty = true) })

    @Query("SELECT * FROM budget WHERE id = :id")
    abstract suspend fun get(id: String): BudgetEntity?

    /** Every row, tombstones included (backup, currency scaling). */
    @Query("SELECT * FROM budget")
    abstract suspend fun allRows(): List<BudgetEntity>

    @Query("SELECT * FROM budget WHERE deleted = 0")
    abstract fun observeLive(): Flow<List<BudgetEntity>>

    @Query("SELECT * FROM budget WHERE categoryId IS :categoryId")
    abstract suspend fun forCategory(categoryId: String?): List<BudgetEntity>

    @Query("UPDATE budget SET deleted = 1, dirty = 1, updatedAt = :now WHERE deleted = 0 AND categoryId IS :categoryId")
    abstract suspend fun softDelete(categoryId: String?, now: Long)

    @Query("UPDATE budget SET deleted = 1, dirty = 1, updatedAt = :now WHERE deleted = 0")
    abstract suspend fun softDeleteAll(now: Long)

    @Query("DELETE FROM budget")
    abstract suspend fun deleteAll()
}

@Dao
abstract class AttachmentDao {
    /** Sync side: stores the rows exactly as given (a clean row stays clean). Only data/sync may call it. */
    @Upsert abstract suspend fun applyRemoteAll(rows: List<AttachmentEntity>)

    @Query("SELECT * FROM attachment WHERE dirty != 0") abstract suspend fun dirtyRows(): List<AttachmentEntity>
    @Query("UPDATE attachment SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt") abstract suspend fun clearDirty(id: String, updatedAt: Long)
    /** dirty = 2 on purpose: the monotonic trigger only fires for dirty = 1, so a mark-all keeps every updatedAt. */
    @Query("UPDATE attachment SET dirty = 2 WHERE dirty = 0") abstract suspend fun markAllDirty()
    @Query("SELECT COUNT(*) FROM attachment WHERE dirty != 0") abstract fun observeDirtyCount(): Flow<Int>

    /** Local writes: always dirty. */
    open suspend fun upsertAll(rows: List<AttachmentEntity>) = applyRemoteAll(rows.map { it.copy(dirty = true) })

    @Query("SELECT * FROM attachment WHERE id = :id")
    abstract suspend fun get(id: String): AttachmentEntity?

    /** Live photos of an entry, in order. */
    @Query("SELECT * FROM attachment WHERE txnId = :txnId AND deleted = 0 ORDER BY sortOrder")
    abstract suspend fun forTxn(txnId: String): List<AttachmentEntity>

    @Query("SELECT * FROM attachment WHERE txnId = :txnId AND deleted = 0 ORDER BY sortOrder")
    abstract fun observeForTxn(txnId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachment WHERE txnId = :txnId")
    protected abstract suspend fun anyForTxn(txnId: String): List<AttachmentEntity>

    @Query("UPDATE attachment SET deleted = 1, dirty = 1, updatedAt = :now WHERE txnId = :txnId AND deleted = 0")
    abstract suspend fun tombstoneForTxn(txnId: String, now: Long)

    @Query("UPDATE attachment SET deleted = 1, dirty = 1, updatedAt = :now WHERE deleted = 0")
    abstract suspend fun softDeleteAll(now: Long)

    /** Files the sweep must keep. */
    @Query("SELECT DISTINCT fileName FROM attachment WHERE deleted = 0")
    abstract suspend fun liveFileNames(): List<String>

    /** Every row, tombstones included (backup). */
    @Query("SELECT * FROM attachment")
    abstract suspend fun allRows(): List<AttachmentEntity>

    @Query("DELETE FROM attachment")
    abstract suspend fun deleteAll()

    /** Makes [names] (in order, duplicates dropped) the entry's photos: others are tombstoned, a tombstoned one comes back; unchanged rows are not touched. */
    @Transaction
    open suspend fun replaceForTxn(txnId: String, names: List<String>, now: Long) {
        val wanted = names.distinct()
        val old = anyForTxn(txnId).associateBy { it.fileName }
        val changed = ArrayList<AttachmentEntity>()
        old.values.filter { !it.deleted && it.fileName !in wanted }.forEach { changed += it.copy(deleted = true, dirty = true, updatedAt = now) }
        wanted.forEachIndexed { i, name ->
            val row = old[name]
            if (row == null || row.deleted || row.sortOrder != i) {
                changed += AttachmentEntity(AttachmentEntity.idFor(txnId, name), txnId, name, i, now, serverVersion = row?.serverVersion ?: 0)
            }
        }
        if (changed.isNotEmpty()) upsertAll(changed)
    }
}
