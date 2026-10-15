package com.moneybook.ui.accounts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moneybook.R
import com.moneybook.domain.AccountGroupItem
import com.moneybook.domain.AccountItem
import com.moneybook.domain.AccountsOverview
import com.moneybook.domain.CardStatus
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.ui.common.AmountText
import com.moneybook.ui.common.DragHandle
import com.moneybook.ui.common.FitText
import com.moneybook.ui.common.Hairline
import com.moneybook.ui.common.IconButtonBox
import com.moneybook.ui.common.MbTopBar
import com.moneybook.ui.common.MbIcon
import com.moneybook.ui.common.MenuPopup
import com.moneybook.ui.common.RemoveBadge
import com.moneybook.ui.common.ReorderableColumn
import com.moneybook.ui.common.SummaryBar
import com.moneybook.ui.common.SummaryColor
import com.moneybook.ui.common.SummaryItem
import com.moneybook.ui.common.TitleText
import com.moneybook.ui.common.TrashBox
import com.moneybook.ui.common.amountMaxWidth
import com.moneybook.ui.common.appContainer
import com.moneybook.ui.common.money
import com.moneybook.ui.common.specOf
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType
import kotlin.math.abs

@Composable
fun balanceColor(value: Long): Color = when {
    value > 0 -> Mb.colors.income
    value < 0 -> Mb.colors.expense
    else -> Mb.colors.textPrimary
}

/** Card columns: owed in the expense color, a credit in the income color; the amounts are shown without a sign. */
@Composable
fun cardColor(owed: Long): Color = when {
    owed > 0 -> Mb.colors.expense
    owed < 0 -> Mb.colors.income
    else -> Mb.colors.textPrimary
}

enum class AccountsMode(val title: String) {
    NORMAL("Accounts"),
    SHOW_HIDE("Show/Hide"),
    DELETE("Delete"),
    ORDER("Modify Orders"),
    /** More → Accounts → Deleted accounts: the red minus purges, a tap opens the read-only detail. */
    DELETED("Deleted accounts"),
}

/** What the ⋮ menu modes do; the defaults do nothing (tests, previews). */
data class AccountsActions(
    val onSetHidden: (id: String, hidden: Boolean) -> Unit = { _, _ -> },
    val onDelete: (id: String) -> Unit = {},
    val onReorder: (ids: List<String>) -> Unit = {},
)

@Composable
fun AccountsScreen(onOpenAccount: (String) -> Unit, onAddAccount: (() -> Unit)?, onOpenTotalStats: (() -> Unit)? = null) {
    val c = appContainer()
    val vm: AccountsViewModel = viewModel { AccountsViewModel(c.accounts, c.currencies, c.time) }
    val s by vm.state.collectAsStateWithLifecycle()
    AccountsContent(s, onOpenAccount, onAddAccount, AccountsActions(vm::setHidden, vm::delete, vm::reorder), onOpenTotalStats)
}

@Composable
fun AccountsContent(
    s: AccountsUiState,
    onOpenAccount: (String) -> Unit,
    onAddAccount: (() -> Unit)?,
    actions: AccountsActions = AccountsActions(),
    onOpenTotalStats: (() -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf(AccountsMode.NORMAL) }
    BackHandler(enabled = mode != AccountsMode.NORMAL) { mode = AccountsMode.NORMAL }
    Column(Modifier.fillMaxSize().background(Mb.colors.background)) {
        MbTopBar(
            title = { TitleText(mode.title) },
            actions = {
                if (mode != AccountsMode.NORMAL) {
                    IconButtonBox(R.drawable.ic_check, "Done", { mode = AccountsMode.NORMAL })
                } else {
                    if (onOpenTotalStats != null) IconButtonBox(R.drawable.ic_nav_stats, "Total Stats", onOpenTotalStats)
                    if (onAddAccount != null) {
                        Box {
                            IconButtonBox(R.drawable.ic_more_vert, "Menu", { menuOpen = true })
                            MenuPopup(menuOpen, onDismiss = { menuOpen = false }, items = listOf(
                                "Add" to onAddAccount,
                                "Show/Hide" to { mode = AccountsMode.SHOW_HIDE },
                                "Delete" to { mode = AccountsMode.DELETE },
                                "Modify Orders" to { mode = AccountsMode.ORDER },
                            ))
                        }
                    }
                }
            },
        )
        Hairline(px = 2)
        val o = s.overview
        SummaryBar(listOf(
            SummaryItem("Assets", money(o.assets, s.currency, withSymbol = false), SummaryColor.INCOME),
            SummaryItem("Liabilities", money(o.liabilities, s.currency, withSymbol = false), SummaryColor.EXPENSE),
            SummaryItem("Total", money(o.total, s.currency, withSymbol = false), SummaryColor.PRIMARY),
        ), height = MbDimens.accountsSummaryBar, sideInset = 4.dp, labelGap = 3.5.dp)
        val shown: AccountsOverview = if (mode == AccountsMode.SHOW_HIDE) s.withHidden else s.overview
        AccountGroupList(shown.groups, mode, s.currency, actions, onOpenAccount, showBalances = true, Modifier.weight(1f))
    }
}

/**
 * The group headers and account rows shared by the Accounts tab and More → Accounts → Accounts Setting. [showBalances] is
 * the tab's look (totals and balances); without it a row is its name and a chevron. In Delete mode the red minus arms a
 * row and the trash box that slides in deletes it at once, as in Realbyte.
 */
@Composable
internal fun AccountGroupList(
    groups: List<AccountGroupItem>,
    mode: AccountsMode,
    spec: CurrencySpec,
    actions: AccountsActions,
    onOpenAccount: (String) -> Unit,
    showBalances: Boolean,
    modifier: Modifier = Modifier,
) {
    var armed by remember(mode) { mutableStateOf<String?>(null) }
    LazyColumn(modifier.fillMaxWidth()) {
        groups.forEach { group ->
            item(key = "g-${group.id}") { GroupHeader(group, spec, showBalances) }
            if (mode == AccountsMode.ORDER) {
                item(key = "o-${group.id}") { OrderedAccounts(group.accounts, showBalances, actions.onReorder) }
            } else {
                items(group.accounts, key = { it.id }) { account ->
                    AccountRow(
                        account, mode, showBalances,
                        onClick = { onOpenAccount(account.id) },
                        onToggleHidden = { actions.onSetHidden(account.id, !account.hidden) },
                        armed = armed == account.id,
                        onArm = { armed = if (armed == account.id) null else account.id },
                        onDelete = { armed = null; actions.onDelete(account.id) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun GroupHeader(group: AccountGroupItem, spec: CurrencySpec, showBalances: Boolean) {
    Column(Modifier.background(Mb.colors.background)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = MbDimens.accountGroupHeader).padding(start = 20.dp, end = MbDimens.sidePadding, bottom = 7.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            FitText(group.name, MbType.listItem, Mb.colors.textTertiary, Modifier.weight(1f), maxLines = 3)
            if (!showBalances) Unit // Accounts Setting: the name alone
            else if (group.type == AccountGroupType.CARD) {
                CardHeaderColumn("Balance Payable", group.cardPayable, spec)
                CardHeaderColumn("Outst. Balance", group.cardOutstanding, spec)
            } else {
                AmountText(group.total, spec, balanceColor(group.total), Modifier.widthIn(max = amountMaxWidth()))
            }
        }
        Hairline()
    }
}

@Composable
private fun CardHeaderColumn(label: String, owed: Long, spec: CurrencySpec) {
    Column(Modifier.width(MbDimens.cardColumn), horizontalAlignment = Alignment.End) {
        FitText(label, MbType.summaryLabel, Mb.colors.textTertiary, textAlign = TextAlign.End)
        AmountText(abs(owed), spec, cardColor(owed))
    }
}

/** A card's payable column: the part not yet due over the overdue part (gray, as a balance: "-4.00"), then Outst. Balance. */
@Composable
private fun CardColumns(card: CardStatus, spec: CurrencySpec, endPadding: Dp) {
    val notDue = card.payable - card.overdue
    Column(Modifier.width(MbDimens.cardColumn), horizontalAlignment = Alignment.End) {
        AmountText(abs(notDue), spec, cardColor(notDue))
        if (card.overdue > 0) AmountText(-card.overdue, spec, Mb.colors.textSecondary, style = MbType.rowAccount)
    }
    AmountText(abs(card.outstanding), spec, cardColor(card.outstanding), Modifier.width(MbDimens.cardColumn).padding(end = endPadding))
}

@Composable
private fun OrderedAccounts(accounts: List<AccountItem>, showBalances: Boolean, onReorder: (List<String>) -> Unit) {
    var order by remember(accounts) { mutableStateOf(accounts) }
    ReorderableColumn(
        items = order,
        keyOf = { it.id },
        onMove = { from, to -> order = order.toMutableList().apply { add(to, removeAt(from)) } },
        onDragEnd = { onReorder(order.map { it.id }) },
    ) { account, handle ->
        AccountRow(account, AccountsMode.ORDER, showBalances, onClick = {}, onToggleHidden = {}, armed = false, onArm = {}, onDelete = {}, handle = handle)
    }
}

@Composable
private fun AccountRow(
    account: AccountItem,
    mode: AccountsMode,
    showBalances: Boolean,
    onClick: () -> Unit,
    onToggleHidden: () -> Unit,
    armed: Boolean,
    onArm: () -> Unit,
    onDelete: () -> Unit,
    handle: Modifier = Modifier,
) {
    val removable = mode == AccountsMode.DELETE || mode == AccountsMode.DELETED
    Column(Modifier.background(Mb.colors.surface).clickable(enabled = mode == AccountsMode.NORMAL || mode == AccountsMode.DELETED, onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = MbDimens.listRow).height(IntrinsicSize.Min)
                .padding(
                    start = if (removable) 0.dp else 20.dp,
                    end = if (mode == AccountsMode.NORMAL && showBalances) MbDimens.sidePadding else 0.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (removable) RemoveBadge("Delete ${account.name}", rotated = armed, onClick = onArm)
            FitText(
                account.name, MbType.listItem, if (account.hidden) Mb.colors.textSecondary else Mb.colors.textBody,
                Modifier.weight(1f).alpha(if (account.hidden && !showBalances) 0.5f else 1f), maxLines = 3,
            )
            when (mode) {
                AccountsMode.SHOW_HIDE -> IconButtonBox(
                    if (account.hidden) R.drawable.ic_visibility_off else R.drawable.ic_visibility,
                    if (account.hidden) "Show ${account.name}" else "Hide ${account.name}",
                    onToggleHidden,
                    tint = if (account.hidden) Mb.colors.textSecondary else Mb.colors.accent,
                )
                AccountsMode.ORDER -> DragHandle(handle, "Move ${account.name}")
                AccountsMode.NORMAL, AccountsMode.DELETE, AccountsMode.DELETED -> if (!showBalances) {
                    if (mode != AccountsMode.DELETE) {
                        MbIcon(R.drawable.ic_chevron_right, null, tint = Mb.colors.textSecondary, size = 20.dp)
                        Spacer(Modifier.width(MbDimens.sidePadding))
                    }
                } else {
                    val endPadding = if (removable && !armed) MbDimens.sidePadding else 0.dp
                    val card = account.card
                    if (card != null) {
                        CardColumns(card, specOf(account.currency), endPadding)
                    } else {
                        AmountText(
                            account.balance, specOf(account.currency), balanceColor(account.balance),
                            Modifier.widthIn(max = amountMaxWidth()).padding(end = endPadding),
                        )
                    }
                }
            }
            if (removable) TrashBox(armed, "Trash ${account.name}", onDelete)
        }
        Hairline()
    }
}
