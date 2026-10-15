package com.moneybook.ui.nav

import com.moneybook.ui.edit.TxnPreset
import kotlinx.serialization.Serializable

@Serializable object MainRoute

/** [date] is ISO yyyy-MM-dd for "add on this day" (calendar); null means today. [bookmarkId] starts a new entry from that bookmark.
 * [ruleId] edits a repeat rule (Repeat Setting); [openRepeat] starts a new entry on the Repeat list.
 * [copyOf] opens that entry as a new one (Edit → Copy), dated per [copyBy] (a CopyDateBy name). */
/** [type] is a TxnType name for a new entry (the quick add widgets' +); null is Expense. */
@Serializable data class EditTxnRoute(
    val txnId: String? = null, val date: String? = null, val bookmarkId: String? = null, val ruleId: String? = null,
    val openRepeat: Boolean = false, val type: String? = null, val copyOf: String? = null, val copyBy: String? = null,
    /** A new entry's presets (TxnPreset): + on account detail, Pay on a card. */
    val accountId: String? = null, val toAccountId: String? = null, val amount: Long? = null, val note: String? = null,
) {
    val preset: TxnPreset?
        get() = if (accountId == null && toAccountId == null && amount == null && note == null) null
        else TxnPreset(accountId, toAccountId, amount, note)
}

/** [readOnly]: a deleted account's detail (Deleted accounts), with Restore. */
@Serializable data class AccountDetailRoute(val accountId: String, val readOnly: Boolean = false)

@Serializable data class AccountEditRoute(val accountId: String? = null)

@Serializable object BackupRoute
@Serializable object SyncRoute

@Serializable object ConfigurationRoute

@Serializable object AccountSettingsRoute

@Serializable object AccountGroupRoute

@Serializable object AccountsSettingRoute

@Serializable object TransferExpenseRoute

@Serializable object CardExpenseConfigRoute

/** [groupId] null adds a group. */
@Serializable data class GroupEditRoute(val groupId: String? = null)

@Serializable object DeletedAccountsRoute

@Serializable object DeletedGroupsRoute

/** [type] is CategoryType.name. */
@Serializable data class CategorySettingRoute(val type: String)

/** [parentId] set: a subcategory of that main category (add when [categoryId] is null). */
@Serializable data class CategoryEditRoute(val type: String, val categoryId: String? = null, val parentId: String? = null)

/** The "Subcategory" list of the main category [parentId]; [type] is CategoryType.name. */
@Serializable data class SubcategoryListRoute(val type: String, val parentId: String)

/** [uri] is the content Uri of the picked file, as a string. */
@Serializable data class LegacyImportRoute(val uri: String)

/** A Stats row's detail. [type] is TxnType.name; [period] is StatsPeriod.encode(). */
@Serializable data class CategoryStatsRoute(val key: String, val name: String, val type: String, val period: String)

@Serializable object TotalStatsRoute

/** A memo to edit ([memoId]), or a new one dated [date] (ISO yyyy-MM-dd; null means today). */
@Serializable data class MemoEditRoute(val memoId: String? = null, val date: String? = null)

@Serializable object BookmarksRoute

@Serializable object SearchRoute

@Serializable object RepeatSettingRoute

@Serializable object MonthlyStartDateRoute

@Serializable object StyleRoute

/** [purpose] is PasscodePurpose.name. */
@Serializable data class PasscodeRoute(val purpose: String)

@Serializable object PasscodeSettingsRoute
@Serializable object AlarmRoute

@Serializable object WidgetSettingsRoute

@Serializable object SubCurrencyRoute

/** [mode]: a [com.moneybook.ui.more.CurrencyListMode] name. */
@Serializable data class CurrencyPickRoute(val mode: String)

@Serializable data class CurrencyEditRoute(val code: String)

@Serializable object MainCurrencyRoute

/** Configuration → Budget Setting (and Trans → Total's "Budget Setting" button). */
@Serializable object BudgetSettingRoute

/** Add Budget; [total] opens it with "· All" already chosen (the Total page's Total Budget row without a Total Budget). */
@Serializable data class BudgetAddRoute(val total: Boolean = false)

/** A budget's month-by-month screen; [categoryId] null is the Total Budget. */
@Serializable data class BudgetMonthlyRoute(val categoryId: String? = null)

/** Edit one month's amount ([month] yyyymm) or, with [month] null, the Default Budget. */
@Serializable data class BudgetEditRoute(val categoryId: String? = null, val month: Int? = null)

/** Budget detail of a category budget, opened on Trans month [month] ("2026-10"). */
@Serializable data class BudgetDetailRoute(val categoryId: String, val month: String)

