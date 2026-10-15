package com.moneybook.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moneybook.data.AccountRepository
import com.moneybook.data.CurrencyRepository
import com.moneybook.data.TimeSource
import com.moneybook.domain.AccountsOverview
import com.moneybook.domain.buildAccountsOverview
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.ui.common.specOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AccountsUiState(
    val currency: CurrencySpec = specOf("USD"),
    val overview: AccountsOverview = AccountsOverview(0, 0, emptyList()),
    /** Same totals, but the lists include hidden accounts (Show/Hide mode). */
    val withHidden: AccountsOverview = AccountsOverview(0, 0, emptyList()),
)

class AccountsViewModel(private val accounts: AccountRepository, currencies: CurrencyRepository, private val time: TimeSource) : ViewModel() {
    val state: StateFlow<AccountsUiState> = combine(accounts.observeBalances(), accounts.observeCardCharges(), currencies.state) { rows, charges, cs ->
        val today = time.today()
        val fx = cs.fx
        AccountsUiState(
            cs.mainSpec,
            buildAccountsOverview(rows, charges = charges, today = today, toMain = fx::toMain),
            buildAccountsOverview(rows, includeHidden = true, charges = charges, today = today, toMain = fx::toMain),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountsUiState())

    fun setHidden(id: String, hidden: Boolean) {
        viewModelScope.launch { accounts.setHidden(id, hidden) }
    }

    fun delete(id: String) {
        viewModelScope.launch { accounts.delete(id) }
    }

    fun reorder(ids: List<String>) {
        viewModelScope.launch { accounts.reorder(ids) }
    }
}
