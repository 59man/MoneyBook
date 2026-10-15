package com.moneybook.shared.dto

import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.ArchiveState
import com.moneybook.shared.model.CategoryType
import com.moneybook.shared.model.RepeatFrequency
import com.moneybook.shared.model.TxnType
import kotlinx.serialization.Serializable

// Backup and sync shapes of the app's Room entities. The local-only columns (dirty, serverVersion) are left out.

@Serializable
data class AccountGroupDto(
    val id: String,
    val name: String,
    val type: AccountGroupType,
    val sortOrder: Int,
    val updatedAt: Long,
    val deleted: Boolean = false,
    /** Version 6. */
    val state: ArchiveState = ArchiveState.ACTIVE,
)

@Serializable
data class AccountDto(
    val id: String,
    val groupId: String,
    val name: String,
    val currency: String,
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
    /** Version 6. */
    val state: ArchiveState = ArchiveState.ACTIVE,
    /** Version 6: More → Accounts → Transfer-Expense setting. */
    val transferExpense: Boolean = false,
)

@Serializable
data class CategoryDto(
    val id: String,
    val type: CategoryType,
    val parentId: String? = null,
    val name: String,
    val sortOrder: Int,
    val hidden: Boolean = false,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

@Serializable
data class TxnDto(
    val id: String,
    val type: TxnType,
    /** Local date-time, yyyy-MM-dd'T'HH:mm:ss.SSS. */
    val dateTime: String,
    val accountId: String,
    val toAccountId: String? = null,
    val categoryId: String? = null,
    val amount: Long,
    val toAmount: Long? = null,
    val currency: String,
    val amountMain: Long,
    /** Version 4: the From (or only) account's side in its currency; null = [amount]. */
    val accountAmount: Long? = null,
    val fee: Long = 0,
    val note: String = "",
    val description: String = "",
    val repeatRuleId: String? = null,
    val installmentTotal: Int? = null,
    val installmentIndex: Int? = null,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

@Serializable
data class SettingsDto(
    val mainCurrency: String,
    val monthStartDay: Int = 1,
    /** java.time.DayOfWeek name. */
    val weekStart: String = "SUNDAY",
    val autocomplete: Boolean = true,
    /** Epoch ms of the last Autocomplete → Reset. */
    val autocompleteSince: Long = 0,
    /** M2e Configuration options, stored by enum name; every field has a default, so older backups restore. */
    val startScreen: String = "DAILY",
    val monthStartWeekend: String = "NONE",
    val swipe: String = "DATE",
    val colorSet: String = "A",
    val timeInput: String = "INPUT_DESC",
    val showDescription: Boolean = false,
    val inputOrder: String = "AMOUNT",
    val noteButton: Boolean = true,
    /** M2h More → Accounts → Card expenses display config (AT_THE_TIME or LUMP_SUM). */
    val cardExpenseMode: String = "AT_THE_TIME",
    /** Version 6: Configuration → Carry-over Setting and Subcategory. */
    val carryOver: Boolean = false,
    val subcategory: Boolean = false,
    /** Version 7: Edit → Copy's date choice (TODAY or ENTRY_DATE). */
    val copyDateBy: String = "TODAY",
)

@Serializable
data class BackupDto(
    val format: String,
    val version: Int,
    /** Local date-time the backup was made, ISO-8601. */
    val createdAt: String,
    val appVersion: String,
    val settings: SettingsDto,
    val accountGroups: List<AccountGroupDto>,
    val accounts: List<AccountDto>,
    val categories: List<CategoryDto>,
    val txns: List<TxnDto>,
    /** Version 2. A version 1 backup has neither, so both default to empty. */
    val memos: List<MemoDto> = emptyList(),
    val bookmarks: List<BookmarkDto> = emptyList(),
    /** Version 3. Older backups have none. */
    val repeatRules: List<RepeatRuleDto> = emptyList(),
    /** Version 4: Main/Sub Currency Setting. Older backups have none. */
    val currencies: List<CurrencyDto> = emptyList(),
    /** Version 5: budgets. Older backups have none. */
    val budgets: List<BudgetDto> = emptyList(),
    /** Version 7: receipt photos (the files are the zip's `photos/<fileName>` entries). Older backups have none. */
    val attachments: List<AttachmentDto> = emptyList(),
)

/** One receipt photo of entry [txnId]; [fileName] is `<md5>.jpg`. */
@Serializable
data class AttachmentDto(
    val id: String,
    val txnId: String,
    val fileName: String,
    val sortOrder: Int,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

/** One budget amount row: [amount] (main-currency minor units) for [categoryId] (null = Total) from [fromMonth] (yyyymm, 0 = always). */
@Serializable
data class BudgetDto(
    val id: String,
    val categoryId: String? = null,
    val fromMonth: Int,
    val amount: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

/** One currency's display settings and rate (1 [code] = [rate] main, decimal text). [position] is START or END. */
@Serializable
data class CurrencyDto(
    val code: String,
    val symbol: String,
    val position: String,
    val decimals: Int,
    val rate: String,
    val sortOrder: Int,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

@Serializable
data class MemoDto(
    val id: String,
    /** Local date, yyyy-MM-dd. */
    val date: String,
    val title: String = "",
    val text: String = "",
    val color: Int? = null,
    val pinned: Boolean = false,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

@Serializable
data class BookmarkDto(
    val id: String,
    val name: String,
    val type: TxnType,
    val accountId: String,
    val toAccountId: String? = null,
    val categoryId: String? = null,
    val amount: Long,
    val currency: String,
    val note: String = "",
    val description: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

@Serializable
data class RepeatRuleDto(
    val id: String,
    val frequency: RepeatFrequency,
    /** Local dates, yyyy-MM-dd: the first occurrence, and the last one added. */
    val startDate: String,
    val lastDate: String,
    /** A monthly installment plan's number of parts; null for a plain repeat. */
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
    /** Local time of each occurrence, HH:mm. */
    val time: String,
    val updatedAt: Long,
    val deleted: Boolean = false,
)
