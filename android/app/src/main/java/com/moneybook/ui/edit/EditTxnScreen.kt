package com.moneybook.ui.edit

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.moneybook.shared.model.CategoryType
import kotlin.math.roundToInt
import com.moneybook.data.CopyDateBy
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moneybook.AppLock
import com.moneybook.R
import com.moneybook.data.PhotoStore
import com.moneybook.data.InputOrder
import com.moneybook.domain.Calculator
import com.moneybook.domain.Formats
import com.moneybook.shared.model.TxnType
import com.moneybook.shared.money.SymbolPosition
import com.moneybook.ui.calc.CalculatorScreen
import com.moneybook.ui.common.ConfirmDialog
import com.moneybook.ui.common.DatePickDialog
import com.moneybook.ui.common.FilledButton
import com.moneybook.ui.common.FitText
import com.moneybook.ui.common.FormRow
import com.moneybook.ui.common.Hairline
import com.moneybook.ui.common.IconButtonBox
import com.moneybook.ui.common.LocalAppLocked
import com.moneybook.ui.common.MbIcon
import com.moneybook.ui.common.MbTopBar
import com.moneybook.ui.common.MenuPopup
import com.moneybook.ui.common.OutlinedButtonBox
import com.moneybook.ui.common.TitleText
import com.moneybook.ui.common.ValueText
import com.moneybook.ui.common.appContainer
import com.moneybook.ui.common.highlightMatches
import com.moneybook.ui.common.money
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.launch

fun typeTitle(type: TxnType): String = when (type) {
    TxnType.INCOME -> "Income"
    TxnType.EXPENSE -> "Expense"
    TxnType.TRANSFER -> "Transfer"
}

@Composable
fun typeColor(type: TxnType): Color = when (type) {
    TxnType.INCOME -> Mb.colors.income
    TxnType.EXPENSE -> Mb.colors.expense
    TxnType.TRANSFER -> Mb.colors.textBody
}

private enum class PickerDialog { DATE, TIME }

/** Full-screen pieces drawn over the form (they are not routes, so their result needs no round trip). */
private enum class Overlay { REPEAT, INSTALLMENT, CALCULATOR }

@Composable
fun EditTxnScreen(
    txnId: String?,
    initialDate: LocalDate?,
    onClose: () -> Unit,
    bookmarkId: String? = null,
    ruleId: String? = null,
    openRepeat: Boolean = false,
    initialType: TxnType? = null,
    preset: TxnPreset? = null,
    copyOf: String? = null,
    copyBy: CopyDateBy? = null,
    /** The Copy dialog's OK: opens the copy form of this entry, dated per the choice. */
    onCopy: (String, CopyDateBy) -> Unit = { _, _ -> },
    /** The keypad's globe (Sub Currency Setting); null hides it. */
    onOpenSubCurrencies: (() -> Unit)? = null,
    /** The account panel's pencil (Accounts Setting); null hides it. */
    onOpenAccountsSetting: (() -> Unit)? = null,
    /** The category panel's pencil (the category list of that type); null hides it. */
    onOpenCategories: ((CategoryType) -> Unit)? = null,
) {
    val c = appContainer()
    val vm: EditTxnViewModel = viewModel {
        EditTxnViewModel(
            txnId, initialDate, c.txns, c.accounts, c.categories, c.settings, c.time, c.bookmarks, c.repeats, c.currencies, bookmarkId, ruleId,
            initialType ?: TxnType.EXPENSE, preset, copyOf, copyBy,
        )
    }
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    var dialog by remember { mutableStateOf<PickerDialog?>(null) }
    var overlay by rememberSaveable { mutableStateOf(if (openRepeat) Overlay.REPEAT else null) }
    var confirmStop by remember { mutableStateOf(false) }
    var repMenu by remember { mutableStateOf(false) }
    var copying by remember { mutableStateOf(false) }
    val storedCopyBy by vm.copyDateBy.collectAsStateWithLifecycle()
    // The row tap shows at once and goes with OK; the stored setting follows a moment later.
    var copyDateBy by remember(storedCopyBy) { mutableStateOf(storedCopyBy) }
    val photos = rememberPhotoSources(c.photos, c.lock, { MAX_PHOTOS - s.photos.size }, vm::addPhoto, vm::beginImport, vm::endImport)
    var viewing by rememberSaveable { mutableStateOf<Int?>(null) }
    val download = rememberPhotoDownload(c.photos, c.lock)
    val device by c.device.settings.collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    // Each panel's row, by its top in the form: an opened picker scrolls its row to the top (Realbyte).
    val rowTop = remember { mutableStateMapOf<Panel, Int>() }
    fun Modifier.rowOf(panel: Panel) = onGloballyPositioned { rowTop[panel] = it.positionInParent().y.roundToInt() }
    val picking = s.panel == Panel.CATEGORY || s.panel == Panel.ACCOUNT || s.panel == Panel.TO_ACCOUNT
    LaunchedEffect(s.panel) {
        withFrameNanos { } // the panel takes its space first
        val top = rowTop[s.panel] ?: return@LaunchedEffect
        if (picking) scroll.animateScrollTo(top) else if (s.panel == Panel.AMOUNT && top < scroll.value) scroll.animateScrollTo(top)
    }
    // Back from the pencil's settings screen: the pickers list what was added, renamed or hidden there.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshLists() }

    LaunchedEffect(vm) {
        vm.events.collect { e ->
            when (e) {
                is EditEvent.Error -> Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
                is EditEvent.Info -> Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
                EditEvent.Closed -> onClose()
            }
        }
    }
    BackHandler(enabled = s.panel != Panel.NONE) { vm.closePanel() }
    BackHandler(enabled = overlay != null) { overlay = null }

    fun open(panel: Panel) {
        focus.clearFocus()
        vm.openPanel(panel)
    }
    val color = typeColor(s.type)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(Mb.colors.surface).systemBarsPadding().imePadding()) {
            MbTopBar(onBack = onClose, title = { TitleText(if (s.isRule) "Repeat" else typeTitle(s.type)) })
            Column(Modifier.weight(1f).verticalScroll(scroll)) {
                TypeSegments(s.type, vm::setType)
                DateRow(
                    s.date, s.time, s.repeat?.label,
                    showTime = s.timeShown,
                    // A plan keeps its dates, so rule mode cannot move them.
                    onDate = if (s.isRule && s.repeat is RepeatChoice.Installments) null else ({
                        focus.clearFocus(); vm.closePanel(); dialog = PickerDialog.DATE
                    }),
                    onTime = { focus.clearFocus(); vm.closePanel(); dialog = PickerDialog.TIME },
                    onRepeat = when {
                        s.isRule -> if (s.repeat is RepeatChoice.Repeat) ({ overlay = Overlay.REPEAT }) else null
                        s.isNew -> ({ focus.clearFocus(); vm.closePanel(); repMenu = true })
                        else -> null
                    },
                    menu = {
                        MenuPopup(
                            repMenu, onDismiss = { repMenu = false },
                            items = buildList<Pair<String, () -> Unit>> {
                                add("Repeat" to { overlay = Overlay.REPEAT })
                                // Installment plans are for expenses only.
                                if (s.type == TxnType.EXPENSE) add("Installment" to { overlay = Overlay.INSTALLMENT })
                            },
                        )
                    },
                )
                val amountRow = @Composable {
                    AmountRows(
                        s, color, onOpen = ::open, onAddFee = { focus.clearFocus(); vm.addFee() }, onRemoveFee = vm::removeFee,
                        onRate = { focus.clearFocus(); vm.openRateSheet() }, modifier = Modifier.rowOf(Panel.AMOUNT),
                    )
                }
                val pickerRows = @Composable {
                    if (s.type == TxnType.TRANSFER) {
                        FormRow("From", active = s.panel == Panel.ACCOUNT, activeColor = color, onClick = { open(Panel.ACCOUNT) }, modifier = Modifier.rowOf(Panel.ACCOUNT)) {
                            ValueText(s.accountName(s.accountId))
                        }
                        FormRow("To", active = s.panel == Panel.TO_ACCOUNT, activeColor = color, onClick = { open(Panel.TO_ACCOUNT) }, modifier = Modifier.rowOf(Panel.TO_ACCOUNT)) {
                            ValueText(s.accountName(s.toAccountId))
                        }
                    } else {
                        val category = @Composable {
                            FormRow("Category", active = s.panel == Panel.CATEGORY, activeColor = color, onClick = { open(Panel.CATEGORY) }, modifier = Modifier.rowOf(Panel.CATEGORY)) {
                                ValueText(s.categoryName)
                            }
                        }
                        val account = @Composable {
                            FormRow("Account", active = s.panel == Panel.ACCOUNT, activeColor = color, onClick = { open(Panel.ACCOUNT) }, modifier = Modifier.rowOf(Panel.ACCOUNT)) {
                                ValueText(s.accountName(s.accountId))
                            }
                        }
                        if (s.inputOrder == InputOrder.ACCOUNT) { account(); category() } else { category(); account() }
                    }
                }
                // Configuration → Input order: From Account puts the pickers before the amount.
                if (s.inputOrder == InputOrder.ACCOUNT) { pickerRows(); amountRow() } else { amountRow(); pickerRows() }
                FormRow("Note") {
                    BasicTextField(
                        value = s.note,
                        onValueChange = vm::setNote,
                        singleLine = true,
                        textStyle = MbType.formValue.copy(color = Mb.colors.textPrimary),
                        cursorBrush = SolidColor(Mb.colors.accent),
                        modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) vm.closePanel() else vm.clearSuggestions() }
                            .semantics { contentDescription = "Note" },
                    )
                }
                if (s.suggestions.isNotEmpty()) NoteSuggestions(s.suggestions, s.note, vm::pickSuggestion)
                Spacer(Modifier.height(26.dp))
                Hairline()
                Box(Modifier.fillMaxWidth().height(9.dp).background(Mb.colors.background))
                Hairline()
                DescriptionField(s.description, vm::setDescription, onFocus = vm::closePanel, photos = photos.takeIf { s.canAddPhoto })
                PhotoStrip(s.photos, c.photos, vm::removePhoto, onOpen = { viewing = it })
                ActionButtons(
                    isNew = s.isNew, dirty = s.dirty, color = color,
                    onSave = { vm.save() }, onContinue = { vm.save(continueAfter = true) },
                    onDelete = vm::delete, onCopy = { focus.clearFocus(); copying = true }, onBookmark = vm::bookmark,
                    isRule = s.isRule, onStop = { confirmStop = true },
                )
                // Room under the form so a picker's row can scroll up to the top.
                if (picking) Spacer(Modifier.height(MbDimens.pickerPanel))
            }
            val headerColor = Mb.colors.keypadHeaderText
            val categoryEdit: @Composable RowScope.() -> Unit = {
                onOpenCategories?.let { open ->
                    IconButtonBox(R.drawable.ic_edit, "Edit categories", {
                        vm.closePanel(); open(if (s.type == TxnType.INCOME) CategoryType.INCOME else CategoryType.EXPENSE)
                    }, tint = headerColor)
                }
            }
            val grouped = device?.accountsGrouped == true
            val accountActions: @Composable RowScope.() -> Unit = {
                IconButtonBox(
                    if (grouped) R.drawable.ic_group else R.drawable.ic_ungroup, if (grouped) "Show accounts in a grid" else "Show accounts by group",
                    { scope.launch { c.device.update { it.copy(accountsGrouped = !it.accountsGrouped) } } }, tint = headerColor,
                )
                onOpenAccountsSetting?.let { open ->
                    IconButtonBox(R.drawable.ic_edit, "Edit accounts", { vm.closePanel(); open() }, tint = headerColor)
                }
            }
            when (s.panel) {
                Panel.AMOUNT -> AmountKeypad(
                    onKey = vm::onKey, onDone = vm::onAmountDone, onClose = vm::closePanel, onCalc = { overlay = Overlay.CALCULATOR },
                    strip = s.strip, selected = s.currency.code, onCurrency = vm::pickCurrency, onGlobe = onOpenSubCurrencies,
                )
                Panel.CATEGORY ->
                    if (s.subcategory) TwoPaneCategoryPanel(s.categories, s.subcategories, s.categoryId, vm::pickCategory, vm::closePanel, categoryEdit)
                    else GridPickerPanel("Category", s.categories.map { it.id to it.name }, vm::pickCategory, vm::closePanel, categoryEdit)
                Panel.ACCOUNT, Panel.TO_ACCOUNT -> {
                    val listed = s.accounts.filter { !it.hidden }
                    val current = if (s.panel == Panel.TO_ACCOUNT) s.toAccountId else s.accountId
                    // key: a group picked earlier in the panel is forgotten when it opens for the other account.
                    if (grouped) key(s.panel) { GroupedAccountPanel(s.groups, listed, current, vm::pickAccount, vm::closePanel, accountActions) }
                    else GridPickerPanel("Accounts", listed.map { it.id to it.name }, vm::pickAccount, vm::closePanel, accountActions)
                }
                Panel.FEE -> AmountKeypad(
                    onKey = vm::onKey, onDone = vm::closePanel, onClose = vm::closePanel, title = "Fees",
                    strip = s.strip, selected = s.feeCurrency.code, onCurrency = vm::pickFeeCurrency, onGlobe = onOpenSubCurrencies,
                )
                Panel.NONE -> Unit
            }
        }
        when (overlay) {
            Overlay.REPEAT -> RepeatPickScreen(
                selected = (s.repeat as? RepeatChoice.Repeat)?.frequency, includeNothing = !s.isRule,
                onPick = { f -> vm.setRepeat(f?.let { RepeatChoice.Repeat(it) }); overlay = null },
                onBack = { overlay = null },
            )
            Overlay.INSTALLMENT -> InstallmentScreen(
                initial = (s.repeat as? RepeatChoice.Installments)?.months,
                onSave = { n -> vm.setRepeat(RepeatChoice.Installments(n)); overlay = null },
                onBack = { overlay = null },
            )
            Overlay.CALCULATOR -> CalculatorScreen(
                Calculator.of(s.amount.minorUnits() ?: 0, s.amount.decimals), s.amount.decimals,
                onDone = { minor -> vm.setAmount(minor); overlay = null },
                onClose = { overlay = null },
            )
            null -> Unit
        }
        viewing?.let { at ->
            PhotoViewer(s.photos, s.savedPhotos, at, c.photos, onBack = { viewing = null }, onDownload = download)
        }
        s.rateSheet?.let { sheet ->
            RateSheetOverlay(sheet, s.mainCurrency, vm::focusRate, vm::onRateKey, vm::rateSheetDone, vm::closeRateSheet)
        }
    }

    when (dialog) {
        PickerDialog.DATE -> DatePickDialog(s.date, onPick = { vm.setDate(it); dialog = null }, onDismiss = { dialog = null })
        PickerDialog.TIME -> TimeDialog(s.time, onPick = { vm.setTime(it); dialog = null }, onDismiss = { dialog = null })
        null -> Unit
    }
    if (copying) {
        CopyDialog(
            selected = copyDateBy, onSelect = { copyDateBy = it; vm.setCopyDateBy(it) }, onDismiss = { copying = false },
            onOk = { copying = false; s.editingId?.let { onCopy(it, copyDateBy) } },
        )
    }
    if (confirmStop) {
        ConfirmDialog(
            "Stop repeat", "Stop this repeat? Entries already added stay.", "Delete",
            onConfirm = vm::delete, onDismiss = { confirmStop = false },
        )
    }
}

/** The Amount row with a sub-currency's main amount (or a transfer's Fees button), the rate line, and the Fees row. */
@Composable
internal fun AmountRows(
    s: EditTxnState,
    color: Color,
    onOpen: (Panel) -> Unit,
    onAddFee: () -> Unit,
    onRemoveFee: () -> Unit,
    onRate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FormRow("Amount", active = s.panel == Panel.AMOUNT, activeColor = color, onClick = { onOpen(Panel.AMOUNT) }, modifier = modifier) {
        // The entered amount takes the row; the main amount and the Fees button sit at its right end.
        ValueText(amountText(s), Modifier.weight(1f))
        s.shownMain?.let { FitText(money(it, s.mainCurrency), MbType.formValue, Mb.colors.textTertiary, Modifier.padding(start = 8.dp)) }
        // A repeat rule stores only the amount and its currency: no fee and no rate of its own.
        if (s.type == TxnType.TRANSFER && s.fee == null && !s.isRule) FeesButton(onAddFee)
    }
    if (s.shownMain != null && !s.isRule) {
        Text(
            "Exchange rate: ${s.shownRate}", style = MbType.rateLine, color = Mb.colors.textTertiary, textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onRate)
                .padding(end = 18.dp, top = 6.dp, bottom = 6.dp),
        )
    }
    if (s.type == TxnType.TRANSFER && s.fee != null && !s.isRule) {
        FormRow("Fees", active = s.panel == Panel.FEE, activeColor = color, onClick = { onOpen(Panel.FEE) }) {
            ValueText(feeText(s), Modifier.weight(1f))
            IconButtonBox(R.drawable.ic_close, "Remove fees", onRemoveFee, tint = Mb.colors.textTertiary)
        }
    }
}

private fun feeText(s: EditTxnState): String {
    val fee = s.fee ?: return ""
    if (fee.isEmpty) return ""
    if (s.panel == Panel.FEE) {
        val body = fee.display()
        return if (s.feeCurrency.position == SymbolPosition.START) s.feeCurrency.symbol + body else body + s.feeCurrency.symbol
    }
    return fee.minorUnits()?.let { money(it, s.feeCurrency) }.orEmpty()
}

/** The transfer's small outlined "Fees" button at the right of the Amount row (Realbyte's rb_tr). */
@Composable
private fun FeesButton(onClick: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier.clip(shape).border(1.dp, Mb.colors.textSecondary, shape).clickable(onClick = onClick).padding(horizontal = 7.dp, vertical = 6.dp),
    ) { Text("Fees", style = MbType.button, color = Mb.colors.textPrimary) }
}

private fun amountText(s: EditTxnState): String {
    if (s.amount.isEmpty) return ""
    if (s.panel == Panel.AMOUNT) {
        val body = s.amount.display()
        return if (s.currency.position == SymbolPosition.START) s.currency.symbol + body else body + s.currency.symbol
    }
    return s.amount.minorUnits()?.let { money(it, s.currency) }.orEmpty()
}

/**
 * The Date row: date and time, and at the right Realbyte's "Rep/Inst." button (capture 07) showing the current
 * choice. [onRepeat] null hides the button unless a [repeatLabel] is shown (rule mode of a plan).
 */
@Composable
internal fun DateRow(
    date: LocalDate,
    time: LocalTime,
    repeatLabel: String?,
    onDate: (() -> Unit)?,
    onTime: () -> Unit,
    onRepeat: (() -> Unit)?,
    menu: @Composable () -> Unit = {},
    showTime: Boolean = true,
) {
    FormRow("Date") {
        // The date shrinks before anything else, so time and Rep/Inst. keep their room at large fonts.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            FitText(
                Formats.editDate(date), MbType.formValue, Mb.colors.textPrimary,
                Modifier.weight(1f, fill = false).then(if (onDate != null) Modifier.clickable(onClick = onDate) else Modifier),
            )
            if (showTime) {
                Spacer(Modifier.width(18.dp))
                FitText(Formats.time(time), MbType.formValue, Mb.colors.textPrimary, Modifier.clickable(onClick = onTime))
            }
        }
        if (onRepeat != null || repeatLabel != null) {
            Box {
                RepInstButton(repeatLabel, onRepeat)
                menu()
            }
        }
    }
}

@Composable
private fun RepInstButton(label: String?, onClick: (() -> Unit)?) {
    val color = if (label != null) Mb.colors.accent else Mb.colors.iconMuted
    Column(
        Modifier.widthIn(max = 110.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MbIcon(R.drawable.ic_repeat, null, tint = color, size = 22.dp)
        FitText(label ?: "Rep/Inst.", MbType.repInst, color, maxLines = 2, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun TypeSegments(selected: TxnType, onSelect: (TxnType) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(15.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TxnType.entries.forEach { type ->
            val isSelected = type == selected
            val color = typeColor(type)
            val shape = RoundedCornerShape(4.dp)
            Box(
                Modifier.weight(1f).heightIn(min = MbDimens.segmentHeight).clip(shape)
                    .background(if (isSelected) Mb.colors.surface else Mb.colors.segmentBackground)
                    .border(1.dp, if (isSelected) color else Mb.colors.segmentBorder, shape)
                    .clickable { onSelect(type) },
                contentAlignment = Alignment.Center,
            ) {
                FitText(typeTitle(type), MbType.segment, if (isSelected) color else Mb.colors.textTertiary, Modifier.padding(horizontal = 4.dp))
            }
        }
    }
}

@Composable
private fun DescriptionField(value: String, onChange: (String) -> Unit, onFocus: () -> Unit, photos: PhotoSources? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 17.dp)) {
        Box(Modifier.fillMaxWidth()) {
            Box(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 8.dp, top = 15.dp, bottom = 5.dp, end = MbDimens.photoCameraSpace),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) Text("Description", style = MbType.formValue, color = Mb.colors.textSecondary)
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    textStyle = MbType.formValue.copy(color = Mb.colors.textPrimary),
                    cursorBrush = SolidColor(Mb.colors.accent),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocus() }
                        .semantics { contentDescription = "Description" },
                )
            }
            if (photos != null) CameraButton(photos, Modifier.matchParentSize())
        }
        Hairline()
    }
}

/** The camera at the end of Description (top aligned, 42 dp wide); it opens the Camera / Gallery menu. */
@Composable
private fun CameraButton(photos: PhotoSources, modifier: Modifier) {
    var menu by remember { mutableStateOf(false) }
    Box(modifier, contentAlignment = Alignment.TopEnd) {
        Box(
            Modifier.padding(end = MbDimens.photoCameraEnd).width(MbDimens.photoCameraWidth).fillMaxHeight().clickable { menu = true }.semantics { contentDescription = "Camera" },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.padding(top = MbDimens.photoCameraTop)) { MbIcon(R.drawable.ic_photo_camera, null, tint = Mb.colors.iconMuted, size = MbDimens.photoCameraGlyph) }
            MenuPopup(
                menu, onDismiss = { menu = false },
                items = buildList {
                    if (photos.hasCamera) add("Camera" to photos.camera)
                    add("Gallery" to photos.gallery)
                },
                width = MbDimens.photoMenuWidth, itemHeight = MbDimens.photoMenuItem, itemPadding = MbDimens.photoMenuPadding,
            )
        }
    }
}

/** What the camera button can do: take a photo, pick one, and whether this device has a camera at all. */
internal class PhotoSources(val hasCamera: Boolean, val camera: () -> Unit, val gallery: () -> Unit)

/**
 * The Camera and Gallery launchers. A result is imported into the [store] and handed to [onAdded]; "This file format is
 * unsupported." shows as a toast. The lock is told before leaving, so coming back never asks for the passcode.
 */
@Composable
private fun rememberPhotoSources(store: PhotoStore, lock: AppLock, freeSlots: () -> Int, onAdded: (String) -> Unit, begin: () -> Unit, end: () -> Unit): PhotoSources {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var capturePath by rememberSaveable { mutableStateOf<String?>(null) }
    // Photos are added one after the other so a multi-pick keeps its order; an unreadable one toasts and the rest still go in.
    fun import(opens: List<() -> InputStream?>, after: () -> Unit = {}) {
        begin() // before the launch, so a Save tapped right now already waits
        scope.launch {
            try {
                for (open in opens) {
                    try {
                        onAdded(store.import(open() ?: throw IOException("This file format is unsupported.")))
                    } catch (e: IOException) {
                        Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
                    }
                }
                after()
            } finally {
                end()
            }
        }
    }
    val take = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        lock.returned()
        val file = capturePath?.let(::File)
        capturePath = null
        if (file != null) {
            if (taken) import(listOf({ file.inputStream() }), after = { file.delete() }) else file.delete()
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        lock.returned()
        if (uri != null) import(listOf({ context.contentResolver.openInputStream(uri) }))
    }
    // Realbyte multi-picks up to the free slots; the multi contract needs a limit of at least 2, and a pick beyond the free slots is cut.
    val pickSeveral = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PHOTOS)) { uris ->
        lock.returned()
        import(uris.take(freeSlots()).map { uri -> { context.contentResolver.openInputStream(uri) } })
    }
    return PhotoSources(
        hasCamera = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY),
        camera = {
            val file = File(File(context.cacheDir, "capture").apply { mkdirs() }, "MM_${System.currentTimeMillis()}.jpg")
            capturePath = file.path
            lock.expectReturn()
            try {
                take.launch(FileProvider.getUriForFile(context, "${context.packageName}.capture", file))
            } catch (e: android.content.ActivityNotFoundException) {
                lock.returned()
                capturePath = null
            }
        },
        gallery = {
            lock.expectReturn()
            try {
                val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                if (freeSlots() >= 2) pickSeveral.launch(request) else pick.launch(request)
            } catch (e: android.content.ActivityNotFoundException) {
                lock.returned()
            }
        },
    )
}

@Composable
private fun ActionButtons(
    isNew: Boolean, dirty: Boolean, color: Color,
    onSave: () -> Unit, onContinue: () -> Unit, onDelete: () -> Unit, onCopy: () -> Unit,
    onBookmark: () -> Unit,
    isRule: Boolean = false,
    onStop: () -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, top = 35.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        when {
            isRule -> {
                FilledButton("Save", color, Modifier.weight(235f), onSave)
                OutlinedButtonBox("Delete", Modifier.weight(88f), onClick = onStop)
            }
            isNew -> {
                FilledButton("Save", color, Modifier.weight(235f), onSave)
                OutlinedButtonBox("Continue", Modifier.weight(88f), onClick = onContinue)
            }
            dirty -> {
                FilledButton("Save", color, Modifier.weight(235f), onSave)
                OutlinedButtonBox("Delete", Modifier.weight(88f), onClick = onDelete)
            }
            else -> {
                OutlinedButtonBox("Delete", Modifier.weight(1f), icon = R.drawable.ic_delete, onClick = onDelete)
                OutlinedButtonBox("Copy", Modifier.weight(1f), icon = R.drawable.ic_copy, onClick = onCopy)
                OutlinedButtonBox("Bookmark", Modifier.weight(1f), icon = R.drawable.ic_star, onClick = onBookmark)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(time: LocalTime, onPick: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    if (LocalAppLocked.current) return // a window of its own, above the lock screen
    val state = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { TimePicker(state = state) },
    )
}

/** Realbyte's autocomplete list under the Note value: matching letters in the accent color. */
@Composable
private fun NoteSuggestions(notes: List<String>, typed: String, onPick: (String) -> Unit) {
    // 88 dp = the form row's 17 dp inset + 61 dp label column + 10 dp gap, so the list starts under the value.
    Column(
        Modifier.padding(start = 88.dp, end = 13.dp).fillMaxWidth().shadow(4.dp, RoundedCornerShape(4.dp)).background(Mb.colors.surface),
    ) {
        notes.forEach { note ->
            Text(
                highlightMatches(note, typed, Mb.colors.accent), style = MbType.formValue, color = Mb.colors.textPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clickable { onPick(note) }.heightIn(min = MbDimens.suggestionRow)
                    .padding(horizontal = 12.dp).wrapContentHeight(),
            )
        }
    }
}
