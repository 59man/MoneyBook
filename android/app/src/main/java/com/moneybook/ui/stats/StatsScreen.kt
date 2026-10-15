package com.moneybook.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moneybook.R
import com.moneybook.domain.CategorySlice
import com.moneybook.domain.Formats
import com.moneybook.domain.StatsMode
import com.moneybook.domain.StatsPeriod
import com.moneybook.domain.Totals
import com.moneybook.shared.model.TxnType
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.ui.common.AmountText
import com.moneybook.ui.common.DatePickDialog
import com.moneybook.ui.common.EmptyState
import com.moneybook.ui.common.FitText
import com.moneybook.ui.common.Hairline
import com.moneybook.ui.common.MbIcon
import com.moneybook.ui.common.MbTopBar
import com.moneybook.ui.common.MenuPopup
import com.moneybook.ui.common.MonthNavigator
import com.moneybook.ui.common.amountMaxWidth
import com.moneybook.ui.common.appContainer
import com.moneybook.ui.common.charts.PieChart
import com.moneybook.ui.common.charts.PieEntry
import com.moneybook.ui.common.money
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType
import java.time.LocalDate

/** What the Stats screen's controls do; the defaults do nothing (tests, previews). */
data class StatsActions(
    val onPrevious: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onMode: (StatsMode) -> Unit = {},
    val onType: (TxnType) -> Unit = {},
    val onCustom: (LocalDate, LocalDate) -> Unit = { _, _ -> },
    val onOpenSlice: (CategorySlice) -> Unit = {},
)

@Composable
fun StatsScreen(onOpenCategory: (CategorySlice, TxnType, StatsPeriod) -> Unit) {
    val c = appContainer()
    val vm: StatsViewModel = viewModel { StatsViewModel(c.txns, c.categories, c.settings, c.time) }
    val s by vm.state.collectAsStateWithLifecycle()
    StatsContent(
        s,
        StatsActions(vm::previous, vm::next, vm::selectMode, vm::selectType, vm::setCustom) { slice -> onOpenCategory(slice, s.type, s.period) },
    )
}

@Composable
fun StatsContent(s: StatsUiState, actions: StatsActions = StatsActions()) {
    var menuOpen by remember { mutableStateOf(false) }
    /** Which end of a custom period is being picked: 0 = first day, 1 = last day. */
    var picking by remember { mutableStateOf<Int?>(null) }
    val period = s.period
    Column(Modifier.fillMaxSize().background(Mb.colors.background)) {
        MbTopBar(
            titleInset = 0.dp,
            title = {
                when {
                    period is StatsPeriod.Custom -> PeriodChips(period) { picking = it }
                    period.movable -> MonthNavigator(period.title(), actions.onPrevious, actions.onNext)
                    else -> Text(period.title(), style = MbType.topTitle, color = Mb.colors.textPrimary, modifier = Modifier.padding(start = MbDimens.sidePadding))
                }
            },
            actions = {
                Box(Modifier.padding(end = MbDimens.sidePadding)) {
                    ModeButton(period.mode.label) { menuOpen = true }
                    MenuPopup(menuOpen, onDismiss = { menuOpen = false }, items = StatsMode.entries.map { m -> m.label to { actions.onMode(m) } })
                }
            },
        )
        Hairline(px = 2)
        StatsTypeTabs(s.totals, s.currency, s.type, actions.onType)
        if (s.slices.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().background(Mb.colors.surface)) { EmptyState() }
        } else {
            val palette = Mb.colors.chartPalette
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                item(key = "pie") { SlicePie(s.slices) }
                itemsIndexed(s.slices, key = { _, slice -> slice.key }) { i, slice ->
                    SliceRow(slice, palette[i % palette.size], s.currency) { actions.onOpenSlice(slice) }
                }
            }
        }
    }
    if (period is StatsPeriod.Custom) {
        picking?.let { end ->
            DatePickDialog(
                if (end == 0) period.first else period.last,
                onPick = { day ->
                    picking = null
                    if (end == 0) actions.onCustom(day, period.last) else actions.onCustom(period.first, day)
                },
                onDismiss = { picking = null },
            )
        }
    }
}

/** The pie of [slices] (same colors as their list rows) and the gap under it. */
@Composable
internal fun SlicePie(slices: List<CategorySlice>) {
    val palette = Mb.colors.chartPalette
    Column(Modifier.background(Mb.colors.surface)) {
        PieChart(
            slices.mapIndexedNotNull { i, slice ->
                if (slice.share > 0) PieEntry(slice.name, slice.percentLabel, slice.share, palette[i % palette.size]) else null
            },
        )
        Hairline()
        Spacer(Modifier.fillMaxWidth().height(MbDimens.groupGap).background(Mb.colors.background))
        Hairline()
    }
}

/** Category detail with Subcategory ON: the pie and list of the subcategories. */
@Composable
internal fun SubcategoryBreakdown(slices: List<CategorySlice>, spec: CurrencySpec) {
    val palette = Mb.colors.chartPalette
    Column {
        SlicePie(slices)
        slices.forEachIndexed { i, slice -> SliceRow(slice, palette[i % palette.size], spec) }
        Spacer(Modifier.fillMaxWidth().height(MbDimens.groupGap).background(Mb.colors.background))
    }
}

/** "Income 2,500.00Kč | Expenses 400.00Kč": the selected tab's text is dark with a bold amount and an accent bar. */
@Composable
private fun StatsTypeTabs(totals: Totals, spec: CurrencySpec, selected: TxnType, onSelect: (TxnType) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Mb.colors.surface)) {
        Row(Modifier.fillMaxWidth()) {
            listOf(TxnType.INCOME to totals.income, TxnType.EXPENSE to totals.expense).forEach { (type, amount) ->
                val on = type == selected
                val color = if (on) Mb.colors.textPrimary else Mb.colors.tabUnselected
                Box(Modifier.weight(1f).heightIn(min = MbDimens.subTabRow).clickable { onSelect(type) }, contentAlignment = Alignment.Center) {
                    Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        FitText(if (type == TxnType.INCOME) "Income" else "Expenses", MbType.subTab, color, Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(6.dp))
                        FitText(money(amount, spec), if (on) MbType.statsTabAmount else MbType.subTab, color, Modifier.weight(1f, fill = false))
                    }
                    if (on) {
                        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(MbDimens.tabIndicator).background(Mb.colors.accent))
                    }
                }
            }
        }
        Hairline(px = 2)
    }
}

@Composable
private fun SliceRow(slice: CategorySlice, color: Color, spec: CurrencySpec, onClick: (() -> Unit)? = null) {
    Column(Modifier.background(Mb.colors.surface).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = MbDimens.statsRow).padding(horizontal = MbDimens.sidePadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(MbDimens.percentChipWidth, MbDimens.percentChipHeight).clip(RoundedCornerShape(3.dp)).background(color),
                contentAlignment = Alignment.Center,
            ) {
                FitText(slice.percentChip, MbType.percentChip, Color.White, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.width(12.dp))
            FitText(slice.name, MbType.listItem, Mb.colors.textPrimary, Modifier.weight(1f), maxLines = 2)
            AmountText(slice.amount, spec, Mb.colors.textPrimary, Modifier.widthIn(max = amountMaxWidth()))
        }
        Hairline()
    }
}

/** The outlined "Monthly ⌄" button that opens the period menu. */
@Composable
private fun ModeButton(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.heightIn(min = MbDimens.segmentHeight).clip(shape).border(1.dp, Mb.colors.segmentBorder, shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FitText(label, MbType.listItem, Mb.colors.textPrimary)
        Spacer(Modifier.width(4.dp))
        MbIcon(R.drawable.ic_expand, null, tint = Mb.colors.textSecondary, size = 16.dp)
    }
}

/** Period mode's title: `1/1/26 📅 ~ 12/31/26 📅`; tapping a date picks that end (0 = first, 1 = last). */
@Composable
private fun PeriodChips(period: StatsPeriod.Custom, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxHeight().padding(start = MbDimens.sidePadding), verticalAlignment = Alignment.CenterVertically) {
        DateChip(Formats.shortDate(period.first), "Change the first day", Modifier.weight(1f, fill = false)) { onPick(0) }
        Text("~", style = MbType.topTitle, color = Mb.colors.textPrimary, modifier = Modifier.padding(horizontal = 8.dp))
        DateChip(Formats.shortDate(period.last), "Change the last day", Modifier.weight(1f, fill = false)) { onPick(1) }
    }
}

@Composable
private fun DateChip(text: String, description: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxHeight().clickable(onClick = onClick).semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FitText(text, MbType.topTitle, Mb.colors.textPrimary, Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(4.dp))
        MbIcon(R.drawable.ic_calendar, null, tint = Mb.colors.textSecondary, size = 18.dp)
    }
}
