package com.moneybook.ui.trans

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.moneybook.R
import com.moneybook.domain.FilterOptions
import com.moneybook.domain.TxnFilter
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.ui.common.FitText
import com.moneybook.ui.common.Hairline
import com.moneybook.ui.common.IconButtonBox
import com.moneybook.ui.common.MbIcon
import com.moneybook.ui.common.SubTabs
import com.moneybook.ui.common.money
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType

enum class FilterTab(val label: String) { INCOME("INCOME"), EXPENSES("EXPENSES"), ACCOUNT("ACCOUNT") }

/** What the filter panel's controls do; the defaults do nothing (tests). */
data class FilterActions(
    val onPrevious: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onClose: () -> Unit = {},
    val onToggleCategory: (String) -> Unit = {},
    val onToggleAccount: (String) -> Unit = {},
    /** A tab's "All" row: tick ([Boolean] true) or untick every line of that tab. */
    val onAll: (FilterTab, Boolean) -> Unit = { _, _ -> },
)

/** Realbyte's Trans filter (captures 24–25): navy header, two rings, then INCOME | EXPENSES | ACCOUNT lists. */
@Composable
fun TransFilterPanel(title: String, options: FilterOptions, filter: TxnFilter, spec: CurrencySpec, actions: FilterActions = FilterActions()) {
    var tab by rememberSaveable { mutableStateOf(FilterTab.INCOME) }
    val colors = Mb.colors
    // The panel covers the Trans tab; the empty pointerInput keeps taps from reaching the list behind it.
    Column(Modifier.fillMaxSize().background(colors.surface).pointerInput(Unit) {}) {
        Row(Modifier.fillMaxWidth().height(MbDimens.topBar).background(colors.filterBar), verticalAlignment = Alignment.CenterVertically) {
            IconButtonBox(R.drawable.ic_chevron_left, "Previous", actions.onPrevious, width = MbDimens.monthArrow, tint = Color.White, iconSize = MbDimens.monthArrowIcon)
            Text(title, style = MbType.topTitle, color = Color.White)
            IconButtonBox(R.drawable.ic_chevron_right, "Next", actions.onNext, width = MbDimens.monthArrow, tint = Color.White, iconSize = MbDimens.monthArrowIcon)
            Spacer(Modifier.weight(1f))
            IconButtonBox(R.drawable.ic_close, "Close filter", actions.onClose, tint = Color.White)
        }
        Text(
            "Select items that you want to filter.", style = MbType.listItem, color = Color.White,
            modifier = Modifier.fillMaxWidth().background(colors.filterBand).heightIn(min = MbDimens.filterBand)
                .padding(horizontal = 23.dp).wrapContentHeight(),
        )
        Row(Modifier.fillMaxWidth().heightIn(min = MbDimens.filterRings).padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterRing("Income", options.incomePercent, money(options.month.income, spec), colors.ringIncome, colors.income, Modifier.weight(1f))
            FilterRing("Expenses", options.expensePercent, money(options.month.expense, spec), colors.ringExpense, colors.expense, Modifier.weight(1f))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                FitText("Total", MbType.summaryLabel, colors.textBody)
                FitText(money(options.month.total, spec), MbType.ringAmount, colors.textPrimary)
            }
        }
        SubTabs(FilterTab.entries.map { it.label }, tab.ordinal) { tab = FilterTab.entries[it] }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                FilterTab.INCOME, FilterTab.EXPENSES -> {
                    val lines = if (tab == FilterTab.INCOME) options.income else options.expense
                    val allTicked = lines.isNotEmpty() && lines.all { it.id in filter.categories }
                    item(key = "all") {
                        CheckRow("All", allTicked, { actions.onAll(tab, !allTicked) }) {
                            Text("This month", style = MbType.listItem, color = colors.textSecondary)
                        }
                    }
                    items(lines, key = { it.id }) { line ->
                        CheckRow(line.name, line.id in filter.categories, { actions.onToggleCategory(line.id) }) {
                            if (line.amount != 0L) FitText(money(line.amount, spec), MbType.listItem, colors.textPrimary)
                        }
                    }
                }
                FilterTab.ACCOUNT -> {
                    val ids = options.groups.flatMap { g -> g.accounts.map { it.id } }
                    val allTicked = ids.isNotEmpty() && ids.all { it in filter.accounts }
                    item(key = "all") {
                        CheckRow("All", allTicked, { actions.onAll(FilterTab.ACCOUNT, !allTicked) }) {
                            TwoLine("Income", colors.income, "Transfer-In")
                            Spacer(Modifier.width(16.dp))
                            TwoLine("Expenses", colors.expense, "Transfer-Out")
                        }
                    }
                    options.groups.forEach { group ->
                        item(key = "group-${group.name}") { GroupBand(group.name) }
                        items(group.accounts, key = { it.id }) { a ->
                            CheckRow(a.name, a.id in filter.accounts, { actions.onToggleAccount(a.id) }) {
                                FitText(money(a.incoming, spec), MbType.listItem, colors.income)
                                Spacer(Modifier.width(16.dp))
                                FitText(money(a.outgoing, spec), MbType.listItem, colors.expense)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A ring in [track] color; the ticked share is drawn over it in [fill], clockwise from 12 o'clock. */
@Composable
private fun FilterRing(label: String, percent: Long, amount: String, track: Color, fill: Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        FitText(label, MbType.summaryLabel, Mb.colors.textBody)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.size(MbDimens.ring), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(MbDimens.ring)) {
                val stroke = MbDimens.ringStroke.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                val topLeft = Offset(inset, inset)
                drawArc(track, 0f, 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke))
                if (percent > 0) drawArc(fill, -90f, percent * 3.6f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke))
            }
            Text("$percent%", style = MbType.ringPercent, color = Mb.colors.textPrimary)
        }
        Spacer(Modifier.height(4.dp))
        FitText(amount, MbType.ringAmount, Mb.colors.textPrimary)
    }
}

/** A line with a checkbox, a name and [trailing] content on the right; the whole line toggles. */
@Composable
private fun CheckRow(name: String, checked: Boolean, onToggle: () -> Unit, trailing: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Mb.colors.surface).clickable(onClick = onToggle)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = MbDimens.listRow).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterCheck(checked)
            Spacer(Modifier.width(14.dp))
            FitText(name, MbType.listItem, Mb.colors.textPrimary, Modifier.weight(1f), maxLines = 2)
            // Name and amounts share the width, so neither squeezes the other away at large fonts.
            Row(Modifier.weight(1f, fill = false), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                trailing()
            }
        }
        Hairline()
    }
}

/** Realbyte's square checkbox: a gray outline, filled with the accent and a white check when ticked. */
@Composable
fun FilterCheck(checked: Boolean) {
    val shape = RoundedCornerShape(3.dp)
    Box(
        Modifier.size(MbDimens.checkBox).clip(shape)
            .background(if (checked) Mb.colors.accent else Mb.colors.surface)
            .border(2.dp, if (checked) Mb.colors.accent else Mb.colors.divider, shape)
            .semantics { contentDescription = if (checked) "Ticked" else "Not ticked" },
        contentAlignment = Alignment.Center,
    ) {
        if (checked) MbIcon(R.drawable.ic_check, null, tint = Color.White, size = 16.dp)
    }
}

@Composable
private fun TwoLine(top: String, topColor: Color, bottom: String) {
    Column(horizontalAlignment = Alignment.End) {
        Text(top, style = MbType.summaryLabel, color = topColor, textAlign = TextAlign.End)
        Text(bottom, style = MbType.summaryLabel, color = Mb.colors.textBody, textAlign = TextAlign.End)
    }
}

@Composable
private fun GroupBand(name: String) {
    Column(Modifier.fillMaxWidth().background(Mb.colors.background)) {
        Text(
            name, style = MbType.listItem, color = Mb.colors.textTertiary,
            modifier = Modifier.fillMaxWidth().heightIn(min = MbDimens.listRow).padding(start = 20.dp, top = 12.dp),
        )
        Hairline()
    }
}
