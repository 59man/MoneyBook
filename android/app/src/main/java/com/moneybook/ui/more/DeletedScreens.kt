package com.moneybook.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moneybook.data.AccountRepository
import com.moneybook.data.db.DeletedGroupRow
import com.moneybook.domain.AccountGroupItem
import com.moneybook.domain.AccountItem
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.ui.accounts.AccountGroupList
import com.moneybook.ui.accounts.AccountsActions
import com.moneybook.ui.accounts.AccountsMode
import com.moneybook.ui.common.ConfirmDialog
import com.moneybook.ui.common.EmptyState
import com.moneybook.ui.common.FitText
import com.moneybook.ui.common.Hairline
import com.moneybook.ui.common.MbTopBar
import com.moneybook.ui.common.RemoveBadge
import com.moneybook.ui.common.TitleText
import com.moneybook.ui.common.TrashBox
import com.moneybook.ui.common.appContainer
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** More → Accounts → Deleted accounts: deleted accounts of normal groups, grouped; null until the first read. */
class DeletedAccountsViewModel(private val accounts: AccountRepository) : ViewModel() {
    val groups: StateFlow<List<AccountGroupItem>?> =
        combine(accounts.observeDeletedAccounts(), accounts.observeGroups()) { deleted, groups ->
            val byGroup = deleted.groupBy { it.groupId }
            groups.filter { it.id in byGroup }.map { g ->
                AccountGroupItem(g.id, g.name, g.type, 0, byGroup.getValue(g.id).map { AccountItem(it.id, it.name, 0, it.currency, it.hidden) })
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun purge(id: String) {
        viewModelScope.launch { accounts.purge(id) }
    }
}

@Composable
fun DeletedAccountsScreen(onBack: () -> Unit, onOpenAccount: (String) -> Unit) {
    val accounts = appContainer().accounts
    val vm: DeletedAccountsViewModel = viewModel { DeletedAccountsViewModel(accounts) }
    val groups by vm.groups.collectAsStateWithLifecycle()
    DeletedAccountsContent(groups, onBack, onOpenAccount, vm::purge)
}

@Composable
fun DeletedAccountsContent(groups: List<AccountGroupItem>?, onBack: () -> Unit, onOpenAccount: (String) -> Unit, onPurge: (String) -> Unit) {
    Column(Modifier.fillMaxSize().background(Mb.colors.background).systemBarsPadding()) {
        MbTopBar(onBack = onBack, title = { TitleText("Deleted accounts") })
        Hairline(px = 2)
        when {
            groups == null -> Unit
            groups.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth().background(Mb.colors.surface)) { EmptyState() }
            else -> AccountGroupList(
                groups, AccountsMode.DELETED, CurrencySpec.of("USD"), AccountsActions(onDelete = onPurge), onOpenAccount,
                showBalances = false, Modifier.weight(1f),
            )
        }
    }
}

/** More → Accounts → Deleted account group. */
class DeletedGroupsViewModel(private val accounts: AccountRepository) : ViewModel() {
    val groups: StateFlow<List<DeletedGroupRow>?> =
        accounts.observeDeletedGroups().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun restore(id: String) {
        viewModelScope.launch { accounts.restoreGroup(id) }
    }

    fun purge(id: String) {
        viewModelScope.launch { accounts.purgeGroup(id) }
    }
}

@Composable
fun DeletedGroupsScreen(onBack: () -> Unit) {
    val accounts = appContainer().accounts
    val vm: DeletedGroupsViewModel = viewModel { DeletedGroupsViewModel(accounts) }
    val groups by vm.groups.collectAsStateWithLifecycle()
    DeletedGroupsContent(groups, onBack, vm::restore, vm::purge)
}

@Composable
fun DeletedGroupsContent(groups: List<DeletedGroupRow>?, onBack: () -> Unit, onRestore: (String) -> Unit, onPurge: (String) -> Unit) {
    var armed by remember { mutableStateOf<String?>(null) }
    var restoring by remember { mutableStateOf<DeletedGroupRow?>(null) }
    Column(Modifier.fillMaxSize().background(Mb.colors.background).systemBarsPadding()) {
        MbTopBar(onBack = onBack, title = { TitleText("Deleted account group") })
        Hairline(px = 2)
        when {
            groups == null -> Unit
            groups.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth().background(Mb.colors.surface)) { EmptyState() }
            else -> LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(groups, key = { it.id }) { g ->
                    val isArmed = armed == g.id
                    Column(Modifier.background(Mb.colors.surface).clickable { restoring = g }) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = MbDimens.listRow).height(IntrinsicSize.Min),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RemoveBadge("Delete ${g.name}", rotated = isArmed) { armed = if (isArmed) null else g.id }
                            FitText(
                                if (g.accountCount > 0) "${g.name} (${g.accountCount})" else g.name,
                                MbType.listItem, Mb.colors.textBody, Modifier.weight(1f), maxLines = 3,
                            )
                            if (!isArmed) FitText("Restore", MbType.listItem, Mb.colors.accent, Modifier.padding(end = MbDimens.sidePadding))
                            TrashBox(isArmed, "Trash ${g.name}") { armed = null; onPurge(g.id) }
                        }
                        Hairline()
                    }
                }
            }
        }
    }
    restoring?.let { g ->
        ConfirmDialog("Restore", "Do you want to restore?", "YES", "NO", onConfirm = { onRestore(g.id) }, onDismiss = { restoring = null })
    }
}
