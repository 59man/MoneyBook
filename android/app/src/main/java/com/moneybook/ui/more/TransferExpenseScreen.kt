package com.moneybook.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moneybook.R
import com.moneybook.data.AccountRepository
import com.moneybook.domain.AccountGroupItem
import com.moneybook.domain.AccountItem
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.ui.accounts.GroupHeader
import com.moneybook.ui.common.EmptyState
import com.moneybook.ui.common.FitText
import com.moneybook.ui.common.Hairline
import com.moneybook.ui.common.MbIcon
import com.moneybook.ui.common.MbTopBar
import com.moneybook.ui.common.TitleText
import com.moneybook.ui.common.appContainer
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The listed accounts under their groups and which of them are ticked. */
data class TransferExpenseState(val groups: List<AccountGroupItem>, val ticked: Set<String>)

class TransferExpenseViewModel(private val accounts: AccountRepository) : ViewModel() {
    val state: StateFlow<TransferExpenseState?> =
        combine(accounts.observeTransferExpenseAccounts(), accounts.observeGroups()) { listed, groups ->
            val byGroup = listed.groupBy { it.groupId }
            TransferExpenseState(
                groups.filter { it.id in byGroup }.map { g ->
                    AccountGroupItem(g.id, g.name, g.type, 0, byGroup.getValue(g.id).map { AccountItem(it.id, it.name, 0, it.currency, it.hidden) })
                },
                listed.filter { it.transferExpense }.map { it.id }.toSet(),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun toggle(id: String, on: Boolean) {
        viewModelScope.launch { accounts.setTransferExpense(id, on) }
    }
}

/** Realbyte's description of the setting (config_setting_list7_send_out_desc and transfer_income, joined by a space). */
private const val DESCRIPTION = "Amount transferred from the regular account to the selected account is shown as an expense on the Trans tab and " +
    "the Stats tab. This is a feature that displays the transferred amount as an expense to the accounts that are difficult to liquidate, " +
    "such as savings, investments, loans, and insurance. This is not applicable to cash/bank/card/check card account groups. " +
    "The transferred amount from the selected account to the regular account will be displayed as an income."

@Composable
fun TransferExpenseScreen(onBack: () -> Unit) {
    val accounts = appContainer().accounts
    val vm: TransferExpenseViewModel = viewModel { TransferExpenseViewModel(accounts) }
    val state by vm.state.collectAsStateWithLifecycle()
    TransferExpenseContent(state, onBack, vm::toggle)
}

@Composable
fun TransferExpenseContent(state: TransferExpenseState?, onBack: () -> Unit, onToggle: (String, Boolean) -> Unit) {
    Column(Modifier.fillMaxSize().background(Mb.colors.background).systemBarsPadding()) {
        MbTopBar(onBack = onBack, title = { TitleText("Transfer-Expense setting") })
        Hairline(px = 2)
        if (state != null) LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            // The description scrolls with the list: at a large font it would otherwise fill the screen.
            item(key = "description") {
                Column {
                    Text(
                        DESCRIPTION, style = MbType.budgetHelp, color = Mb.colors.textSecondary,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                    )
                    Hairline()
                }
            }
            if (state.groups.isEmpty()) item(key = "empty") { EmptyState() }
            state.groups.forEach { group ->
                item(key = "g-${group.id}") { GroupHeader(group, CurrencySpec.of("USD"), showBalances = false) }
                    items(group.accounts, key = { it.id }) { account ->
                        val on = account.id in state.ticked
                        Column(Modifier.background(Mb.colors.surface).clickable { onToggle(account.id, !on) }) {
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = MbDimens.listRow).padding(start = 20.dp, end = MbDimens.sidePadding),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                FitText(account.name, MbType.listItem, Mb.colors.textBody, Modifier.weight(1f), maxLines = 3)
                                if (on) MbIcon(R.drawable.ic_check, "Ticked", tint = Mb.colors.textBody)
                            }
                            Hairline()
                        }
                    }
            }
        }
    }
}
