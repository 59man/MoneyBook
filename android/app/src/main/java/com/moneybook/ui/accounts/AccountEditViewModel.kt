package com.moneybook.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moneybook.data.AccountRepository
import com.moneybook.data.CurrencyRepository
import com.moneybook.data.CurrencyState
import com.moneybook.data.SettingsRepository
import com.moneybook.data.TimeSource
import com.moneybook.data.db.AccountEntity
import com.moneybook.data.db.AccountGroupEntity
import com.moneybook.domain.AmountInput
import com.moneybook.domain.CardStatement
import com.moneybook.domain.DateRange
import com.moneybook.domain.DayInput
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.ui.common.specOf
import com.moneybook.ui.edit.KeypadKey
import com.moneybook.ui.edit.applyTo
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AccountEditState(
    val loaded: Boolean = false,
    val isNew: Boolean = true,
    val groups: List<AccountGroupEntity> = emptyList(),
    val groupId: String? = null,
    val name: String = "",
    /** Current balance as typed on the keypad. */
    val amount: AmountInput = AmountInput(),
    val description: String = "",
    val includeInTotals: Boolean = true,
    val hidden: Boolean = false,
    val currency: CurrencySpec = specOf("USD"),
    /** The Currency chips: main first, then the sub-currencies. */
    val currencies: List<CurrencySpec> = emptyList(),
    /** An existing account's chosen currency, waiting for the [Notice]. */
    val pendingCurrency: String? = null,
    val showGroupPicker: Boolean = false,
    val panel: AccountPanel = AccountPanel.NONE,
    /** Card accounts (group type CARD): Settlement Date, Payment Date and the Pay account. */
    val statementDay: DayInput = DayInput(),
    val paymentDay: DayInput = DayInput(),
    val paymentAccountId: String? = null,
    /** Accounts Payment can name: the visible ones except this account. */
    val paymentChoices: List<AccountEntity> = emptyList(),
    val today: LocalDate = LocalDate.of(2000, 1, 1),
) {
    val groupName: String? get() = groups.firstOrNull { it.id == groupId }?.name
    val isCard: Boolean get() = groups.firstOrNull { it.id == groupId }?.type == AccountGroupType.CARD
    val keypadOpen: Boolean get() = panel == AccountPanel.AMOUNT
    val paymentAccountName: String? get() = paymentChoices.firstOrNull { it.id == paymentAccountId }?.name

    /** Account Info's preview box, for today; null for other groups. */
    val preview: CardPreview? get() {
        if (!isCard) return null
        val outstanding = CardStatement.containing(today, statementDay.value)
        val payable = CardStatement.previous(outstanding, statementDay.value)
        return CardPreview(previewLine(payable, paymentDay.value), previewLine(outstanding, paymentDay.value))
    }
}

enum class AccountPanel { NONE, AMOUNT, SETTLEMENT, PAYMENT_DAY, PAYMENT_ACCOUNT }

/** "MM/dd  ~  MM/dd (Pay: MM/dd)" for Balance Payable (the previous period) and Outst. Balance (today's). */
data class CardPreview(val payable: String, val outstanding: String)

private val MONTH_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd")

private fun previewLine(p: DateRange, payment: Int): String =
    "${MONTH_DAY.format(p.start)}  ~  ${MONTH_DAY.format(p.lastDay)} (Pay: ${MONTH_DAY.format(CardStatement.paymentDate(p, payment))})"

sealed interface AccountEditEvent {
    data class Error(val message: String) : AccountEditEvent
    data object Saved : AccountEditEvent
    data object Deleted : AccountEditEvent
}

class AccountEditViewModel(
    private val accountId: String?,
    private val accounts: AccountRepository,
    private val settings: SettingsRepository,
    private val time: TimeSource,
    private val currencies: CurrencyRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(AccountEditState())
    val state: StateFlow<AccountEditState> = _state.asStateFlow()

    private val _events = Channel<AccountEditEvent>(Channel.BUFFERED)
    val events: Flow<AccountEditEvent> = _events.receiveAsFlow()

    private var original: AccountEntity? = null

    /** Fixed per form, so a double-tapped Save creates the account once. */
    private val newId = UUID.randomUUID().toString()
    private var currentBalance = 0L

    /** The user confirmed a new currency for an existing account; Save converts it. */
    private var currencyChanged = false
    private var currencyState = CurrencyState("USD", emptyList())

    init {
        viewModelScope.launch {
            currencies.state.collect { cs ->
                currencyState = cs
                _state.update { it.copy(currencies = cs.stripSpecs) }
            }
        }
        viewModelScope.launch {
            val groups = accounts.groups()
            val existing = accountId?.let { accounts.get(it) }?.takeIf { !it.deleted }
            original = existing
            currentBalance = existing?.let { accounts.balanceOf(it.id) } ?: 0
            currencyState = currencies.current()
            val spec = currencyState.spec(existing?.currency ?: currencyState.main)
            val choices = accounts.accounts().filter { !it.hidden && it.id != (existing?.id ?: newId) }
            val base = _state.value.copy(loaded = true, groups = groups, currency = spec, paymentChoices = choices, today = time.today())
            _state.value = if (existing == null) {
                base.copy(amount = AmountInput(decimals = spec.decimals), showGroupPicker = true)
            } else {
                base.copy(
                    isNew = false, groupId = existing.groupId, name = existing.name,
                    amount = AmountInput.fromMinor(currentBalance, spec.decimals), description = existing.description,
                    includeInTotals = existing.includeInTotals, hidden = existing.hidden,
                    statementDay = DayInput(existing.statementDay ?: 1), paymentDay = DayInput(existing.paymentDay ?: 1),
                    paymentAccountId = existing.paymentAccountId,
                )
            }
        }
    }

    /** A new account takes the chip at once; an existing one asks first (Realbyte's [Notice]). */
    fun pickCurrency(code: String) {
        val s = _state.value
        if (code == s.currency.code) return
        if (original == null) switchCurrency(code) else _state.update { it.copy(pendingCurrency = code) }
    }

    fun confirmCurrency() {
        val code = _state.value.pendingCurrency ?: return
        currencyChanged = true
        switchCurrency(code)
    }

    fun dismissCurrency() = _state.update { it.copy(pendingCurrency = null) }

    /** The typed balance follows, converted at the current rate. */
    private fun switchCurrency(code: String) = _state.update { s ->
        val spec = currencyState.spec(code)
        val converted = currencyState.fx.convert(s.amount.minorUnits() ?: 0L, s.currency.code, code)
        s.copy(currency = spec, amount = AmountInput.fromMinor(converted, spec.decimals), pendingCurrency = null)
    }

    fun pickGroup(id: String) = _state.update { it.copy(groupId = id, showGroupPicker = false) }
    fun openGroupPicker() = _state.update { it.copy(showGroupPicker = true, panel = AccountPanel.NONE) }
    fun dismissGroupPicker() = _state.update { it.copy(showGroupPicker = false) }
    fun setName(value: String) = _state.update { it.copy(name = value) }
    fun setDescription(value: String) = _state.update { it.copy(description = value) }
    fun setIncludeInTotals(value: Boolean) = _state.update { it.copy(includeInTotals = value) }
    fun setHidden(value: Boolean) = _state.update { it.copy(hidden = value) }
    fun openKeypad() = _state.update { it.copy(panel = AccountPanel.AMOUNT) }
    fun closeKeypad() = closePanel()
    fun closePanel() = _state.update { it.copy(panel = AccountPanel.NONE) }

    /** Opening a day keypad makes its next digit replace the day. */
    fun openSettlement() = _state.update { it.copy(panel = AccountPanel.SETTLEMENT, statementDay = it.statementDay.copy(fresh = true)) }
    fun openPaymentDay() = _state.update { it.copy(panel = AccountPanel.PAYMENT_DAY, paymentDay = it.paymentDay.copy(fresh = true)) }
    fun openPaymentAccount() = _state.update { it.copy(panel = AccountPanel.PAYMENT_ACCOUNT) }
    fun pickPaymentAccount(id: String) = _state.update { it.copy(paymentAccountId = id, panel = AccountPanel.NONE) }

    fun onKey(key: KeypadKey) = _state.update { s ->
        when (s.panel) {
            AccountPanel.SETTLEMENT -> s.copy(statementDay = key.applyTo(s.statementDay))
            AccountPanel.PAYMENT_DAY -> s.copy(paymentDay = key.applyTo(s.paymentDay))
            else -> s.copy(amount = key.applyTo(s.amount))
        }
    }

    private fun KeypadKey.applyTo(day: DayInput): DayInput = when (this) {
        is KeypadKey.Digit -> char.digitToIntOrNull()?.let(day::digit) ?: day
        KeypadKey.Backspace -> day.backspace()
        KeypadKey.Clear -> day.clear()
        KeypadKey.Dot, KeypadKey.Minus -> day
    }

    fun save() {
        val s = _state.value
        viewModelScope.launch {
            val groupId = s.groupId ?: return@launch _events.send(AccountEditEvent.Error("Please select a group."))
            if (s.name.isBlank()) return@launch _events.send(AccountEditEvent.Error("Please enter a name."))
            val entered = s.amount.minorUnits() ?: 0L
            if (currencyChanged && original != null) {
                currencies.changeAccountCurrency(original!!.id, s.currency.code)
                original = accounts.get(original!!.id)
                currentBalance = accounts.balanceOf(original!!.id)
                currencyChanged = false
            }
            val o = original
            val entity = if (o == null) {
                AccountEntity(
                    id = newId, groupId = groupId, name = s.name.trim(), currency = s.currency.code,
                    initialBalance = entered, description = s.description.trim(), includeInTotals = s.includeInTotals,
                    hidden = s.hidden, sortOrder = accounts.nextSortOrder(groupId), updatedAt = 0,
                ).withCardFields(s)
            } else {
                o.copy(
                    groupId = groupId,
                    name = s.name.trim(),
                    initialBalance = o.initialBalance + (entered - currentBalance),
                    description = s.description.trim(),
                    includeInTotals = s.includeInTotals,
                    hidden = s.hidden,
                    sortOrder = if (o.groupId == groupId) o.sortOrder else accounts.nextSortOrder(groupId),
                ).withCardFields(s)
            }
            accounts.save(entity)
            _events.send(AccountEditEvent.Saved)
        }
    }

    /** A card keeps what the form shows; another group keeps the stored card fields untouched. */
    private fun AccountEntity.withCardFields(s: AccountEditState): AccountEntity =
        if (!s.isCard) this else copy(statementDay = s.statementDay.value, paymentDay = s.paymentDay.value, paymentAccountId = s.paymentAccountId)

    fun delete() {
        val id = original?.id ?: return
        viewModelScope.launch {
            accounts.delete(id)
            _events.send(AccountEditEvent.Deleted)
        }
    }
}
