package com.moneybook.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moneybook.data.AccountRepository
import com.moneybook.data.CategoryRepository
import com.moneybook.data.SettingsRepository
import com.moneybook.data.TimeSource
import com.moneybook.data.TxnRepository
import com.moneybook.data.db.TxnRow
import com.moneybook.domain.DateRange
import com.moneybook.domain.SearchFilter
import com.moneybook.domain.SearchTotals
import com.moneybook.domain.search
import com.moneybook.domain.searchTotals
import com.moneybook.shared.model.CategoryType
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.ui.common.specOf
import java.math.RoundingMode
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    /** The date pickers open on it while no period is set. */
    val today: LocalDate,
    val filter: SearchFilter = SearchFilter(),
    val panelOpen: Boolean = false,
    /** The amount fields as typed; [filter] holds their parsed values. */
    val minText: String = "",
    val maxText: String = "",
    val results: List<TxnRow> = emptyList(),
    val totals: SearchTotals = SearchTotals(),
    val currency: CurrencySpec = specOf("USD"),
    /** Id → name of the visible accounts and of the income and expense categories, for the pickers. */
    val accounts: List<Pair<String, String>> = emptyList(),
    val categories: List<Pair<String, String>> = emptyList(),
) {
    fun accountName(): String? = filter.accountId?.let { id -> accounts.firstOrNull { it.first == id }?.second }
    fun categoryName(): String? = filter.categoryId?.let { id -> categories.firstOrNull { it.first == id }?.second }
}

/** "123,5" → 12350 at 2 decimals; blank or invalid → null. */
internal fun parseAmount(text: String, decimals: Int): Long? =
    text.trim().replace(',', '.').toBigDecimalOrNull()?.movePointRight(decimals)?.setScale(0, RoundingMode.HALF_UP)
        ?.let { runCatching { it.longValueExact() }.getOrNull() }

private data class Form(val filter: SearchFilter = SearchFilter(), val panelOpen: Boolean = false, val minText: String = "", val maxText: String = "")

private data class Choices(val accounts: List<Pair<String, String>> = emptyList(), val categories: List<Pair<String, String>> = emptyList())

class SearchViewModel(
    txns: TxnRepository,
    private val accounts: AccountRepository,
    private val categories: CategoryRepository,
    settings: SettingsRepository,
    private val time: TimeSource,
) : ViewModel() {
    private val form = MutableStateFlow(Form())
    private val choices = MutableStateFlow(Choices())

    init {
        viewModelScope.launch {
            choices.value = Choices(
                accounts = accounts.accounts().filter { !it.hidden }.map { it.id to it.name },
                categories = (categories.top(CategoryType.INCOME) + categories.top(CategoryType.EXPENSE)).map { it.id to it.name },
            )
        }
    }

    val state: StateFlow<SearchUiState> = combine(form, choices, txns.observeAll(), settings.settings) { f, c, rows, s ->
        val currency = specOf(s.mainCurrency)
        val filter = f.filter.copy(min = parseAmount(f.minText, currency.decimals), max = parseAmount(f.maxText, currency.decimals))
        val found = search(rows, filter)
        SearchUiState(
            today = time.today(), filter = filter, panelOpen = f.panelOpen, minText = f.minText, maxText = f.maxText,
            results = found, totals = searchTotals(found), currency = currency, accounts = c.accounts, categories = c.categories,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState(today = time.today()))

    private fun filter(change: (SearchFilter) -> SearchFilter) = form.update { it.copy(filter = change(it.filter)) }

    fun setText(text: String) = filter { it.copy(text = text) }
    fun togglePanel() = form.update { it.copy(panelOpen = !it.panelOpen) }
    fun setRange(first: LocalDate, last: LocalDate) = filter {
        val (a, b) = if (last < first) last to first else first to last
        it.copy(range = DateRange(a, b.plusDays(1)))
    }
    fun clearRange() = filter { it.copy(range = null) }
    fun setAccount(id: String?) = filter { it.copy(accountId = id) }
    fun setCategory(id: String?) = filter { it.copy(categoryId = id) }
    fun setMin(text: String) = form.update { it.copy(minText = text) }
    fun setMax(text: String) = form.update { it.copy(maxText = text) }
}
