package com.moneybook.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moneybook.R
import com.moneybook.data.db.TxnRow
import com.moneybook.domain.Formats
import com.moneybook.domain.localDate
import com.moneybook.shared.model.TxnType
import com.moneybook.ui.common.AmountText
import com.moneybook.ui.common.DatePickDialog
import com.moneybook.ui.common.FitText
import com.moneybook.ui.common.PhotoMark
import com.moneybook.ui.common.FormRow
import com.moneybook.ui.common.Hairline
import com.moneybook.ui.common.IconButtonBox
import com.moneybook.ui.common.LocalListOptions
import com.moneybook.ui.common.MbIcon
import com.moneybook.ui.common.MbTopBar
import com.moneybook.ui.common.SummaryBar
import com.moneybook.ui.common.SummaryColor
import com.moneybook.ui.common.SummaryItem
import com.moneybook.ui.common.TitleText
import com.moneybook.ui.common.ValueText
import com.moneybook.ui.common.amountMaxWidth
import com.moneybook.ui.common.appContainer
import com.moneybook.ui.common.categoryLabel
import com.moneybook.ui.common.money
import com.moneybook.ui.common.specOf
import com.moneybook.ui.edit.GridPickerPanel
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType
import java.time.LocalDate

/** What the Search screen's controls do; the defaults do nothing (tests). */
data class SearchActions(
    val onBack: () -> Unit = {},
    val onText: (String) -> Unit = {},
    val onTogglePanel: () -> Unit = {},
    val onRange: (LocalDate, LocalDate) -> Unit = { _, _ -> },
    val onClearRange: () -> Unit = {},
    val onAccount: (String?) -> Unit = {},
    val onCategory: (String?) -> Unit = {},
    val onMin: (String) -> Unit = {},
    val onMax: (String) -> Unit = {},
    val onOpenTxn: (String) -> Unit = {},
)

private enum class SearchPicker { ACCOUNT, CATEGORY }

private const val ALL = "all"

@Composable
fun SearchScreen(onBack: () -> Unit, onOpenTxn: (String) -> Unit) {
    val c = appContainer()
    val vm: SearchViewModel = viewModel { SearchViewModel(c.txns, c.accounts, c.categories, c.settings, c.time) }
    val s by vm.state.collectAsStateWithLifecycle()
    SearchContent(
        s,
        SearchActions(
            onBack = onBack, onText = vm::setText, onTogglePanel = vm::togglePanel, onRange = vm::setRange, onClearRange = vm::clearRange,
            onAccount = vm::setAccount, onCategory = vm::setCategory, onMin = vm::setMin, onMax = vm::setMax, onOpenTxn = onOpenTxn,
        ),
    )
}

/** Realbyte's Search (captures 21–23): the text field, the optional filter rows, then the summary and results. */
@Composable
fun SearchContent(s: SearchUiState, actions: SearchActions = SearchActions(), autoFocus: Boolean = true) {
    var picker by remember { mutableStateOf<SearchPicker?>(null) }
    /** Which end of the period is being picked: 0 = first day, 1 = last day. */
    var rangeStep by remember { mutableStateOf<Int?>(null) }
    var rangeFirst by remember { mutableStateOf<LocalDate?>(null) }
    val focus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { focus.requestFocus() }
    val colors = Mb.colors

    Column(Modifier.fillMaxSize().background(colors.surface).systemBarsPadding().imePadding()) {
        MbTopBar(
            onBack = actions.onBack,
            title = { TitleText("Search") },
            actions = {
                IconButtonBox(R.drawable.ic_filter, "Filter", actions.onTogglePanel, tint = if (s.panelOpen) colors.accent else colors.textPrimary)
            },
        )
        SearchField(s.filter.text, actions.onText, focus)
        if (s.panelOpen) {
            FormRow("Period", onClick = { rangeStep = 0 }) {
                Box(Modifier.weight(1f)) { ValueText(s.filter.range?.let { Formats.rangeWithYear(it) }) }
                if (s.filter.range != null) IconButtonBox(R.drawable.ic_close, "Clear period", actions.onClearRange, width = 36.dp, tint = colors.iconMuted, iconSize = 18.dp)
            }
            FormRow("Account", onClick = { picker = SearchPicker.ACCOUNT }) { ValueText(s.accountName()) }
            FormRow("Category", onClick = { picker = SearchPicker.CATEGORY }) { ValueText(s.categoryName()) }
            FormRow("Amount") {
                AmountField(s.minText, actions.onMin, "Min.", Modifier.weight(1f))
                Text("~", style = MbType.formValue, color = colors.textSecondary, modifier = Modifier.padding(horizontal = 8.dp))
                AmountField(s.maxText, actions.onMax, "Max.", Modifier.weight(1f))
            }
        }
        if (s.filter.isEmpty) {
            Spacer(Modifier.weight(1f))
        } else {
            Box(Modifier.fillMaxWidth().height(MbDimens.groupGap).background(colors.background))
            Hairline()
            SummaryBar(
                listOf(
                    SummaryItem("Income", money(s.totals.income, s.currency), SummaryColor.INCOME),
                    SummaryItem("Expenses", money(s.totals.expense, s.currency), SummaryColor.EXPENSE),
                    SummaryItem("Transfer", money(s.totals.transfer, s.currency), SummaryColor.BODY),
                ),
            )
            LazyColumn(Modifier.weight(1f)) {
                items(s.results, key = { it.id }) { row ->
                    SearchRow(row) { actions.onOpenTxn(row.id) }
                    Hairline()
                }
            }
        }
        when (picker) {
            SearchPicker.ACCOUNT -> GridPickerPanel(
                "Accounts", listOf(ALL to "All") + s.accounts,
                onPick = { id -> actions.onAccount(id.takeIf { it != ALL }); picker = null }, onClose = { picker = null },
            )
            SearchPicker.CATEGORY -> GridPickerPanel(
                "Category", listOf(ALL to "All") + s.categories,
                onPick = { id -> actions.onCategory(id.takeIf { it != ALL }); picker = null }, onClose = { picker = null },
            )
            null -> Unit
        }
    }

    rangeStep?.let { step ->
        DatePickDialog(
            if (step == 0) s.filter.range?.start ?: s.today else rangeFirst ?: s.today,
            onPick = { day ->
                if (step == 0) {
                    rangeFirst = day
                    rangeStep = 1
                } else {
                    actions.onRange(rangeFirst ?: day, day)
                    rangeStep = null
                }
            },
            onDismiss = { rangeStep = null },
        )
    }
}

@Composable
private fun SearchField(text: String, onText: (String) -> Unit, focus: FocusRequester) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.padding(horizontal = 13.dp, vertical = 10.dp).fillMaxWidth().heightIn(min = MbDimens.searchField).height(IntrinsicSize.Min).clip(shape)
            .background(Mb.colors.searchField).border(1.dp, Mb.colors.segmentBorder, shape).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MbIcon(R.drawable.ic_search, null, tint = Mb.colors.textBody, size = 22.dp)
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = text, onValueChange = onText, singleLine = true,
            textStyle = MbType.searchText.copy(color = Mb.colors.textPrimary), cursorBrush = SolidColor(Mb.colors.accent),
            modifier = Modifier.weight(1f).focusRequester(focus).semantics { contentDescription = "Search text" },
        )
        if (text.isNotEmpty()) IconButtonBox(R.drawable.ic_close, "Clear", { onText("") }, width = 40.dp, tint = Mb.colors.iconMuted, iconSize = 20.dp)
    }
}

@Composable
private fun AmountField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (value.isEmpty()) Text(placeholder, style = MbType.formValue, color = Mb.colors.textSecondary)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = MbType.formValue.copy(color = Mb.colors.textPrimary, textAlign = TextAlign.Center),
            cursorBrush = SolidColor(Mb.colors.accent),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder },
        )
    }
}

/** A result: date over category, note over account, amount in the type's color (capture 22). */
@Composable
internal fun SearchRow(row: TxnRow, onClick: () -> Unit) {
    val colors = Mb.colors
    val transfer = row.type == TxnType.TRANSFER
    val amountColor = when (row.type) {
        TxnType.INCOME -> colors.income
        TxnType.EXPENSE -> colors.expense
        TxnType.TRANSFER -> colors.textBody
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = MbDimens.searchRow).background(colors.surface).clickable(onClick = onClick)
            .padding(horizontal = MbDimens.sidePadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(MbDimens.searchDateColumn)) {
            FitText(Formats.isoDate(row.localDate()), MbType.rowAccount, colors.textSecondary)
            Text(
                if (transfer) "Transfer" else row.categoryLabel(LocalListOptions.current.subcategory), style = MbType.rowCategory, color = colors.textSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
            if (row.note.isNotEmpty()) Text(row.note, style = MbType.rowNote, color = colors.textBody, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (transfer) "${row.accountName} → ${row.toAccountName.orEmpty()}" else row.accountName, Modifier.weight(1f, fill = false),
                    style = MbType.rowAccount, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (row.hasPhoto) PhotoMark(Modifier.padding(start = 2.dp))
            }
        }
        AmountText(row.amount, specOf(row.currency), amountColor, Modifier.widthIn(max = amountMaxWidth()))
    }
}
