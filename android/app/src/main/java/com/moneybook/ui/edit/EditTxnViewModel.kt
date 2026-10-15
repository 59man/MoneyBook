package com.moneybook.ui.edit

import kotlinx.coroutines.flow.first
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moneybook.data.AccountRepository
import com.moneybook.data.BookmarkRepository
import com.moneybook.data.CategoryRepository
import com.moneybook.data.CopyDateBy
import com.moneybook.data.CurrencyRepository
import com.moneybook.data.CurrencyState
import com.moneybook.data.InputOrder
import com.moneybook.data.RepeatRepository
import com.moneybook.data.SettingsRepository
import com.moneybook.data.TimeSource
import com.moneybook.data.TxnRepository
import com.moneybook.data.db.AccountEntity
import com.moneybook.data.db.BookmarkEntity
import com.moneybook.data.db.CategoryEntity
import com.moneybook.data.db.DbTime
import com.moneybook.data.db.RepeatRuleEntity
import com.moneybook.data.db.TxnEntity
import com.moneybook.domain.AmountInput
import com.moneybook.domain.Formats
import com.moneybook.domain.RepeatSchedule
import com.moneybook.domain.StoredAmounts
import com.moneybook.domain.entryAmounts
import com.moneybook.domain.splitInstallments
import com.moneybook.shared.model.CategoryType
import com.moneybook.shared.model.RepeatFrequency
import com.moneybook.shared.model.TxnType
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.shared.money.Fx
import com.moneybook.ui.common.specOf
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Realbyte's limit: the camera icon goes away at three photos. */
const val MAX_PHOTOS = 3

enum class Panel { NONE, AMOUNT, CATEGORY, ACCOUNT, TO_ACCOUNT, FEE }

sealed interface KeypadKey {
    data class Digit(val char: Char) : KeypadKey
    data object Dot : KeypadKey
    data object Backspace : KeypadKey
    data object Minus : KeypadKey
    /** The day keypad's C (Account Info → Settlement Date / Payment Date). */
    data object Clear : KeypadKey
}

fun KeypadKey.applyTo(input: AmountInput): AmountInput = when (this) {
    is KeypadKey.Digit -> input.digit(char)
    KeypadKey.Dot -> input.dot()
    KeypadKey.Backspace -> input.backspace()
    KeypadKey.Clear -> input.copy(text = "")
    KeypadKey.Minus -> input.toggleSign()
}

sealed interface EditEvent {
    data class Error(val message: String) : EditEvent
    data object Closed : EditEvent
    data class Info(val message: String) : EditEvent
}

/** The rate sheet's two fields: the main-currency amount and the exchange rate. */
enum class RateField { MAIN, RATE }

/**
 * Amount → "Exchange rate: …" (Realbyte's rate sheet): the entry's main amount and rate; editing one recomputes the
 * other. [fresh]: the next digit replaces the focused field.
 */
data class RateSheet(val main: AmountInput, val rate: String, val focus: RateField = RateField.RATE, val fresh: Boolean = true)

/** A new entry's presets: + on account detail (the account), Pay on a card (both accounts, the amount and "Payment"). */
data class TxnPreset(val accountId: String? = null, val toAccountId: String? = null, val amount: Long? = null, val note: String? = null)

/** Rep/Inst.: a repeat frequency, or a monthly installment plan of [Installments.months] parts. */
sealed interface RepeatChoice {
    val label: String

    data class Repeat(val frequency: RepeatFrequency) : RepeatChoice {
        override val label: String get() = frequency.label
    }

    data class Installments(val months: Int) : RepeatChoice {
        override val label: String get() = "$months months"
    }
}

data class EditTxnState(
    val loaded: Boolean = false,
    /** Null while creating a new entry (also a Copy). */
    val editingId: String? = null,
    val type: TxnType = TxnType.EXPENSE,
    val date: LocalDate,
    val time: LocalTime,
    val amount: AmountInput = AmountInput(),
    val categoryId: String? = null,
    /** Income/expense account, or the "From" account of a transfer. */
    val accountId: String? = null,
    val toAccountId: String? = null,
    val note: String = "",
    val description: String = "",
    /** The entry's photo file names in order; Save writes this list, so a removed one is gone only then. */
    val photos: List<String> = emptyList(),
    /** The part of [photos] already stored with the entry: only those can be downloaded from the viewer. */
    val savedPhotos: Set<String> = emptySet(),
    val suggestions: List<String> = emptyList(),
    /** Rep/Inst. on a new entry; saving creates the rule. */
    val repeat: RepeatChoice? = null,
    /** Configuration → Time Input: whether the form shows the time row. */
    val timeShown: Boolean = true,
    /** Configuration → Input order: From Account asks for the account first. */
    val inputOrder: InputOrder = InputOrder.AMOUNT,
    /** Rule mode (Repeat Setting → a rule): the rule being edited, and the next date it loaded with. */
    val ruleId: String? = null,
    val ruleNext: LocalDate? = null,
    val panel: Panel = Panel.NONE,
    val dirty: Boolean = false,
    /** The currency the amount is entered in. */
    val currency: CurrencySpec = specOf("USD"),
    val mainCurrency: CurrencySpec = specOf("USD"),
    /** The keypad's currency strip: main first, then the sub-currencies (shown when there are any). */
    val strip: List<CurrencySpec> = emptyList(),
    val fx: Fx = Fx("USD", emptyMap()),
    /** This entry's own rate (the rate sheet); null = the sub-currency's current rate. */
    val rateOverride: BigDecimal? = null,
    val rateSheet: RateSheet? = null,
    /** A transfer's Fees row (null = none); saved as a separate "Fees" expense from the From account. */
    val fee: AmountInput? = null,
    val feeCurrency: CurrencySpec = specOf("USD"),
    val incomeCategories: List<CategoryEntity> = emptyList(),
    val expenseCategories: List<CategoryEntity> = emptyList(),
    /** Id -> display name for every category: with Subcategory ON a sub reads "Parent/Sub", with OFF just its parent. */
    val categoryLabels: Map<String, String> = emptyMap(),
    /** Configuration → Subcategory: the category picker has two panes. */
    val subcategory: Boolean = false,
    /** The live, visible subcategories (both types) for the picker's right pane. */
    val subcategories: List<CategoryEntity> = emptyList(),
    val accounts: List<AccountEntity> = emptyList(),
) {
    val isNew: Boolean get() = editingId == null
    val isRule: Boolean get() = ruleId != null
    val categories: List<CategoryEntity> get() = if (type == TxnType.INCOME) incomeCategories else expenseCategories
    val canAddPhoto: Boolean get() = !isRule && photos.size < MAX_PHOTOS
    val categoryName: String? get() = categoryId?.let { categoryLabels[it] }
    fun accountName(id: String?): String? = accounts.firstOrNull { it.id == id }?.name

    /** The main-currency amount shown right of a sub-currency amount; null for the main currency or no amount. */
    val shownMain: Long? get() {
        if (currency.code == mainCurrency.code) return null
        val minor = amount.minorUnits() ?: return null
        return rateOverride?.let { Fx.mainAt(minor, currency.code, it, mainCurrency.code) } ?: fx.toMain(minor, currency.code)
    }

    /** "Exchange rate: 1.1" under the amount. */
    val shownRate: String? get() = if (currency.code == mainCurrency.code) null else Fx.formatRate(rateOverride ?: fx.rate(currency.code))
}

class EditTxnViewModel(
    txnId: String?,
    initialDate: LocalDate?,
    private val txns: TxnRepository,
    private val accounts: AccountRepository,
    private val categories: CategoryRepository,
    private val settings: SettingsRepository,
    private val time: TimeSource,
    private val bookmarks: BookmarkRepository,
    private val repeats: RepeatRepository,
    private val currencies: CurrencyRepository,
    bookmarkId: String? = null,
    ruleId: String? = null,
    initialType: TxnType = TxnType.EXPENSE,
    private val preset: TxnPreset? = null,
    /** Edit → Copy: the entry to load as a new one (a new id, dated per Copy's choice). */
    private val copyOf: String? = null,
    /** The Copy dialog's choice, carried by the route: the stored setting may not have landed yet. Null reads the setting. */
    private val copyBy: CopyDateBy? = null,
) : ViewModel() {
private val _state = MutableStateFlow(EditTxnState(date = initialDate ?: time.today(), time = nowMinute(), type = initialType))
    val state: StateFlow<EditTxnState> = _state.asStateFlow()

    private val _events = Channel<EditEvent>(Channel.BUFFERED)
    val events: Flow<EditEvent> = _events.receiveAsFlow()

    /** The stored row being edited; its non-form fields (fee, repeat, installment) survive a save. */
    private var original: TxnEntity? = null

    /** Id of the entry being created. Fixed per form, so a double-tapped Save writes the same row twice. */
    private var newId = UUID.randomUUID().toString()

    private var suggestJob: Job? = null

    /** The Copy dialog's choice, stored the moment a row is tapped. */
    val copyDateBy: StateFlow<CopyDateBy> =
        settings.settings.map { it.copyDateBy }.stateIn(viewModelScope, SharingStarted.Eagerly, CopyDateBy.TODAY)

    fun setCopyDateBy(by: CopyDateBy) {
        viewModelScope.launch { settings.replace(settings.current().copy(copyDateBy = by)) }
    }

    init {
        viewModelScope.launch {
            load(txnId, bookmarkId, ruleId)
            // A sub-currency added through the globe shows up on return.
            currencies.state.collect { cs -> _state.update { it.copy(mainCurrency = cs.mainSpec, strip = cs.stripSpecs, fx = cs.fx) } }
        }
    }

    private fun nowMinute(): LocalTime = time.now().toLocalTime().withSecond(0).withNano(0)

    private suspend fun load(txnId: String?, bookmarkId: String?, ruleId: String?) {
        val appSettings = settings.current()
        val cs = currencies.current()
        val mainCurrency = cs.mainSpec
        val source = (txnId ?: copyOf)?.let { txns.get(it) }?.takeIf { !it.deleted }
        // A copy is a new entry: it never sets [original], so Save writes a new row (and a new fee).
        val existing = source?.takeIf { txnId != null }
        val copied = source?.takeIf { txnId == null }
        original = existing
        val existingFee = source?.takeIf { it.type == TxnType.TRANSFER }?.let { txns.get(feeId(it.id)) }?.takeIf { !it.deleted }
        val existingPhotos = existing?.let { txns.photos(it.id) }.orEmpty()
        val all = categories.allRows()
        val byId = all.associateBy { it.id }
        val subOn = appSettings.subcategory
        val labels = all.associate { c ->
            c.id to (c.parentId?.let { p -> if (subOn) "${byId[p]?.name}/${c.name}" else byId[p]?.name.orEmpty() } ?: c.name)
        }
        // An entry of a deleted account stays editable: its own accounts are listed whatever their state.
        val own = listOfNotNull(existing?.accountId, existing?.toAccountId).distinct()
        val listed = accounts.accounts().filter { !it.hidden || it.id in own }
        val usable = listed + own.filter { id -> listed.none { it.id == id } }.mapNotNull { accounts.get(it) }
        val income = categories.top(CategoryType.INCOME)
        val expense = categories.top(CategoryType.EXPENSE)
        val bookmark = if (existing == null) bookmarkId?.let { bookmarks.get(it) } else null
        val bookmarkCategory = bookmark?.categoryId?.let { categories.get(it) }?.id
        val copiedCategory = copied?.categoryId?.let { categories.get(it) }?.id
        val rule = if (existing == null) ruleId?.let { repeats.get(it) } else null
        val ruleCategory = rule?.categoryId?.let { categories.get(it) }?.id
        _state.update { s ->
            val base = s.copy(
                loaded = true, currency = mainCurrency, mainCurrency = mainCurrency, strip = cs.stripSpecs, fx = cs.fx, incomeCategories = income, expenseCategories = expense,
                categoryLabels = labels, accounts = usable,
                subcategory = subOn, subcategories = all.filter { it.parentId != null && !it.deleted && !it.hidden }.sortedWith(compareBy({ it.sortOrder }, { it.name })),
                timeShown = appSettings.timeInput.inForm, inputOrder = appSettings.inputOrder,
            )
            if (existing == null) {
                val fresh = base.copy(amount = AmountInput.of(0, mainCurrency), panel = firstPanel(appSettings.inputOrder))
                when {
                    copied != null -> fromCopy(fresh, copied, copyBy ?: appSettings.copyDateBy, copiedCategory, usable.map { it.id }.toSet(), cs, existingFee)
                    rule != null -> fromRule(fresh, rule, ruleCategory, usable.map { it.id }.toSet(), cs.spec(rule.currency))
                    bookmark != null -> fromBookmark(fresh, bookmark, bookmarkCategory, usable.map { it.id }.toSet(), cs.spec(bookmark.currency))
                    preset != null -> applyPreset(fresh, preset, usable.map { it.id }.toSet(), mainCurrency)
                    else -> fresh
                }
            } else {
                val dateTime = DbTime.parse(existing.dateTime)
                val spec = cs.spec(existing.currency)
                base.copy(
                    editingId = existing.id, type = existing.type, date = dateTime.toLocalDate(), time = dateTime.toLocalTime(),
                    amount = AmountInput.of(existing.amount, spec), currency = spec,
                    rateOverride = if (existing.currency == cs.main) null else Fx.rateOf(existing.amount, existing.currency, existing.amountMain, cs.main),
                    categoryId = existing.categoryId, accountId = existing.accountId, toAccountId = existing.toAccountId,
                    note = existing.note, description = existing.description, photos = existingPhotos, savedPhotos = existingPhotos.toSet(),
                    fee = existingFee?.let { AmountInput.of(it.amount, cs.spec(it.currency)) },
                    feeCurrency = existingFee?.let { cs.spec(it.currency) } ?: mainCurrency,
                )
            }
        }
    }

    /**
     * Edit → Copy: [c]'s type, amounts, currency, rate, accounts, category, note, description and fee as a new entry. Today and now,
     * or its own date and time. No photos, installment or repeat rule (they are not form fields). Hidden or deleted accounts stay empty.
     */
    private fun fromCopy(
        fresh: EditTxnState, c: TxnEntity, by: CopyDateBy, categoryId: String?, usable: Set<String>, cs: CurrencyState, fee: TxnEntity?,
    ): EditTxnState {
        val at = DbTime.parse(c.dateTime)
        val spec = cs.spec(c.currency)
        return fresh.copy(
            type = c.type,
            date = if (by == CopyDateBy.TODAY) time.today() else at.toLocalDate(),
            time = if (by == CopyDateBy.TODAY) nowMinute() else at.toLocalTime(),
            amount = AmountInput.of(c.amount, spec), currency = spec,
            rateOverride = if (c.currency == cs.main) null else Fx.rateOf(c.amount, c.currency, c.amountMain, cs.main),
            categoryId = categoryId, accountId = c.accountId.takeIf { it in usable }, toAccountId = c.toAccountId?.takeIf { it in usable },
            note = c.note, description = c.description,
            fee = fee?.let { AmountInput.of(it.amount, cs.spec(it.currency)) },
            feeCurrency = fee?.let { cs.spec(it.currency) } ?: cs.mainSpec,
            panel = Panel.NONE, dirty = true,
        )
    }

    /** Accounts that are deleted or hidden are left empty. Pay arrives filled in; + on an account still asks for the amount. */
    private fun applyPreset(s: EditTxnState, p: TxnPreset, usable: Set<String>, spec: CurrencySpec): EditTxnState {
        val account = p.accountId?.takeIf { it in usable }
        return s.copy(
            accountId = account,
            toAccountId = p.toAccountId?.takeIf { it in usable },
            amount = p.amount?.let { AmountInput.of(it, spec) } ?: s.amount,
            note = p.note ?: s.note,
            panel = when {
                p.amount != null -> Panel.NONE
                account != null -> Panel.AMOUNT
                else -> s.panel
            },
        )
    }

    private fun edit(change: (EditTxnState) -> EditTxnState) = _state.update { change(it).copy(dirty = true) }

    fun setType(type: TxnType) = edit { s ->
        if (s.type == type) s else s.copy(
            type = type,
            categoryId = null,
            toAccountId = if (type == TxnType.TRANSFER) s.toAccountId else null,
            fee = if (type == TxnType.TRANSFER) s.fee else null,
            panel = Panel.NONE,
            // Installment plans are for expenses only.
            repeat = if (type != TxnType.EXPENSE && s.repeat is RepeatChoice.Installments) null else s.repeat,
        )
    }

    fun setRepeat(choice: RepeatChoice?) = edit { it.copy(repeat = choice) }

    fun setDate(date: LocalDate) = edit { it.copy(date = date) }
    fun setTime(t: LocalTime) = edit { it.copy(time = t) }
    fun openPanel(panel: Panel) = _state.update { it.copy(panel = panel) }
    fun closePanel() = _state.update { it.copy(panel = Panel.NONE) }
    fun onKey(key: KeypadKey) = edit {
        if (it.panel == Panel.FEE && it.fee != null) it.copy(fee = key.applyTo(it.fee))
        else it.copy(amount = key.applyTo(it.amount))
    }

    /** Transfer → Fees: adds the Fees row in the main currency and opens its keypad. */
    fun addFee() = edit { it.copy(fee = AmountInput.of(0, it.mainCurrency), feeCurrency = it.mainCurrency, panel = Panel.FEE) }

    /** ✕ on the Fees row: Save tombstones the fee entry. */
    fun removeFee() = edit { it.copy(fee = null, panel = if (it.panel == Panel.FEE) Panel.NONE else it.panel) }

    fun pickFeeCurrency(code: String) = edit { s ->
        val fee = s.fee
        if (fee == null || code == s.feeCurrency.code) s else {
            val spec = s.strip.firstOrNull { it.code == code } ?: specOf(code)
            s.copy(feeCurrency = spec, fee = fee.withCurrency(spec))
        }
    }

    /** The keypad strip: the typed value stays, cut to the new currency's decimals; the rate is the new currency's. */
    fun pickCurrency(code: String) = edit { s ->
        if (code == s.currency.code) s else {
            val spec = s.strip.firstOrNull { it.code == code } ?: specOf(code)
            s.copy(currency = spec, amount = s.amount.withCurrency(spec), rateOverride = null)
        }
    }

    fun openRateSheet() = _state.update { s ->
        val main = s.shownMain ?: return@update s
        s.copy(rateSheet = RateSheet(AmountInput.of(main, s.mainCurrency), s.shownRate.orEmpty()), panel = Panel.NONE)
    }

    fun focusRate(field: RateField) = _state.update { s -> s.copy(rateSheet = s.rateSheet?.copy(focus = field, fresh = true)) }

    fun onRateKey(key: KeypadKey) = _state.update { s ->
        val sheet = s.rateSheet ?: return@update s
        val minor = s.amount.minorUnits() ?: return@update s
        val main = s.mainCurrency.code
        when (sheet.focus) {
            RateField.RATE -> {
                val rate = typeRate(if (sheet.fresh && key is KeypadKey.Digit) "" else sheet.rate, key)
                val mainMinor = Fx.parseRate(rate)?.let { Fx.mainAt(minor, s.currency.code, it, main) } ?: 0L
                s.copy(rateSheet = sheet.copy(rate = rate, main = AmountInput.of(mainMinor, s.mainCurrency), fresh = false))
            }
            RateField.MAIN -> {
                val start = if (sheet.fresh && key is KeypadKey.Digit) sheet.main.copy(text = "") else sheet.main
                val typed = key.applyTo(start)
                val rate = typed.minorUnits()?.takeIf { it > 0 }?.let { Fx.formatRate(Fx.rateOf(minor, s.currency.code, it, main)) } ?: ""
                s.copy(rateSheet = sheet.copy(main = typed, rate = rate, fresh = false))
            }
        }
    }

    /** Done: the sheet's rate becomes this entry's own (an empty or zero rate changes nothing). */
    fun rateSheetDone() = edit { s ->
        val rate = s.rateSheet?.rate?.let(Fx::parseRate)
        if (rate == null) s.copy(rateSheet = null) else s.copy(rateSheet = null, rateOverride = rate)
    }

    fun closeRateSheet() = _state.update { it.copy(rateSheet = null) }
    fun onAmountDone() = _state.update { it.copy(panel = nextPanelAfterAmount(it)) }
    /** The calculator's DONE: its result replaces the amount. */
    fun setAmount(minor: Long) = edit { it.copy(amount = AmountInput.fromMinor(minor, it.amount.decimals).copy(maxFraction = it.amount.maxFraction)) }
    fun pickCategory(id: String) = edit { s ->
        s.copy(categoryId = id, panel = if (s.accountId == null) Panel.ACCOUNT else amountIfStillEmpty(s))
    }
    fun setNote(note: String) {
        edit { it.copy(note = note) }
        suggest(note)
    }

    /** Autocomplete: earlier notes of this entry type containing what is typed (Configuration → Autocomplete). */
    private fun suggest(typed: String) {
        suggestJob?.cancel()
        suggestJob = viewModelScope.launch {
            val s = settings.current()
            val found = if (s.autocomplete) txns.noteSuggestions(_state.value.type, typed, s.autocompleteSince) else emptyList()
            _state.update { it.copy(suggestions = found) }
        }
    }

    /** A tapped suggestion fills the note and nothing else. */
    fun pickSuggestion(note: String) {
        suggestJob?.cancel()
        edit { it.copy(note = note, suggestions = emptyList()) }
    }

    fun clearSuggestions() {
        suggestJob?.cancel()
        _state.update { it.copy(suggestions = emptyList()) }
    }
    fun setDescription(text: String) = edit { it.copy(description = text) }

    /** A picked or captured photo, already stored under [name]; a full form or a photo it already has is ignored. */
    fun addPhoto(name: String) = edit { s -> if (name in s.photos || s.photos.size >= MAX_PHOTOS) s else s.copy(photos = s.photos + name) }

    /** The ✕ on a thumbnail: the file stays until Save, and a form left unsaved changes nothing. */
    fun removePhoto(name: String) = edit { it.copy(photos = it.photos - name) }

    fun pickAccount(id: String) = edit { s ->
        if (s.panel == Panel.TO_ACCOUNT) {
            s.copy(toAccountId = id, panel = amountIfStillEmpty(s))
        } else {
            s.copy(
                accountId = id,
                panel = when {
                    s.type == TxnType.TRANSFER && s.toAccountId == null -> Panel.TO_ACCOUNT
                    s.type != TxnType.TRANSFER && s.inputOrder == InputOrder.ACCOUNT && s.categoryId == null -> Panel.CATEGORY
                    else -> amountIfStillEmpty(s)
                },
            )
        }
    }

    /** From Account: the keypad comes after the pickers while no amount was typed. */
    private fun amountIfStillEmpty(s: EditTxnState): Panel =
        if (s.inputOrder == InputOrder.ACCOUNT && s.amount.isEmpty) Panel.AMOUNT else Panel.NONE

    /** Photo imports under way (a big one is re-encoded): Save waits for them so the photo is not dropped. */
    private val importsRunning = MutableStateFlow(0)
    private var saveWaiting = false

    fun beginImport() = importsRunning.update { it + 1 }

    fun endImport() = importsRunning.update { it - 1 }

    fun save(continueAfter: Boolean = false) {
        if (_state.value.isRule) {
            saveRule(_state.value)
            return
        }
        if (importsRunning.value > 0) {
            if (saveWaiting) return
            saveWaiting = true
            viewModelScope.launch {
                importsRunning.first { it == 0 }
                saveWaiting = false
                save(continueAfter)
            }
            return
        }
        // Read at tap time: a second tap must not see the reset that the first save's Continue makes.
        val s = _state.value
        val editing = original
        val id = editing?.id ?: newId
        viewModelScope.launch {
            validate(s)?.let { _events.send(EditEvent.Error(it)); return@launch }
            val account = accounts.get(s.accountId!!)
            if (account == null || account.deleted) {
                _events.send(EditEvent.Error("Please select an account."))
                return@launch
            }
            val amount = s.amount.minorUnits()!!
            // Rep/Inst. applies to new entries only; an installment plan stores its first part here.
            val choice = if (editing == null) s.repeat else null
            val parts = (choice as? RepeatChoice.Installments)?.let { splitInstallments(amount, it.months) }
            val stored = parts?.first() ?: amount
            // Derived from the entry id, so a double-tapped Save writes the same rule twice.
            val ruleId = choice?.let { "r-$id" }
            val cs = currencies.current()
            val toAccount = if (s.type == TxnType.TRANSFER) s.toAccountId?.let { accounts.get(it) } else null
            // Only a changed amount, currency, rate, type or account recomputes the sides: a plain re-save keeps them.
            val amounts = if (editing != null && keepsAmounts(editing, s, stored, account.id, cs.main)) {
                StoredAmounts(editing.amount, editing.currency, editing.amountMain, editing.accountAmount, editing.toAmount)
            } else {
                val main = s.rateOverride?.takeIf { s.currency.code != cs.main }?.let { Fx.mainAt(stored, s.currency.code, it, cs.main) }
                entryAmounts(stored, s.currency.code, main, account.currency, toAccount?.currency, cs.fx)
            }
            val base = editing ?: TxnEntity(
                id = id, type = s.type, dateTime = "", accountId = account.id,
                amount = stored, currency = s.currency.code, amountMain = stored, updatedAt = 0,
            )
            val entry = base.copy(
                type = s.type,
                dateTime = DbTime.format(s.date.atTime(s.time)),
                accountId = account.id,
                toAccountId = if (s.type == TxnType.TRANSFER) s.toAccountId else null,
                categoryId = if (s.type == TxnType.TRANSFER) null else s.categoryId,
                amount = amounts.amount,
                currency = amounts.currency,
                amountMain = amounts.amountMain,
                accountAmount = amounts.accountAmount,
                toAmount = amounts.toAmount,
                note = s.note.trim(),
                description = s.description.trim(),
                repeatRuleId = ruleId ?: base.repeatRuleId,
                installmentTotal = parts?.size ?: base.installmentTotal,
                installmentIndex = if (parts != null) 1 else base.installmentIndex,
            )
            txns.save(entry, s.photos)
            saveFee(s, entry, account.currency, cs.fx)
            if (choice != null && ruleId != null) {
                val frequency = (choice as? RepeatChoice.Repeat)?.frequency ?: RepeatFrequency.MONTHLY
                repeats.create(ruleId, entry, frequency, installments = parts?.size, laterAmount = parts?.getOrNull(1) ?: stored)
                repeats.catchUp(time.today())
            }
            if (continueAfter) {
                original = null
                newId = UUID.randomUUID().toString()
                _state.update {
                    it.copy(
                        editingId = null, amount = it.amount.copy(text = ""), categoryId = null, rateOverride = null, fee = null,
                        note = "", description = "", photos = emptyList(), panel = firstPanel(it.inputOrder), dirty = false, repeat = null,
                    )
                }
            } else {
                _events.send(EditEvent.Closed)
            }
        }
    }

    fun delete() {
        _state.value.ruleId?.let { ruleId ->
            viewModelScope.launch {
                repeats.delete(ruleId)
                _events.send(EditEvent.Closed)
            }
            return
        }
        val id = _state.value.editingId ?: return
        viewModelScope.launch {
            txns.delete(id)
            txns.delete(feeId(id))
            _events.send(EditEvent.Closed)
        }
    }

    private fun keepsAmounts(old: TxnEntity, s: EditTxnState, stored: Long, accountId: String, main: String): Boolean {
        val oldRate = if (old.currency == main) null else Fx.rateOf(old.amount, old.currency, old.amountMain, main)
        val sameRate = if (oldRate == null || s.rateOverride == null) oldRate == s.rateOverride else oldRate.compareTo(s.rateOverride) == 0
        return old.amount == stored && old.currency == s.currency.code && old.type == s.type && old.accountId == accountId &&
            old.toAccountId == (if (s.type == TxnType.TRANSFER) s.toAccountId else null) && sameRate
    }

    /** The transfer's fee entry follows the form: saved with it, or tombstoned when the Fees row or the transfer is gone. */
    private suspend fun saveFee(s: EditTxnState, entry: TxnEntity, accountCurrency: String, fx: Fx) {
        val feeMinor = s.fee?.minorUnits()?.takeIf { entry.type == TxnType.TRANSFER && it != 0L }
        val old = txns.get(feeId(entry.id))?.takeIf { !it.deleted }
        if (feeMinor == null) {
            old?.let { txns.delete(it.id) }
            return
        }
        // Like the entry itself: an unchanged fee keeps the amounts it was stored with.
        val m = if (old != null && old.amount == feeMinor && old.currency == s.feeCurrency.code && old.accountId == entry.accountId) {
            StoredAmounts(old.amount, old.currency, old.amountMain, old.accountAmount, null)
        } else {
            entryAmounts(feeMinor, s.feeCurrency.code, null, accountCurrency, null, fx)
        }
        val other = old?.categoryId ?: categories.top(CategoryType.EXPENSE).firstOrNull { it.name == "Other" }?.id
        val base = old ?: TxnEntity(
            id = feeId(entry.id), type = TxnType.EXPENSE, dateTime = "", accountId = entry.accountId,
            amount = 0, currency = "", amountMain = 0, updatedAt = 0, note = "Fees",
        )
        txns.save(
            base.copy(
                type = TxnType.EXPENSE, dateTime = entry.dateTime, accountId = entry.accountId, toAccountId = null, categoryId = other,
                amount = m.amount, currency = m.currency, amountMain = m.amountMain, accountAmount = m.accountAmount, toAmount = null,
            ),
        )
    }

    /** Edit screen → Bookmark: saves the stored entry (the button shows only while the form is unchanged). */
    fun bookmark() {
        val entry = original ?: return
        viewModelScope.launch {
            bookmarks.add(entry)
            _events.send(EditEvent.Info("Added to Bookmarks"))
        }
    }

    /** A new entry filled from [b], dated now. A deleted category or a hidden or deleted account stays empty. */
    private fun fromBookmark(fresh: EditTxnState, b: BookmarkEntity, categoryId: String?, usable: Set<String>, spec: CurrencySpec) =
        fresh.copy(
            type = b.type,
            amount = AmountInput.of(b.amount, spec),
            currency = spec,
            categoryId = categoryId,
            accountId = b.accountId.takeIf { it in usable },
            toAccountId = b.toAccountId?.takeIf { it in usable },
            note = b.note,
            description = b.description,
            panel = Panel.NONE,
        )

    /** Rule mode: the rule's template, dated on its next occurrence. A deleted category or a hidden account stays empty. */
    private fun fromRule(fresh: EditTxnState, r: RepeatRuleEntity, categoryId: String?, usable: Set<String>, spec: CurrencySpec): EditTxnState {
        val next = RepeatSchedule.next(r.frequency, LocalDate.parse(r.startDate), LocalDate.parse(r.lastDate), r.installments)?.date
            ?: time.today()
        return fresh.copy(
            ruleId = r.id, ruleNext = next, type = r.type, date = next, time = LocalTime.parse(r.time),
            amount = AmountInput.of(r.amount, spec), currency = spec, categoryId = categoryId,
            accountId = r.accountId.takeIf { it in usable }, toAccountId = r.toAccountId?.takeIf { it in usable },
            note = r.note, description = r.description,
            repeat = r.installments?.let { RepeatChoice.Installments(it) } ?: RepeatChoice.Repeat(r.frequency),
            panel = Panel.NONE,
        )
    }

    /** Rule mode's Save: later occurrences follow the form; entries already added stay as they are. */
    private fun saveRule(s: EditTxnState) {
        viewModelScope.launch {
            validate(s)?.let { _events.send(EditEvent.Error(it)); return@launch }
            val account = accounts.get(s.accountId!!)
            if (account == null || account.deleted) {
                _events.send(EditEvent.Error("Please select an account."))
                return@launch
            }
            val rule = repeats.get(s.ruleId!!)
            if (rule == null) {
                _events.send(EditEvent.Closed)
                return@launch
            }
            val frequency = (s.repeat as? RepeatChoice.Repeat)?.frequency ?: rule.frequency
            // A new date or frequency restarts the count on the shown date: it becomes occurrence 0, so a new
            // frequency never back-fills the dates since the last entry (plans keep their dates).
            val moved = rule.installments == null && (s.date != s.ruleNext || frequency != rule.frequency)
            val last = LocalDate.parse(rule.lastDate)
            if (moved && !s.date.isAfter(last)) {
                // Dates up to the last entry are covered already; restarting there would add them again.
                _events.send(EditEvent.Error("Please pick a date after ${Formats.monthDayShort(last)}."))
                return@launch
            }
            repeats.save(
                rule.copy(
                    frequency = frequency,
                    startDate = if (moved) s.date.toString() else rule.startDate,
                    lastDate = if (moved) s.date.minusDays(1).toString() else rule.lastDate,
                    type = s.type,
                    accountId = account.id,
                    toAccountId = if (s.type == TxnType.TRANSFER) s.toAccountId else null,
                    categoryId = if (s.type == TxnType.TRANSFER) null else s.categoryId,
                    amount = s.amount.minorUnits()!!,
                    currency = s.currency.code,
                    note = s.note.trim(),
                    description = s.description.trim(),
                    time = s.time.format(RepeatRepository.TIME),
                ),
            )
            repeats.catchUp(time.today())
            _events.send(EditEvent.Closed)
        }
    }

    companion object {
        /** The id of a transfer's fee entry. */
        fun feeId(id: String) = "$id-fee"

        /** The panel a new entry opens on: the keypad, or the account grid for From Account. */
        fun firstPanel(order: InputOrder): Panel = if (order == InputOrder.ACCOUNT) Panel.ACCOUNT else Panel.AMOUNT

        fun validate(s: EditTxnState): String? {
            val amount = s.amount.minorUnits()
            if (amount == null || amount == 0L) return "Please enter the amount."
            if (s.type == TxnType.TRANSFER) {
                if (s.accountId == null || s.toAccountId == null) return "Please select an account."
                if (s.accountId == s.toAccountId) return "Please select different accounts."
            } else {
                if (s.categoryId == null) return "Please select a category."
                if (s.accountId == null) return "Please select an account."
            }
            return null
        }

        fun nextPanelAfterAmount(s: EditTxnState): Panel = when {
            s.type == TxnType.TRANSFER && s.accountId == null -> Panel.ACCOUNT
            s.type == TxnType.TRANSFER && s.toAccountId == null -> Panel.TO_ACCOUNT
            s.type != TxnType.TRANSFER && s.categoryId == null -> Panel.CATEGORY
            s.type != TxnType.TRANSFER && s.accountId == null -> Panel.ACCOUNT
            else -> Panel.NONE
        }
    }
}
