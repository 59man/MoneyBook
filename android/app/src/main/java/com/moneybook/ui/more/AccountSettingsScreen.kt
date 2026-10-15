package com.moneybook.ui.more

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import com.moneybook.R
import com.moneybook.ui.accounts.AccountGroupList
import com.moneybook.ui.accounts.AccountsActions
import com.moneybook.ui.accounts.AccountsMode
import com.moneybook.ui.accounts.AccountsUiState
import com.moneybook.ui.accounts.AccountsViewModel
import com.moneybook.ui.common.IconButtonBox
import com.moneybook.ui.common.MenuPopup
import androidx.compose.foundation.background
import com.moneybook.domain.CardExpenseMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moneybook.data.AccountRepository
import com.moneybook.data.db.AccountGroupEntity
import com.moneybook.ui.common.DragHandle
import com.moneybook.ui.common.RemoveBadge
import com.moneybook.ui.common.TrashBox
import com.moneybook.data.DefaultData
import com.moneybook.shared.model.AccountGroupType
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.moneybook.ui.common.FitText
import com.moneybook.ui.common.Hairline
import com.moneybook.ui.common.EditPencil
import com.moneybook.ui.common.MbTopBar
import com.moneybook.ui.common.ListTopGap
import com.moneybook.ui.common.ReorderableColumn
import com.moneybook.ui.common.SettingsGroup
import com.moneybook.ui.common.SettingsRow
import com.moneybook.ui.common.TitleText
import com.moneybook.ui.common.appContainer
import com.moneybook.ui.common.bottomHairline
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** More → Accounts: the group list, Accounts Setting, the two deleted lists, Transfer-Expense setting and Card expenses display config. */
@Composable
fun AccountSettingsScreen(
    onBack: () -> Unit,
    onOpenGroups: () -> Unit,
    onOpenAccountsSetting: () -> Unit,
    onOpenDeletedGroups: () -> Unit,
    onOpenDeletedAccounts: () -> Unit,
    onOpenTransferExpense: () -> Unit,
    onOpenCardExpenses: () -> Unit,
) {
    val settings by appContainer().settings.settings.collectAsStateWithLifecycle(initialValue = null)
    AccountSettingsContent(settings?.cardExpenseMode, onBack, onOpenGroups, onOpenAccountsSetting, onOpenDeletedGroups, onOpenDeletedAccounts, onOpenTransferExpense, onOpenCardExpenses)
}

@Composable
fun AccountSettingsContent(
    mode: CardExpenseMode?,
    onBack: () -> Unit,
    onOpenGroups: () -> Unit,
    onOpenAccountsSetting: () -> Unit,
    onOpenDeletedGroups: () -> Unit,
    onOpenDeletedAccounts: () -> Unit,
    onOpenTransferExpense: () -> Unit,
    onOpenCardExpenses: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Mb.colors.background).systemBarsPadding()) {
        MbTopBar(onBack = onBack, title = { TitleText("Accounts") })
        Hairline(px = 2)
        SettingsGroup {
            SettingsRow("Account Group", onClick = onOpenGroups)
            SettingsRow("Accounts Setting", onClick = onOpenAccountsSetting)
            SettingsRow("Deleted account group", onClick = onOpenDeletedGroups)
            SettingsRow("Deleted accounts", onClick = onOpenDeletedAccounts)
            SettingsRow("Transfer-Expense setting", onClick = onOpenTransferExpense)
            SettingsRow("Card expenses display config", value = mode?.label, onClick = onOpenCardExpenses)
        }
    }
}

/** Accounts Setting: the accounts under their groups (hidden ones dimmed), with the Accounts tab's Delete, Show/Hide and Modify Orders modes. */
@Composable
fun AccountsSettingScreen(onBack: () -> Unit, onOpenAccount: (String) -> Unit, onAdd: () -> Unit) {
    val c = appContainer()
    val vm: AccountsViewModel = viewModel { AccountsViewModel(c.accounts, c.currencies, c.time) }
    val s by vm.state.collectAsStateWithLifecycle()
    AccountsSettingContent(s, onBack, onOpenAccount, onAdd, AccountsActions(vm::setHidden, vm::delete, vm::reorder))
}

@Composable
fun AccountsSettingContent(
    s: AccountsUiState,
    onBack: () -> Unit,
    onOpenAccount: (String) -> Unit,
    onAdd: () -> Unit,
    actions: AccountsActions = AccountsActions(),
) {
    var menuOpen by remember { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf(AccountsMode.NORMAL) }
    BackHandler(enabled = mode != AccountsMode.NORMAL) { mode = AccountsMode.NORMAL }
    Column(Modifier.fillMaxSize().background(Mb.colors.background).systemBarsPadding()) {
        MbTopBar(
            onBack = onBack,
            title = { TitleText(if (mode == AccountsMode.NORMAL) "Accounts Setting" else mode.title) },
            actions = {
                if (mode != AccountsMode.NORMAL) {
                    IconButtonBox(R.drawable.ic_check, "Done", { mode = AccountsMode.NORMAL })
                } else {
                    IconButtonBox(R.drawable.ic_add, "Add", onAdd)
                    Box {
                        IconButtonBox(R.drawable.ic_more_vert, "Menu", { menuOpen = true })
                        MenuPopup(menuOpen, onDismiss = { menuOpen = false }, items = listOf(
                            "Modify Orders" to { mode = AccountsMode.ORDER },
                            "Show/Hide" to { mode = AccountsMode.SHOW_HIDE },
                            "Delete" to { mode = AccountsMode.DELETE },
                        ))
                    }
                }
            },
        )
        Hairline(px = 2)
        AccountGroupList(s.withHidden.groups, mode, s.currency, actions, onOpenAccount, showBalances = false, Modifier.weight(1f))
    }
}

/** Capture 78: the description, then A and B side by side; a tap saves and goes back. */
@Composable
fun CardExpenseConfigScreen(onBack: () -> Unit) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    CardExpenseConfigContent(onBack) { mode ->
        scope.launch {
            c.settings.replace(c.settings.current().copy(cardExpenseMode = mode))
            onBack()
        }
    }
}

@Composable
fun CardExpenseConfigContent(onBack: () -> Unit, onPick: (CardExpenseMode) -> Unit) {
    Column(Modifier.fillMaxSize().background(Mb.colors.surface).systemBarsPadding()) {
        MbTopBar(onBack = onBack, title = { TitleText("Card expenses display config") })
        Hairline(px = 2)
        Text(
            "Card expenses display config", style = MbType.listItem, color = Mb.colors.textBody,
            modifier = Modifier.padding(start = 17.dp, end = 17.dp, top = 18.dp, bottom = 26.dp),
        )
        Text(
            "The time credit card paid. You can configure your credit card spendings to be reflected either on the moment " +
                "of usage, or show it as a lump sum on your card payment date.",
            style = MbType.rowNote, color = Mb.colors.textBody, modifier = Modifier.padding(horizontal = 17.dp),
        )
        Spacer(Modifier.height(14.dp))
        Hairline()
        Row(Modifier.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val shape = RoundedCornerShape(6.dp)
            Box(
                Modifier.weight(1f).heightIn(min = MbDimens.choiceButton).clip(shape).border(1.dp, Mb.colors.accent, shape)
                    .clickable { onPick(CardExpenseMode.AT_THE_TIME) },
                contentAlignment = Alignment.Center,
            ) { FitText(CardExpenseMode.AT_THE_TIME.label, MbType.choiceButton, Mb.colors.accent) }
            Box(
                Modifier.weight(1f).heightIn(min = MbDimens.choiceButton).clip(shape).background(Mb.colors.accent)
                    .clickable { onPick(CardExpenseMode.LUMP_SUM) },
                contentAlignment = Alignment.Center,
            ) { FitText(CardExpenseMode.LUMP_SUM.label, MbType.choiceButton, Color.White) }
        }
    }
}

class AccountGroupViewModel(private val accounts: AccountRepository) : ViewModel() {
    val groups: StateFlow<List<AccountGroupEntity>> =
        accounts.observeGroups().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun reorder(ids: List<String>) {
        viewModelScope.launch { accounts.reorderGroups(ids) }
    }

    fun delete(id: String) {
        viewModelScope.launch { accounts.deleteGroup(id) }
    }
}

/** Realbyte's four fixed groups have no red minus: they cannot be deleted. */
private val KEPT_GROUPS = listOf(AccountGroupType.CASH, AccountGroupType.ACCOUNTS, AccountGroupType.CARD, AccountGroupType.DEBIT_CARD)
    .map(DefaultData::groupId).toSet()

@Composable
fun AccountGroupScreen(onBack: () -> Unit, onEdit: (String) -> Unit, onAdd: () -> Unit) {
    val c = appContainer()
    val vm: AccountGroupViewModel = viewModel { AccountGroupViewModel(c.accounts) }
    val groups by vm.groups.collectAsStateWithLifecycle()
    AccountGroupContent(groups, onBack, onEdit, onAdd, vm::reorder, vm::delete)
}

@Composable
fun AccountGroupContent(
    groups: List<AccountGroupEntity>,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onAdd: () -> Unit,
    onReorder: (List<String>) -> Unit,
    onDelete: (String) -> Unit,
) {
    var order by remember(groups) { mutableStateOf(groups) }
    var armed by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(Mb.colors.background).systemBarsPadding()) {
        MbTopBar(onBack = onBack, title = { TitleText("Account Group") }, actions = { IconButtonBox(R.drawable.ic_add, "Add", onAdd) })
        Hairline(px = 2)
        ListTopGap()
        ReorderableColumn(
            items = order,
            keyOf = { it.id },
            onMove = { from, to -> order = order.toMutableList().apply { add(to, removeAt(from)) } },
            onDragEnd = { onReorder(order.map { it.id }) },
            modifier = Modifier.verticalScroll(rememberScrollState()),
        ) { group, handle ->
            val removable = group.id !in KEPT_GROUPS
            val isArmed = armed == group.id
            Row(
                Modifier.fillMaxWidth().background(Mb.colors.surface).bottomHairline(Mb.colors.divider)
                    .heightIn(min = MbDimens.listRow).height(IntrinsicSize.Min).padding(start = if (removable) 0.dp else 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (removable) RemoveBadge("Delete ${group.name}", rotated = isArmed) { armed = if (isArmed) null else group.id }
                FitText(group.name, MbType.listItem, Mb.colors.textPrimary, Modifier.weight(1f), maxLines = 2)
                EditPencil("Edit ${group.name}") { onEdit(group.id) }
                DragHandle(handle, "Move ${group.name}")
                if (removable) TrashBox(isArmed, "Trash ${group.name}") { armed = null; onDelete(group.id) }
            }
        }
    }
}

/** Realbyte's group form: the name, and for a group that is not one of the fixed ones its type (Default, credit card, debit card). */
@Composable
fun GroupEditScreen(groupId: String?, onClose: () -> Unit) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    var initial by remember { mutableStateOf<AccountGroupEntity?>(null) }
    var type by remember { mutableStateOf<AccountGroupType?>(null) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(groupId) {
        val g = groupId?.let { c.accounts.getGroup(it) }
        initial = g ?: AccountGroupEntity("", "", AccountGroupType.OTHERS, 0, 0)
        type = g?.type ?: AccountGroupType.OTHERS
    }
    initial?.let { start ->
        val fixed = start.id in KEPT_GROUPS
        NameEditScreen(
            "Account Group", start.name, onClose,
            below = { if (!fixed) GroupTypeChoice(type ?: AccountGroupType.OTHERS) { type = it } },
        ) { name ->
            if (saving) return@NameEditScreen // a double-tapped Save must not write twice
            saving = true
            scope.launch {
                if (groupId == null) c.accounts.addGroup(name, type ?: AccountGroupType.OTHERS)
                else c.accounts.updateGroup(groupId, name, if (fixed) start.type else type ?: start.type)
                withContext(Dispatchers.Main) { onClose() } // navigation must run on the main thread
            }
        }
    }
}

/** Realbyte's three radio rows: "Default" (type Others here), "Account group for credit cards", "Account group for debit cards". */
@Composable
private fun GroupTypeChoice(selected: AccountGroupType, onPick: (AccountGroupType) -> Unit) {
    val options = listOf(
        "Default" to AccountGroupType.OTHERS,
        "Account group for credit cards" to AccountGroupType.CARD,
        "Account group for debit cards" to AccountGroupType.DEBIT_CARD,
    )
    // an existing group of another type (Savings, Loan ...) shows "Default" selected until the user picks a card option
    val current = if (selected == AccountGroupType.CARD || selected == AccountGroupType.DEBIT_CARD) selected else AccountGroupType.OTHERS
    Column(Modifier.padding(start = 15.dp, top = 14.dp)) {
        options.forEach { (label, type) ->
            Row(
                Modifier.heightIn(min = MbDimens.listRow).clickable { onPick(type) }.semantics { this.contentDescription = label },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = current == type, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Mb.colors.accent))
                Text(label, style = MbType.listItem, color = Mb.colors.textBody, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}
