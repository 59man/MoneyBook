package com.moneybook.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.ArchiveState
import com.moneybook.shared.model.CategoryType
import com.moneybook.shared.model.RepeatFrequency
import com.moneybook.shared.model.TxnType
import com.moneybook.shared.money.SymbolPosition

// Every entity carries the sync columns from the design spec:
// updatedAt (epoch ms of last local change), deleted (tombstone), dirty (not yet pushed), serverVersion (M4).

@Entity(tableName = "account_group")
data class AccountGroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: AccountGroupType,
    val sortOrder: Int,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val dirty: Boolean = true,
    val serverVersion: Long = 0,
    /** M2k: DELETED groups sit in Deleted account group; PURGED ones are never shown again. */
    @ColumnInfo(defaultValue = "ACTIVE") val state: ArchiveState = ArchiveState.ACTIVE,
)

@Entity(tableName = "account", indices = [Index("groupId")])
data class AccountEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    val name: String,
    val currency: String,
    /** Minor units. Balance = initialBalance + effect of all transactions. */
    val initialBalance: Long,
    val description: String = "",
    val includeInTotals: Boolean = true,
    val hidden: Boolean = false,
    val sortOrder: Int,
    val statementDay: Int? = null,
    val paymentDay: Int? = null,
    val paymentAccountId: String? = null,
    val autoPay: Boolean = false,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val dirty: Boolean = true,
    val serverVersion: Long = 0,
    /** M2k: DELETED accounts sit in Deleted accounts; PURGED ones are never shown again. Entries are never touched. */
    @ColumnInfo(defaultValue = "ACTIVE") val state: ArchiveState = ArchiveState.ACTIVE,
    /** M2k: More → Accounts → Transfer-Expense setting. */
    @ColumnInfo(defaultValue = "0") val transferExpense: Boolean = false,
)

@Entity(tableName = "category", indices = [Index("type"), Index("parentId")])
data class CategoryEntity(
    @PrimaryKey val id: String,
    val type: CategoryType,
    val parentId: String? = null,
    /** Realbyte keeps the emoji inside the name ("🍜 Food"); so do we. */
    val name: String,
    val sortOrder: Int,
    val hidden: Boolean = false,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val dirty: Boolean = true,
    val serverVersion: Long = 0,
)

@Entity(
    tableName = "txn",
    indices = [Index("dateTime"), Index("accountId"), Index("toAccountId"), Index("categoryId")],
)
data class TxnEntity(
    @PrimaryKey val id: String,
    val type: TxnType,
    /** DbTime format, local time. */
    val dateTime: String,
    val accountId: String,
    val toAccountId: String? = null,
    val categoryId: String? = null,
    /** Minor units in [currency]: what was entered (the lists show it). */
    val amount: Long,
    /** The receiving account's side of a transfer, in its currency; null means [amount]. */
    val toAmount: Long? = null,
    val currency: String,
    /** Minor units in the main currency. */
    val amountMain: Long,
    /** The From (or only) account's side, in that account's currency; null means [amount] (same currency). */
    val accountAmount: Long? = null,
    val fee: Long = 0,
    val note: String = "",
    val description: String = "",
    val repeatRuleId: String? = null,
    val installmentTotal: Int? = null,
    val installmentIndex: Int? = null,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val dirty: Boolean = true,
    val serverVersion: Long = 0,
)

/** A Trans → Note memo. [date] is a local date, yyyy-MM-dd, so it sorts and range-compares as text. */
@Entity(tableName = "memo", indices = [Index("date")])
data class MemoEntity(
    @PrimaryKey val id: String,
    val date: String,
    val title: String = "",
    val text: String = "",
    /** ARGB, or null for no color. */
    val color: Int? = null,
    val pinned: Boolean = false,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val dirty: Boolean = true,
    val serverVersion: Long = 0,
)

/** A saved entry template (Trans → ☆ Bookmarks). */
@Entity(tableName = "bookmark")
data class BookmarkEntity(
    @PrimaryKey val id: String,
    /** The note, or the category's name when the note is empty. */
    val name: String,
    val type: TxnType,
    val accountId: String,
    val toAccountId: String? = null,
    val categoryId: String? = null,
    /** Minor units in [currency]. */
    val amount: Long,
    val currency: String,
    val note: String = "",
    val description: String = "",
    /** Epoch ms; the list shows the newest first. */
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val dirty: Boolean = true,
    val serverVersion: Long = 0,
)

/** A repeat (Rep/Inst. → Repeat) or a monthly installment plan; each occurrence becomes an ordinary entry. */
@Entity(tableName = "repeat_rule")
data class RepeatRuleEntity(
    @PrimaryKey val id: String,
    val frequency: RepeatFrequency,
    /** yyyy-MM-dd: occurrence 0, the entry the user saved. */
    val startDate: String,
    /** yyyy-MM-dd: the last occurrence added; the generator adds the ones after it. */
    val lastDate: String,
    /** The number of parts of an installment plan; null for a repeat. */
    val installments: Int? = null,
    val type: TxnType,
    val accountId: String,
    val toAccountId: String? = null,
    val categoryId: String? = null,
    /** Minor units of each later occurrence, in [currency]. */
    val amount: Long,
    val currency: String,
    val note: String = "",
    val description: String = "",
    /** HH:mm, the time every occurrence gets. */
    val time: String,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val dirty: Boolean = true,
    val serverVersion: Long = 0,
)

/** Main/Sub Currency Setting: one row per currency the user configured (the main one too). Tombstones keep old entries' display. */
@Entity(tableName = "currency")
data class CurrencyEntity(
    @PrimaryKey val code: String,
    val symbol: String,
    val position: SymbolPosition,
    /** Display decimals (Decimal point). */
    val decimals: Int,
    /** 1 [code] = rate main, as decimal text; "1" for the main currency. */
    val rate: String,
    val sortOrder: Int,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val dirty: Boolean = true,
    val serverVersion: Long = 0,
)
/** One budget amount row (Realbyte BUDGET + BUDGET_AMOUNT): [amount] applies to [categoryId] from [fromMonth] (yyyymm, 0 = always). */
@Entity(tableName = "budget", indices = [Index("categoryId")])
data class BudgetEntity(
    @PrimaryKey val id: String,
    /** A top-level expense category, or null for the Total Budget. */
    val categoryId: String?,
    val fromMonth: Int,
    /** Main-currency minor units. */
    val amount: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val dirty: Boolean = true,
    val serverVersion: Long = 0,
) {
    companion object {
        /** Deterministic, so two devices that edit the same month write the same row. */
        fun idFor(categoryId: String?, fromMonth: Int) = "budget-${categoryId ?: "total"}-$fromMonth"
    }
}

/** M2l: one receipt photo of an entry. [fileName] is `<md5>.jpg` in the PhotoStore; two entries may share one file. */
@Entity(tableName = "attachment", indices = [Index("txnId")])
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val txnId: String,
    val fileName: String,
    val sortOrder: Int,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val dirty: Boolean = true,
    val serverVersion: Long = 0,
) {
    companion object {
        /** Deterministic, so removing a photo and adding it back revives its row. */
        fun idFor(txnId: String, fileName: String) = "$txnId/$fileName"
    }
}
