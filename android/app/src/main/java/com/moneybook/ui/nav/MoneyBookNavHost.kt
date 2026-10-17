package com.moneybook.ui.nav

import com.moneybook.data.CopyDateBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.moneybook.domain.StatsPeriod
import com.moneybook.shared.model.CategoryType
import com.moneybook.shared.model.TxnType
import com.moneybook.ui.accounts.AccountDetailScreen
import com.moneybook.ui.accounts.AccountEditScreen
import com.moneybook.ui.accounts.TotalStatsScreen
import com.moneybook.ui.bookmarks.BookmarksScreen
import com.moneybook.ui.edit.EditTxnScreen
import com.moneybook.ui.memo.MemoEditScreen
import com.moneybook.MainActivity
import com.moneybook.ui.common.appContainer
import com.moneybook.ui.lock.PasscodeFlow
import com.moneybook.ui.lock.PasscodePurpose
import com.moneybook.domain.Budgets
import com.moneybook.ui.budget.BudgetAddScreen
import com.moneybook.ui.budget.BudgetDetailScreen
import com.moneybook.ui.budget.BudgetEditScreen
import com.moneybook.ui.budget.BudgetMonthlyScreen
import com.moneybook.ui.budget.BudgetSettingScreen
import com.moneybook.ui.more.AccountGroupScreen
import com.moneybook.ui.more.CurrencyEditScreen
import com.moneybook.ui.more.CurrencyListMode
import com.moneybook.ui.more.CurrencyListScreen
import com.moneybook.ui.more.MainCurrencyScreen
import com.moneybook.ui.more.SubCurrencySettingScreen
import com.moneybook.ui.more.AlarmScreen
import com.moneybook.ui.more.AccountSettingsScreen
import com.moneybook.ui.more.AccountsSettingScreen
import com.moneybook.ui.more.CardExpenseConfigScreen
import com.moneybook.ui.more.DeletedAccountsScreen
import com.moneybook.ui.more.DeletedGroupsScreen
import com.moneybook.ui.more.TransferExpenseScreen
import com.moneybook.ui.more.BackupScreen
import com.moneybook.ui.more.SyncScreen
import com.moneybook.ui.more.CategoryEditScreen
import com.moneybook.ui.more.CategorySettingScreen
import com.moneybook.ui.more.SubcategoryListScreen
import com.moneybook.ui.more.ConfigurationScreen
import com.moneybook.ui.more.GroupEditScreen
import com.moneybook.ui.more.LegacyImportScreen
import com.moneybook.ui.more.MonthlyStartDateScreen
import com.moneybook.ui.more.RepeatSettingScreen
import com.moneybook.ui.more.PasscodeSettingsScreen
import com.moneybook.ui.more.StyleScreen
import com.moneybook.ui.more.WidgetSettingsScreen
import com.moneybook.ui.search.SearchScreen
import com.moneybook.ui.stats.CategoryDetailScreen
import java.time.LocalDate

@Composable
fun MoneyBookNavHost(navController: NavHostController = rememberNavController(), open: String? = null, onOpened: () -> Unit = {}) {
    NavHost(navController, startDestination = MainRoute) {
        composable<MainRoute> { entry ->
            MainScreen(
                onAddTxn = { date -> entry.whenResumed { navController.navigate(EditTxnRoute(date = date?.toString())) } },
                onOpenTxn = { id -> entry.whenResumed { navController.navigate(EditTxnRoute(txnId = id)) } },
                onOpenAccount = { id -> entry.whenResumed { navController.navigate(AccountDetailRoute(id)) } },
                onAddAccount = { entry.whenResumed { navController.navigate(AccountEditRoute()) } },
                onOpenConfiguration = { entry.whenResumed { navController.navigate(ConfigurationRoute) } },
                onOpenAccountSettings = { entry.whenResumed { navController.navigate(AccountSettingsRoute) } },
                onOpenSync = { entry.whenResumed { navController.navigate(SyncRoute) } },
                onOpenBackup = { entry.whenResumed { navController.navigate(BackupRoute) } },
                onOpenCategoryStats = { route -> entry.whenResumed { navController.navigate(route) } },
                onOpenTotalStats = { entry.whenResumed { navController.navigate(TotalStatsRoute) } },
                onNavigate = { route -> entry.whenResumed { navController.navigate(route) } },
            )
        }
        composable<AccountDetailRoute> { entry ->
            val route = entry.toRoute<AccountDetailRoute>()
            AccountDetailScreen(
                accountId = route.accountId,
                readOnly = route.readOnly,
                onBack = { entry.whenResumed { navController.popBackStack() } },
                onOpenTxn = { id -> entry.whenResumed { navController.navigate(EditTxnRoute(txnId = id)) } },
                onEdit = { entry.whenResumed { navController.navigate(AccountEditRoute(route.accountId)) } },
                onAdd = { entry.whenResumed { navController.navigate(EditTxnRoute(accountId = route.accountId)) } },
                onPay = { pay ->
                    entry.whenResumed {
                        navController.navigate(EditTxnRoute(
                            type = TxnType.TRANSFER.name, accountId = pay.fromAccountId, toAccountId = route.accountId,
                            amount = pay.amount, note = "Payment",
                        ))
                    }
                },
            )
        }
        composable<AccountEditRoute> { entry ->
            val route = entry.toRoute<AccountEditRoute>()
            AccountEditScreen(
                accountId = route.accountId,
                onClose = { entry.whenResumed { navController.popBackStack() } },
                // back to Accounts Setting when it opened this screen, else to the main screen (a deleted account has no detail)
                onDeleted = { entry.whenResumed { if (!navController.popBackStack(AccountsSettingRoute, inclusive = false)) navController.popBackStack(MainRoute, inclusive = false) } },
                onAddCurrency = { entry.whenResumed { navController.navigate(CurrencyPickRoute(CurrencyListMode.SUB.name)) } },
            )
        }
        composable<EditTxnRoute> { entry ->
            val route = entry.toRoute<EditTxnRoute>()
            EditTxnScreen(
                txnId = route.txnId,
                initialDate = route.date?.let(LocalDate::parse),
                onClose = { entry.whenResumed { navController.popBackStack() } },
                bookmarkId = route.bookmarkId,
                ruleId = route.ruleId,
                openRepeat = route.openRepeat,
                initialType = route.type?.let(TxnType::valueOf),
                preset = route.preset,
                copyOf = route.copyOf,
                copyBy = route.copyBy?.let(CopyDateBy::valueOf),
                // The Edit screen is replaced by the copy form (Back from it returns to the list).
                onCopy = { id, by -> entry.whenResumed { navController.navigate(EditTxnRoute(copyOf = id, copyBy = by.name)) { popUpTo<EditTxnRoute> { inclusive = true } } } },
                onOpenSubCurrencies = { entry.whenResumed { navController.navigate(SubCurrencyRoute) } },
                onOpenAccountsSetting = { entry.whenResumed { navController.navigate(AccountsSettingRoute) } },
                onOpenCategories = { type -> entry.whenResumed { navController.navigate(CategorySettingRoute(type.name)) } },
            )
        }
        composable<SyncRoute> { entry ->
            SyncScreen(onBack = { entry.whenResumed { navController.popBackStack() } })
        }
        composable<BackupRoute> { entry ->
            BackupScreen(
                onBack = { entry.whenResumed { navController.popBackStack() } },
                // A picker result arrives before this screen is resumed again, so it is not guarded (and cannot be double-tapped).
                onImportLegacy = { uri -> navController.navigate(LegacyImportRoute(uri)) },
            )
        }
        composable<ConfigurationRoute> { entry ->
            ConfigurationScreen(
                onBack = { entry.whenResumed { navController.popBackStack() } },
                onOpenCategories = { type -> entry.whenResumed { navController.navigate(CategorySettingRoute(type.name)) } },
                onOpenRepeatSetting = { entry.whenResumed { navController.navigate(RepeatSettingRoute) } },
                onOpenBudgetSetting = { entry.whenResumed { navController.navigate(BudgetSettingRoute) } },
                onOpenMonthlyStartDate = { entry.whenResumed { navController.navigate(MonthlyStartDateRoute) } },
                onOpenStyle = { entry.whenResumed { navController.navigate(StyleRoute) } },
                onOpenAlarm = { entry.whenResumed { navController.navigate(AlarmRoute) } },
                onOpenWidgetSettings = { entry.whenResumed { navController.navigate(WidgetSettingsRoute) } },
                onOpenMainCurrency = { entry.whenResumed { navController.navigate(MainCurrencyRoute) } },
                onOpenSubCurrency = { entry.whenResumed { navController.navigate(SubCurrencyRoute) } },
                onOpenPasscode = { set -> entry.whenResumed { navController.navigate(PasscodeRoute(if (set) PasscodePurpose.UNLOCK_SETTINGS.name else PasscodePurpose.SET.name)) } },
            )
        }
        composable<SubCurrencyRoute> { entry ->
            SubCurrencySettingScreen(
                onBack = { entry.whenResumed { navController.popBackStack() } },
                onAdd = { entry.whenResumed { navController.navigate(CurrencyPickRoute(CurrencyListMode.SUB.name)) } },
                onEdit = { code -> entry.whenResumed { navController.navigate(CurrencyEditRoute(code)) } },
            )
        }
        composable<CurrencyPickRoute> { entry ->
            CurrencyListScreen(
                CurrencyListMode.valueOf(entry.toRoute<CurrencyPickRoute>().mode),
                onBack = { entry.whenResumed { navController.popBackStack() } },
                // After a main currency change: back on Main Currency Setting.
                onDone = { navController.popBackStack<MainCurrencyRoute>(inclusive = false) },
            )
        }
        composable<CurrencyEditRoute> { entry ->
            CurrencyEditScreen(entry.toRoute<CurrencyEditRoute>().code, onBack = { entry.whenResumed { navController.popBackStack() } })
        }
        composable<MainCurrencyRoute> { entry ->
            MainCurrencyScreen(
                onBack = { entry.whenResumed { navController.popBackStack() } },
                onChange = { entry.whenResumed { navController.navigate(CurrencyPickRoute(CurrencyListMode.MAIN.name)) } },
            )
        }
        composable<WidgetSettingsRoute> { entry -> WidgetSettingsScreen(onBack = { entry.whenResumed { navController.popBackStack() } }) }
        composable<AlarmRoute> { entry -> AlarmScreen(onBack = { entry.whenResumed { navController.popBackStack() } }) }
        composable<PasscodeRoute> { entry ->
            val device = appContainer().device
            val back = { entry.whenResumed { navController.popBackStack() } }
            val purpose = PasscodePurpose.valueOf(entry.toRoute<PasscodeRoute>().purpose)
            // The checked code opens the list in place of the pad, so Back from the list returns to Configuration.
            val done = if (purpose == PasscodePurpose.UNLOCK_SETTINGS) {
                { entry.whenResumed { navController.navigate(PasscodeSettingsRoute) { popUpTo<PasscodeRoute> { inclusive = true } } } }
            } else back
            PasscodeFlow(purpose, device, onDone = done, onBack = back)
        }
        composable<PasscodeSettingsRoute> { entry ->
            PasscodeSettingsScreen(
                onBack = { entry.whenResumed { navController.popBackStack() } },
                onTurnedOff = { entry.whenResumed { navController.popBackStack() } },
                onChange = { entry.whenResumed { navController.navigate(PasscodeRoute(PasscodePurpose.CHANGE.name)) } },
            )
        }
        composable<CategorySettingRoute> { entry ->
            val type = CategoryType.valueOf(entry.toRoute<CategorySettingRoute>().type)
            CategorySettingScreen(
                type = type,
                onBack = { entry.whenResumed { navController.popBackStack() } },
                onAdd = { entry.whenResumed { navController.navigate(CategoryEditRoute(type.name)) } },
                onEdit = { id -> entry.whenResumed { navController.navigate(CategoryEditRoute(type.name, id)) } },
                onOpenSubs = { id -> entry.whenResumed { navController.navigate(SubcategoryListRoute(type.name, id)) } },
            )
        }
        composable<SubcategoryListRoute> { entry ->
            val route = entry.toRoute<SubcategoryListRoute>()
            SubcategoryListScreen(
                parentId = route.parentId,
                type = CategoryType.valueOf(route.type),
                onBack = { entry.whenResumed { navController.popBackStack() } },
                onAdd = { entry.whenResumed { navController.navigate(CategoryEditRoute(route.type, parentId = route.parentId)) } },
                onEdit = { id -> entry.whenResumed { navController.navigate(CategoryEditRoute(route.type, id)) } },
            )
        }
        composable<CategoryEditRoute> { entry ->
            val route = entry.toRoute<CategoryEditRoute>()
            CategoryEditScreen(
                type = CategoryType.valueOf(route.type),
                categoryId = route.categoryId,
                parentId = route.parentId,
                onClose = { entry.whenResumed { navController.popBackStack() } },
            )
        }
        composable<AccountSettingsRoute> { entry ->
            AccountSettingsScreen(
                onBack = { entry.whenResumed { navController.popBackStack() } },
                onOpenGroups = { entry.whenResumed { navController.navigate(AccountGroupRoute) } },
                onOpenAccountsSetting = { entry.whenResumed { navController.navigate(AccountsSettingRoute) } },
                onOpenDeletedGroups = { entry.whenResumed { navController.navigate(DeletedGroupsRoute) } },
                onOpenDeletedAccounts = { entry.whenResumed { navController.navigate(DeletedAccountsRoute) } },
                onOpenTransferExpense = { entry.whenResumed { navController.navigate(TransferExpenseRoute) } },
                onOpenCardExpenses = { entry.whenResumed { navController.navigate(CardExpenseConfigRoute) } },
            )
        }
        composable<AccountsSettingRoute> { entry ->
            AccountsSettingScreen(
                onBack = { entry.whenResumed { navController.popBackStack() } },
                onOpenAccount = { id -> entry.whenResumed { navController.navigate(AccountEditRoute(id)) } },
                onAdd = { entry.whenResumed { navController.navigate(AccountEditRoute()) } },
            )
        }
        composable<DeletedAccountsRoute> { entry ->
            DeletedAccountsScreen(
                onBack = { entry.whenResumed { navController.popBackStack() } },
                onOpenAccount = { id -> entry.whenResumed { navController.navigate(AccountDetailRoute(id, readOnly = true)) } },
            )
        }
        composable<DeletedGroupsRoute> { entry ->
            DeletedGroupsScreen(onBack = { entry.whenResumed { navController.popBackStack() } })
        }
        composable<TransferExpenseRoute> { entry ->
            TransferExpenseScreen(onBack = { entry.whenResumed { navController.popBackStack() } })
        }
        composable<CardExpenseConfigRoute> { entry ->
            CardExpenseConfigScreen(onBack = { entry.whenResumed { navController.popBackStack() } })
        }
        composable<AccountGroupRoute> { entry ->
            AccountGroupScreen(
                onBack = { entry.whenResumed { navController.popBackStack() } },
                onEdit = { id -> entry.whenResumed { navController.navigate(GroupEditRoute(id)) } },
                onAdd = { entry.whenResumed { navController.navigate(GroupEditRoute()) } },
            )
        }
        composable<GroupEditRoute> { entry ->
            GroupEditScreen(entry.toRoute<GroupEditRoute>().groupId, onClose = { entry.whenResumed { navController.popBackStack() } })
        }
        composable<CategoryStatsRoute> { entry ->
            val route = entry.toRoute<CategoryStatsRoute>()
            CategoryDetailScreen(
                key = route.key, name = route.name, type = TxnType.valueOf(route.type), period = StatsPeriod.decode(route.period),
                onBack = { entry.whenResumed { navController.popBackStack() } },
                onOpenTxn = { id -> entry.whenResumed { navController.navigate(EditTxnRoute(txnId = id)) } },
            )
        }
        composable<TotalStatsRoute> { entry ->
            TotalStatsScreen(onBack = { entry.whenResumed { navController.popBackStack() } })
        }
        composable<MemoEditRoute> { entry ->
            val route = entry.toRoute<MemoEditRoute>()
            MemoEditScreen(
                memoId = route.memoId,
                date = route.date?.let(LocalDate::parse),
                onClose = { entry.whenResumed { navController.popBackStack() } },
            )
        }
        composable<BookmarksRoute> { entry ->
            BookmarksScreen(
                onBack = { entry.whenResumed { navController.popBackStack() } },
                onUse = { id -> entry.whenResumed { navController.navigate(EditTxnRoute(bookmarkId = id)) } },
            )
        }
        composable<SearchRoute> { entry ->
            SearchScreen(
                onBack = { entry.whenResumed { navController.popBackStack() } },
                onOpenTxn = { id -> entry.whenResumed { navController.navigate(EditTxnRoute(txnId = id)) } },
            )
        }
        composable<BudgetSettingRoute> { entry ->
            BudgetSettingScreen(
                onBack = { entry.whenResumed { navController.popBackStack() } },
                onAdd = { entry.whenResumed { navController.navigate(BudgetAddRoute()) } },
                onOpen = { id -> entry.whenResumed { navController.navigate(BudgetMonthlyRoute(id)) } },
            )
        }
        composable<BudgetAddRoute> { entry ->
            BudgetAddScreen(total = entry.toRoute<BudgetAddRoute>().total, onBack = { entry.whenResumed { navController.popBackStack() } })
        }
        composable<BudgetMonthlyRoute> { entry ->
            val route = entry.toRoute<BudgetMonthlyRoute>()
            BudgetMonthlyScreen(
                categoryId = route.categoryId,
                onBack = { entry.whenResumed { navController.popBackStack() } },
                onOpen = { ym -> entry.whenResumed { navController.navigate(BudgetEditRoute(route.categoryId, ym?.let(Budgets::key))) } },
                // A deleted budget's detail would be empty: leave it too when it is below this screen.
                onGone = { entry.whenResumed { navController.popBackStack<BudgetDetailRoute>(inclusive = true) || navController.popBackStack() } },
            )
        }
        composable<BudgetDetailRoute> { entry ->
            val route = entry.toRoute<BudgetDetailRoute>()
            BudgetDetailScreen(
                categoryId = route.categoryId, month = java.time.YearMonth.parse(route.month),
                onBack = { entry.whenResumed { navController.popBackStack() } },
                onEdit = { entry.whenResumed { navController.navigate(BudgetMonthlyRoute(route.categoryId)) } },
                onOpenTxn = { id -> entry.whenResumed { navController.navigate(EditTxnRoute(txnId = id)) } },
            )
        }
        composable<BudgetEditRoute> { entry ->
            val route = entry.toRoute<BudgetEditRoute>()
            BudgetEditScreen(route.categoryId, route.month, onBack = { entry.whenResumed { navController.popBackStack() } })
        }
        composable<RepeatSettingRoute> { entry ->
            RepeatSettingScreen(
                onBack = { entry.whenResumed { navController.popBackStack() } },
                onAdd = { entry.whenResumed { navController.navigate(EditTxnRoute(openRepeat = true)) } },
                onOpen = { id -> entry.whenResumed { navController.navigate(EditTxnRoute(ruleId = id)) } },
            )
        }
        composable<MonthlyStartDateRoute> { entry ->
            MonthlyStartDateScreen(onBack = { entry.whenResumed { navController.popBackStack() } })
        }
        composable<StyleRoute> { entry ->
            StyleScreen(onBack = { entry.whenResumed { navController.popBackStack() } })
        }
        composable<LegacyImportRoute> { entry ->
            LegacyImportScreen(
                uri = entry.toRoute<LegacyImportRoute>().uri,
                onBack = { entry.whenResumed { navController.popBackStack() } },
            )
        }
    }
    // Quick add's shortcuts: open their screen once (the lock screen, if any, stays on top until unlocked).
    LaunchedEffect(open) {
        val route: Any = when (open) {
            MainActivity.OPEN_SEARCH -> SearchRoute
            MainActivity.OPEN_BOOKMARKS -> BookmarksRoute
            MainActivity.OPEN_ADD, MainActivity.OPEN_ADD_EXPENSE -> EditTxnRoute()
            MainActivity.OPEN_ADD_INCOME -> EditTxnRoute(type = TxnType.INCOME.name)
            else -> return@LaunchedEffect
        }
        navController.navigate(route)
        onOpened()
    }
}

/**
 * Runs a navigation action only while this screen is the resumed one. A screen that is leaving is
 * no longer resumed, so the second tap of a double tap cannot push twice or pop twice; a second
 * pop would remove the main screen and leave a blank app.
 */
private fun NavBackStackEntry.whenResumed(action: () -> Unit) {
    if (lifecycle.currentState == Lifecycle.State.RESUMED) action()
}
