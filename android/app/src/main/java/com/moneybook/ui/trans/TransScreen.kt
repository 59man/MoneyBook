package com.moneybook.ui.trans

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moneybook.R
import com.moneybook.data.SwipeMode
import com.moneybook.ui.common.AddFab
import com.moneybook.ui.common.IconButtonBox
import com.moneybook.ui.common.MbTopBar
import com.moneybook.ui.common.MonthNavigator
import com.moneybook.ui.common.SubTabs
import com.moneybook.ui.common.SummaryBar
import com.moneybook.ui.common.appContainer
import com.moneybook.ui.common.incomeExpenseTotal
import com.moneybook.ui.more.ExcelExportButton
import com.moneybook.ui.nav.BudgetAddRoute
import com.moneybook.ui.nav.BudgetDetailRoute
import com.moneybook.ui.nav.BudgetMonthlyRoute
import com.moneybook.ui.nav.BudgetSettingRoute
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import java.time.LocalDate

@Composable
fun TransScreen(
    onAddTxn: ((LocalDate?) -> Unit)?, onOpenTxn: (String) -> Unit, onOpenMemo: (String?, LocalDate?) -> Unit,
    onOpenBookmarks: () -> Unit, onOpenSearch: () -> Unit, onNavigate: (Any) -> Unit,
) {
    val c = appContainer()
    val vm: TransViewModel = viewModel { TransViewModel(c.txns, c.settings, c.time, c.categories, c.accounts, c.memos, c.budgets) }
    val s by vm.state.collectAsStateWithLifecycle()
    val options by vm.filterOptions.collectAsStateWithLifecycle()
    var filterOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = filterOpen) { filterOpen = false }
    var sheetDay by rememberSaveable { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(Mb.colors.background)) {
            MbTopBar(
                titleInset = 0.dp,
                title = { MonthNavigator(s.title, vm::previous, vm::next) },
                actions = {
                    IconButtonBox(R.drawable.ic_star, "Bookmarks", onOpenBookmarks)
                    IconButtonBox(R.drawable.ic_search, "Search", onOpenSearch)
                    IconButtonBox(
                        R.drawable.ic_filter, "Filter", { filterOpen = true },
                        tint = if (s.filter.isActive) Mb.colors.accent else Mb.colors.textPrimary,
                    )
                },
            )
            SubTabs(TransTab.entries.map { it.label }, s.tab.ordinal) { vm.selectTab(TransTab.entries[it]) }
            if (s.tab != TransTab.NOTE) SummaryBar(incomeExpenseTotal(s.summary, s.currency))
            val swipe = when (s.swipe) {
                SwipeMode.DATE -> Modifier.swipeMonths(vm::previous, vm::next)
                SwipeMode.TAB -> Modifier.swipeMonths(vm::previousTab, vm::nextTab)
                SwipeMode.NONE -> Modifier
            }
            Box(Modifier.weight(1f).fillMaxWidth().then(swipe)) {
                when (s.tab) {
                    TransTab.DAILY -> DailyTab(s.dayGroups, s.currency, onOpenTxn)
                    TransTab.CALENDAR -> CalendarTab(s.calendar, s.weekStart, s.currency) { sheetDay = it.toString() }
                    TransTab.MONTHLY -> MonthlyTab(s.monthly, s.currency, vm::toggleMonth)
                    TransTab.TOTAL -> TotalTab(
                        s.total, s.totalRange, s.currency, s.budgets, s.budgetToday,
                        onBudgetSetting = { onNavigate(BudgetSettingRoute) },
                        onBudget = { line ->
                            onNavigate(
                                when {
                                    line.categoryId != null -> BudgetDetailRoute(line.categoryId, s.month.toString())
                                    line.synthetic -> BudgetAddRoute(total = true)
                                    else -> BudgetMonthlyRoute(null)
                                },
                            )
                        },
                    ) { ExcelExportButton(Modifier.fillMaxWidth()) }
                    TransTab.NOTE -> NoteTab(s.memos) { onOpenMemo(it, null) }
                }
                if (s.tab == TransTab.NOTE) {
                    MemoFab(onClick = { onOpenMemo(null, s.memoDate) }, modifier = Modifier.align(Alignment.BottomEnd).padding(MbDimens.fabMargin))
                } else if (onAddTxn != null) {
                    AddFab(onClick = { onAddTxn(null) }, modifier = Modifier.align(Alignment.BottomEnd).padding(MbDimens.fabMargin))
                    // Configuration → Note button setting: Realbyte's memo FAB beside +, on the Daily tab.
                    if (s.tab == TransTab.DAILY && s.noteButton) {
                        MemoFab(
                            onClick = { onOpenMemo(null, s.memoDate) },
                            modifier = Modifier.align(Alignment.BottomEnd)
                                .padding(end = MbDimens.fabMargin + MbDimens.fab + 12.dp, bottom = MbDimens.fabMargin),
                        )
                    }
                }
            }
        }
        if (filterOpen) {
            TransFilterPanel(
                s.title, options, s.filter, s.currency,
                FilterActions(
                    onPrevious = vm::previous, onNext = vm::next, onClose = { filterOpen = false },
                    onToggleCategory = vm::toggleCategory, onToggleAccount = vm::toggleAccount,
                    onAll = { tab, ticked ->
                        when (tab) {
                            FilterTab.INCOME -> vm.setAllCategories(options.income.map { it.id }, ticked)
                            FilterTab.EXPENSES -> vm.setAllCategories(options.expense.map { it.id }, ticked)
                            FilterTab.ACCOUNT -> vm.setAllAccounts(options.groups.flatMap { g -> g.accounts.map { it.id } }, ticked)
                        }
                    },
                ),
            )
        }
    }

    sheetDay?.let { iso ->
        val date = LocalDate.parse(iso)
        DaySheet(
            date = date,
            group = s.calendarDays[date],
            spec = s.currency,
            onDismiss = { sheetDay = null },
            onOpenTxn = { id -> sheetDay = null; onOpenTxn(id) },
            onAdd = onAddTxn?.let { add -> { d: LocalDate -> sheetDay = null; add(d) } },
        )
    }
}

/** Horizontal swipe changes the period (Realbyte's default "Swipe: To Change Date"). */
fun Modifier.swipeMonths(onPrevious: () -> Unit, onNext: () -> Unit): Modifier = pointerInput(onPrevious, onNext) {
    var total = 0f
    val threshold = 80.dp.toPx()
    detectHorizontalDragGestures(
        onDragStart = { total = 0f },
        onDragEnd = {
            if (total > threshold) onPrevious() else if (total < -threshold) onNext()
        },
        onHorizontalDrag = { _, dx -> total += dx },
    )
}
