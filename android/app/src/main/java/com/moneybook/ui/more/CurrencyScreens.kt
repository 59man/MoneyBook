package com.moneybook.ui.more

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moneybook.R
import com.moneybook.data.CurrencyRepository
import com.moneybook.data.CurrencyState
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.shared.money.Fx
import com.moneybook.shared.money.MoneyFormat
import com.moneybook.shared.money.RealbyteCurrencies
import com.moneybook.shared.money.SymbolPosition
import com.moneybook.ui.common.ChoiceDialog
import com.moneybook.ui.common.ConfirmDialog
import com.moneybook.ui.common.DragHandle
import com.moneybook.ui.common.EditPencil
import com.moneybook.ui.common.EmptyState
import com.moneybook.ui.common.FilledButton
import com.moneybook.ui.common.FitText
import com.moneybook.ui.common.FormRow
import com.moneybook.ui.common.Hairline
import com.moneybook.ui.common.IconButtonBox
import com.moneybook.ui.common.ListTopGap
import com.moneybook.ui.common.MbIcon
import com.moneybook.ui.common.MbTopBar
import com.moneybook.ui.common.MessageDialog
import com.moneybook.ui.common.RemoveBadge
import com.moneybook.ui.common.ReorderableColumn
import com.moneybook.ui.common.TitleText
import com.moneybook.ui.common.ValueText
import com.moneybook.ui.common.appContainer
import com.moneybook.ui.common.bottomHairline
import com.moneybook.ui.edit.KeypadKey
import com.moneybook.ui.edit.RateKeypad
import com.moneybook.ui.edit.RateSheetFrame
import com.moneybook.ui.edit.typeRate
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CurrencySettingsViewModel(private val repo: CurrencyRepository) : ViewModel() {
    val state: StateFlow<CurrencyState?> = repo.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    suspend fun add(code: String) = repo.add(code)
    suspend fun delete(code: String) = repo.delete(code)
    fun reorder(codes: List<String>) {
        viewModelScope.launch { repo.reorder(codes) }
    }
    suspend fun update(code: String, rate: BigDecimal, position: SymbolPosition, decimals: Int) = repo.update(code, rate, position, decimals)
    suspend fun setMainDisplay(position: SymbolPosition, decimals: Int) = repo.setMainDisplay(position, decimals)
    suspend fun changeMainRetain(code: String, rate: BigDecimal) = repo.changeMainRetain(code, rate)
    suspend fun changeMainInitialize(code: String) = repo.changeMainInitialize(code)
}

/**
 * Writes, then leaves the screen on the main thread. Written from the screen's scope, not the view model's,
 * whose scope ends with the screen.
 */
private fun CoroutineScope.saveThen(write: suspend () -> Unit, then: () -> Unit) {
    launch {
        write()
        withContext(Dispatchers.Main) { then() }
    }
}

@Composable
private fun currencyVm(): CurrencySettingsViewModel {
    val c = appContainer()
    return viewModel { CurrencySettingsViewModel(c.currencies) }
}

/** Decimal point's options and the decimals each shows. */
private val DECIMAL_LABELS = listOf("None", "1.0", "1.00", "1.000", "1.0000", "1.00000", "1.00000000")
private val DECIMAL_VALUES = listOf(0, 1, 2, 3, 4, 5, 8)

private fun decimalLabel(decimals: Int) = DECIMAL_LABELS.getOrElse(DECIMAL_VALUES.indexOf(decimals)) { "1." + "0".repeat(decimals) }

private fun unitOf(position: SymbolPosition) = if (position == SymbolPosition.START) "Front" else "End"

/** One unit of [spec] ("1.00", "1"); with the symbol where [spec] puts it. */
private fun one(spec: CurrencySpec, withSymbol: Boolean = true) =
    MoneyFormat.format(BigDecimal.TEN.pow(spec.decimals).toLong(), spec, withSymbol)

private fun label(code: String) = RealbyteCurrencies.find(code)?.label ?: code

// ---- Sub Currency Setting ----

@Composable
fun SubCurrencySettingScreen(onBack: () -> Unit, onAdd: () -> Unit, onEdit: (String) -> Unit) {
    val vm = currencyVm()
    val s by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var refused by remember { mutableStateOf(false) }
    val state = s ?: return
    SubCurrencyContent(
        state, onBack, onAdd, onEdit,
        onDelete = { code -> scope.launch { if (!vm.delete(code)) refused = true } },
        onReorder = vm::reorder,
    )
    if (refused) MessageDialog("Delete", "This currency is used by an account.") { refused = false }
}

@Composable
fun SubCurrencyContent(
    state: CurrencyState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
) {
    val subs = state.subs
    var order by remember(subs) { mutableStateOf(subs) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val main = state.mainSpec
    Column(Modifier.fillMaxSize().background(Mb.colors.background).systemBarsPadding()) {
        MbTopBar(onBack = onBack, title = { TitleText("Sub Currency Setting") }, actions = { IconButtonBox(R.drawable.ic_add, "Add", onAdd) })
        Hairline(px = 2)
        if (subs.isEmpty()) {
            EmptyState()
            return@Column
        }
        ReorderableColumn(
            items = order,
            keyOf = { it.code },
            onMove = { from, to -> order = order.toMutableList().apply { add(to, removeAt(from)) } },
            onDragEnd = { onReorder(order.map { it.code }) },
            modifier = Modifier.verticalScroll(rememberScrollState()),
        ) { row, handle ->
            val spec = state.spec(row.code)
            Row(
                Modifier.fillMaxWidth().background(Mb.colors.surface).bottomHairline(Mb.colors.divider)
                    .heightIn(min = MbDimens.listRow).height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RemoveBadge("Delete ${row.code}") { confirm = row.code }
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    val rate = Fx.formatRate(Fx.parseRate(row.rate) ?: BigDecimal.ONE)
                    FitText("${row.code} ${one(spec, withSymbol = false)}  =  ${main.code} $rate", MbType.currencyRow, Mb.colors.textPrimary)
                    FitText(label(row.code), MbType.rowAccount, Mb.colors.textTertiary)
                }
                EditPencil("Edit ${row.code}") { onEdit(row.code) }
                DragHandle(handle, "Move ${row.code}")
            }
        }
    }
    confirm?.let { code ->
        ConfirmDialog("Delete currency", "Delete $code?", "Delete", onConfirm = { onDelete(code) }, onDismiss = { confirm = null })
    }
}

// ---- Currency list (+ on Sub Currency Setting, Change on Main Currency Setting) ----

enum class CurrencyListMode { SUB, MAIN }

@Composable
fun CurrencyListScreen(mode: CurrencyListMode, onBack: () -> Unit, onDone: () -> Unit) {
    val vm = currencyVm()
    val s by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val state = s ?: return
    CurrencyListContent(
        mode, state.main, onBack,
        onAdd = { code -> scope.saveThen({ vm.add(code) }, onBack) },
        suggestedRate = { code ->
            // 1 old main = 1 / (the new main's rate as a sub-currency).
            state.subs.firstOrNull { it.code == code }?.rate?.let(Fx::parseRate)
                ?.let { BigDecimal.ONE.divide(it, 10, RoundingMode.HALF_UP).stripTrailingZeros() } ?: BigDecimal.ONE
        },
        onRetain = { code, rate -> scope.saveThen({ vm.changeMainRetain(code, rate) }, onDone) },
        onInitialize = { code -> scope.saveThen({ vm.changeMainInitialize(code) }, onDone) },
    )
}

private const val NOTICE = "[Notice] Changing the currency will affect the entry made before. The recent exchange rate of changed-currency " +
    "will apply to the exchange rate in a lump. Are you sure you want to change the currency for this account?"

private enum class MainStep { CHOICE, NOTICE, RATE, INITIALIZE }

@Composable
fun CurrencyListContent(
    mode: CurrencyListMode,
    main: String,
    onBack: () -> Unit,
    onAdd: (String) -> Unit = {},
    suggestedRate: (String) -> BigDecimal = { BigDecimal.ONE },
    onRetain: (String, BigDecimal) -> Unit = { _, _ -> },
    onInitialize: (String) -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<String?>(null) }
    var step by remember { mutableStateOf<MainStep?>(null) }
    var rate by remember { mutableStateOf("") }
    var fresh by remember { mutableStateOf(true) }
    val all = RealbyteCurrencies.all
    val shown = if (query.isBlank()) all else all.filter { it.label.contains(query.trim(), ignoreCase = true) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(Mb.colors.surface).systemBarsPadding()) {
            MbTopBar(onBack = onBack, title = { TitleText(if (mode == CurrencyListMode.SUB) "Settings" else "Main Currency Setting") })
            Hairline(px = 2)
            if (mode == CurrencyListMode.SUB) SearchField(query) { query = it }
            LazyColumn(Modifier.weight(1f)) {
                shown.forEachIndexed { i, entry ->
                    val letter = entry.code.first()
                    if (mode == CurrencyListMode.SUB && query.isBlank() && (i == 0 || shown[i - 1].code.first() != letter)) {
                        item(key = "h$letter") {
                            Text(
                                letter.toString(), style = MbType.listItem, color = Mb.colors.textTertiary,
                                modifier = Modifier.fillMaxWidth().background(Mb.colors.background).padding(horizontal = 20.dp, vertical = 6.dp),
                            )
                        }
                    }
                    item(key = entry.code) {
                        Box(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).bottomHairline(Mb.colors.divider).clickable {
                                when {
                                    mode == CurrencyListMode.SUB -> onAdd(entry.code)
                                    entry.code == main -> onBack()
                                    else -> { picked = entry.code; step = MainStep.CHOICE }
                                }
                            }.padding(horizontal = 20.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) { FitText(entry.label, MbType.listItem, Mb.colors.textPrimary, maxLines = 2) }
                    }
                }
            }
        }
        val code = picked
        if (code != null && step == MainStep.RATE) {
            RateSheetFrame(
                onKey = { key -> rate = typeRate(if (fresh && key is KeypadKey.Digit) "" else rate, key); fresh = false },
                onDone = { Fx.parseRate(rate)?.let { r -> step = null; onRetain(code, r) } },
                onClose = { step = null },
            ) {
                FormRow("1 $main =", active = true) { ValueText("$rate $code") }
            }
        }
    }
    val code = picked ?: return
    val spec = RealbyteCurrencies.defaultSpec(code)
    when (step) {
        MainStep.CHOICE -> ChoiceDialog(
            "", listOf("Data retention", "Data initialization", "Cancel"), onDismiss = { step = null },
            description = "Your main currency will be changed to $code(${spec.symbol}).",
        ) { i -> step = listOf(MainStep.NOTICE, MainStep.INITIALIZE, null)[i] }
        MainStep.NOTICE -> ConfirmDialog(
            "Notice", NOTICE, "YES",
            onConfirm = { rate = Fx.formatRate(suggestedRate(code)); fresh = true; step = MainStep.RATE },
            onDismiss = { if (step == MainStep.NOTICE) step = null },
        )
        MainStep.INITIALIZE -> ConfirmDialog(
            "Data initialization", "All data will be deleted and the main currency set to $code (${spec.symbol}). Continue?", "Continue",
            onConfirm = { onInitialize(code) }, onDismiss = { if (step == MainStep.INITIALIZE) step = null },
        )
        MainStep.RATE, null -> Unit
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).height(40.dp).clip(shape)
            .border(1.dp, Mb.colors.divider, shape).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MbIcon(R.drawable.ic_search, null, tint = Mb.colors.textTertiary)
        Spacer(Modifier.padding(start = 8.dp))
        BasicTextField(
            query, onChange, singleLine = true,
            textStyle = MbType.listItem.copy(color = Mb.colors.textPrimary), cursorBrush = SolidColor(Mb.colors.accent),
            modifier = Modifier.weight(1f).semantics { contentDescription = "Search" },
        )
    }
}

// ---- Edit a sub-currency ----

private enum class EditDialog { POSITION, DECIMALS }

@Composable
fun CurrencyEditScreen(code: String, onBack: () -> Unit) {
    val vm = currencyVm()
    val s by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val state = s ?: return
    val row = state.rows.firstOrNull { it.code == code } ?: return
    CurrencyEditContent(state.spec(code), state.mainSpec, Fx.parseRate(row.rate) ?: BigDecimal.ONE, onBack) { rate, position, decimals ->
        scope.saveThen({ vm.update(code, rate, position, decimals) }, onBack)
    }
}

@Composable
fun CurrencyEditContent(
    spec: CurrencySpec,
    mainSpec: CurrencySpec,
    rate: BigDecimal,
    onBack: () -> Unit,
    onSave: (BigDecimal, SymbolPosition, Int) -> Unit,
) {
    var rateText by remember { mutableStateOf(Fx.formatRate(rate)) }
    var position by remember { mutableStateOf(spec.position) }
    var decimals by remember { mutableStateOf(spec.display) }
    var keypad by remember { mutableStateOf(false) }
    var fresh by remember { mutableStateOf(true) }
    var dialog by remember { mutableStateOf<EditDialog?>(null) }
    val shown = spec.copy(position = position, display = decimals)
    val rateShown = Fx.parseRate(rateText)?.let(Fx::formatRate) ?: rateText
    BackHandler(enabled = keypad) { keypad = false }
    Column(Modifier.fillMaxSize().background(Mb.colors.surface).systemBarsPadding()) {
        MbTopBar(onBack = onBack, title = { TitleText("Sub Currency Setting") })
        Hairline(px = 2)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Row(
                Modifier.fillMaxWidth().background(Mb.colors.background).padding(vertical = 24.dp, horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SampleColumn(one(shown), Modifier.weight(1f))
                Text("=", style = MbType.currencySample, color = Mb.colors.textPrimary)
                val right = if (mainSpec.position == SymbolPosition.START) "${mainSpec.symbol} $rateShown" else "$rateShown ${mainSpec.symbol}"
                SampleColumn(right, Modifier.weight(1f))
            }
            FormRow("Exchange rate", active = keypad, onClick = { keypad = true; fresh = true }) { ValueText(rateText) }
            FormRow("Unit position", onClick = { keypad = false; dialog = EditDialog.POSITION }) { ValueText(unitOf(position)) }
            FormRow("Decimal point", onClick = { keypad = false; dialog = EditDialog.DECIMALS }) { ValueText(decimalLabel(decimals)) }
            Spacer(Modifier.height(30.dp))
            FilledButton("Save", Mb.colors.accent, Modifier.fillMaxWidth().padding(horizontal = 13.dp)) {
                Fx.parseRate(rateText)?.let { onSave(it, position, decimals) }
            }
        }
        if (keypad) {
            RateKeypad(
                "Exchange rate",
                onKey = { key -> rateText = typeRate(if (fresh && key is KeypadKey.Digit) "" else rateText, key); fresh = false },
                onDone = { keypad = false }, onClose = { keypad = false },
            )
        }
    }
    DisplayDialogs(dialog, spec.symbol, position, decimals, onPosition = { position = it }, onDecimals = { decimals = it }) { dialog = null }
}

@Composable
private fun SampleColumn(amount: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Name", style = MbType.summaryLabel, color = Mb.colors.textTertiary)
        FitText(amount, MbType.currencySample, Mb.colors.textPrimary, textAlign = TextAlign.Center)
    }
}

/** Unit position and Decimal point's option dialogs (captures rb_subedit, rb_main). */
@Composable
private fun DisplayDialogs(
    dialog: EditDialog?,
    symbol: String,
    position: SymbolPosition,
    decimals: Int,
    onPosition: (SymbolPosition) -> Unit,
    onDecimals: (Int) -> Unit,
    onClose: () -> Unit,
) {
    when (dialog) {
        EditDialog.POSITION -> ChoiceDialog(
            "Unit position", listOf("Front ($symbol 1.00)", "End (1.00 $symbol)"), if (position == SymbolPosition.START) 0 else 1, onDismiss = onClose,
        ) { onPosition(if (it == 0) SymbolPosition.START else SymbolPosition.END); onClose() }
        EditDialog.DECIMALS -> ChoiceDialog("Decimal point", DECIMAL_LABELS, DECIMAL_VALUES.indexOf(decimals), onDismiss = onClose) {
            onDecimals(DECIMAL_VALUES[it]); onClose()
        }
        null -> Unit
    }
}

// ---- Main Currency Setting ----

@Composable
fun MainCurrencyScreen(onBack: () -> Unit, onChange: () -> Unit) {
    val vm = currencyVm()
    val s by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val state = s ?: return
    // Keyed on the main currency, so a change resets the unsaved choices.
    key(state.main) {
        MainCurrencyContent(state.mainSpec, onBack, onChange) { position, decimals ->
            scope.saveThen({ vm.setMainDisplay(position, decimals) }, onBack)
        }
    }
}

@Composable
fun MainCurrencyContent(spec: CurrencySpec, onBack: () -> Unit, onChange: () -> Unit, onSave: (SymbolPosition, Int) -> Unit) {
    var position by remember { mutableStateOf(spec.position) }
    var decimals by remember { mutableStateOf(spec.display) }
    var dialog by remember { mutableStateOf<EditDialog?>(null) }
    val shown = spec.copy(position = position, display = decimals)
    Column(Modifier.fillMaxSize().background(Mb.colors.surface).systemBarsPadding()) {
        MbTopBar(
            onBack = onBack, title = { TitleText("Main Currency Setting") },
            actions = { ChangeButton(onChange) },
        )
        Hairline(px = 2)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Column(
                Modifier.fillMaxWidth().background(Mb.colors.background).padding(vertical = 18.dp, horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                FitText(label(spec.code), MbType.listItem, Mb.colors.textTertiary, textAlign = TextAlign.Center)
                FitText(MoneyFormat.format(1_000L * BigDecimal.TEN.pow(spec.decimals).toLong(), shown), MbType.currencyBig, Mb.colors.textPrimary)
            }
            FormRow("Unit position", onClick = { dialog = EditDialog.POSITION }) { ValueText(unitOf(position)) }
            FormRow("Decimal point", onClick = { dialog = EditDialog.DECIMALS }) { ValueText(decimalLabel(decimals)) }
            Spacer(Modifier.height(30.dp))
            FilledButton("Save", Mb.colors.accent, Modifier.fillMaxWidth().padding(horizontal = 13.dp)) { onSave(position, decimals) }
        }
    }
    DisplayDialogs(dialog, spec.symbol, position, decimals, onPosition = { position = it }, onDecimals = { decimals = it }) { dialog = null }
}

/** Main Currency Setting's small outlined "Change" (rb_main: about 66 × 32 dp, inset from the bar's edge). */
@Composable
private fun ChangeButton(onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier.padding(end = 10.dp).clip(shape).border(1.dp, Mb.colors.textSecondary, shape).clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp),
    ) { Text("Change", style = MbType.button, color = Mb.colors.textPrimary) }
}
