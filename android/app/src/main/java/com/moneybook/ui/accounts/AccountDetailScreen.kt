package com.moneybook.ui.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import com.moneybook.ui.common.ConfirmDialog
import com.moneybook.ui.common.MessageDialog
import com.moneybook.ui.common.OutlinedButtonBox
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moneybook.R
import com.moneybook.domain.Formats
import com.moneybook.domain.Totals
import com.moneybook.ui.common.AddFab
import com.moneybook.ui.common.AmountText
import com.moneybook.ui.common.FitText
import com.moneybook.ui.common.Hairline
import com.moneybook.ui.common.IconButtonBox
import com.moneybook.ui.common.MbTopBar
import com.moneybook.ui.common.MonthNavigator
import com.moneybook.ui.common.SubTabs
import com.moneybook.ui.common.SummaryBar
import com.moneybook.ui.common.SummaryColor
import com.moneybook.ui.common.SummaryItem
import com.moneybook.ui.common.TitleText
import com.moneybook.ui.common.appContainer
import com.moneybook.ui.common.charts.BarChart
import com.moneybook.ui.common.charts.LineChart
import com.moneybook.ui.common.money
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType
import com.moneybook.ui.trans.DailyTab
import kotlin.math.abs

@Composable
fun AccountDetailScreen(
    accountId: String,
    onBack: () -> Unit,
    onOpenTxn: (String) -> Unit,
    onEdit: (() -> Unit)?,
    onAdd: (() -> Unit)? = null,
    onPay: ((PayPreset) -> Unit)? = null,
    /** Deleted accounts: the same screen without +, edit and Pay, with Restore in the Statement block. */
    readOnly: Boolean = false,
) {
    val c = appContainer()
    val vm: AccountDetailViewModel = viewModel { AccountDetailViewModel(accountId, c.txns, c.accounts, c.settings, c.time) }
    val s by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(RestoreStep.NONE) }
    AccountDetailContent(
        s, onBack, onOpenTxn, if (readOnly) null else onEdit, if (readOnly) null else onAdd, if (readOnly) null else onPay,
        DetailActions(vm::selectTab, vm::previous, vm::next, vm::toggleChart, vm::open),
        onRestore = if (readOnly) ({ step = RestoreStep.ASK }) else null,
    )
    if (step == RestoreStep.ASK) {
        ConfirmDialog(
            "Restore", "Do you want to restore?", "YES", "NO",
            onConfirm = { scope.launch { vm.restore(); step = RestoreStep.DONE } }, onDismiss = { step = RestoreStep.NONE },
        )
    }
    if (step == RestoreStep.DONE) MessageDialog("Restore", "Restore completed.", onDismiss = onBack)
}

private enum class RestoreStep { NONE, ASK, DONE }

/** What the screen's controls do; the defaults do nothing (tests). */
data class DetailActions(
    val onTab: (DetailTab) -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onToggleChart: () -> Unit = {},
    val onOpenLine: (DetailLine) -> Unit = {},
)

@Composable
fun AccountDetailContent(
    s: AccountDetailState,
    onBack: () -> Unit,
    onOpenTxn: (String) -> Unit,
    onEdit: (() -> Unit)?,
    onAdd: (() -> Unit)?,
    onPay: ((PayPreset) -> Unit)?,
    actions: DetailActions = DetailActions(),
    onRestore: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxSize().background(Mb.colors.surface).systemBarsPadding()) {
        MbTopBar(
            onBack = onBack,
            title = { TitleText(s.name) },
            actions = { if (s.tab != DetailTab.ANNUALLY) MonthNavigator(s.title, actions.onPrevious, actions.onNext) },
        )
        SubTabs(DetailTab.entries.map { it.label }, s.tab.ordinal) { actions.onTab(DetailTab.entries[it]) }
        StatementBlock(s, onEdit, onRestore, actions.onToggleChart)
        Hairline()
        SummaryBar(listOf(
            SummaryItem("Deposit", money(s.summary.deposit, s.currency, withSymbol = false), SummaryColor.INCOME),
            SummaryItem("Withdrawal", money(s.summary.withdrawal, s.currency, withSymbol = false), SummaryColor.EXPENSE),
            SummaryItem("Total", money(s.summary.total, s.currency, withSymbol = false), SummaryColor.BODY),
            SummaryItem(
                "Balance", money(abs(s.balance), s.currency, withSymbol = false),
                when {
                    s.balance > 0 -> SummaryColor.INCOME
                    s.balance < 0 -> SummaryColor.EXPENSE
                    else -> SummaryColor.BODY
                },
            ),
        ))
        Hairline()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                s.showChart -> DetailCharts(s)
                s.tab == DetailTab.DAILY -> DailyTab(s.dayGroups, s.currency, onOpenTxn, s.balances)
                else -> LazyColumn(Modifier.fillMaxSize().background(Mb.colors.background), contentPadding = PaddingValues(bottom = 88.dp)) {
                    items(s.lines, key = { it.title }) { line ->
                        DetailLineRow(line, s, highlighted = line.line.range.contains(s.today), onClick = { actions.onOpenLine(line) })
                    }
                }
            }
            Row(Modifier.align(Alignment.BottomEnd).padding(MbDimens.fabMargin), verticalAlignment = Alignment.CenterVertically) {
                val pay = s.pay
                if (onPay != null && pay != null) {
                    PayFab { onPay(pay) }
                    Spacer(Modifier.width(12.dp))
                }
                if (onAdd != null) AddFab(onAdd)
            }
        }
    }
}

/** "Statement" over the period ("9.20.26  ~  10.19.26"), then the chart/list switch and the edit pencil. */
@Composable
private fun StatementBlock(s: AccountDetailState, onEdit: (() -> Unit)?, onRestore: (() -> Unit)?, onToggleChart: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = MbDimens.statementBlock).padding(start = 17.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            FitText("Statement", MbType.summaryLabel, Mb.colors.textSecondary)
            s.period?.let { FitText(Formats.rangeWithYear(it), MbType.statementPeriod, Mb.colors.textPrimary) }
        }
        IconButtonBox(if (s.showChart) R.drawable.ic_list else R.drawable.ic_nav_stats, if (s.showChart) "List" else "Chart", onToggleChart)
        if (onEdit != null) IconButtonBox(R.drawable.ic_edit, "Edit", onEdit)
        if (onRestore != null) OutlinedButtonBox("Restore", Modifier.padding(horizontal = 6.dp), onClick = onRestore)
    }
}

/** A Monthly or Annually row: the title over its period; Deposit over Total; Withdrawal over (Balance). */
@Composable
private fun DetailLineRow(d: DetailLine, s: AccountDetailState, highlighted: Boolean, onClick: () -> Unit) {
    val l = d.line
    val sub = if (d.month != null) Formats.rangeShort(l.range) else Formats.rangeWithYear(l.range)
    Column(Modifier.background(if (highlighted) Mb.colors.currentWeek else Mb.colors.surface).clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = MbDimens.monthRow).padding(start = MbDimens.sidePadding, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                FitText(d.title, MbType.monthName, Mb.colors.textBody)
                FitText(sub, MbType.monthRange, Mb.colors.textTertiary)
            }
            Column(Modifier.width(MbDimens.monthlyColumn), horizontalAlignment = Alignment.End) {
                AmountText(l.deposit, s.currency, Mb.colors.income, style = MbType.monthlyAmount)
                AmountText(l.total, s.currency, Mb.colors.textSecondary, style = MbType.monthlyTotal)
            }
            Column(Modifier.width(MbDimens.monthlyColumn), horizontalAlignment = Alignment.End) {
                AmountText(l.withdrawal, s.currency, Mb.colors.expense, style = MbType.monthlyAmount)
                FitText("(${money(l.endBalance, s.currency)})", MbType.monthlyTotal, Mb.colors.textSecondary, textAlign = TextAlign.End)
            }
        }
        Hairline()
    }
}

/** The chart view: the balance at the end of each of the last six months, then Deposit / Withdrawal bars. */
@Composable
private fun DetailCharts(s: AccountDetailState) {
    Column(Modifier.fillMaxSize().background(Mb.colors.background).verticalScroll(rememberScrollState())) {
        if (s.chart.isEmpty()) return@Column
        val labels = s.chart.map { it.title }
        val balances = s.chart.map { it.line.endBalance }
        LineChart(balances, labels, s.currency.decimals, maxLines = 4, valueTexts = balances.map { money(it, s.currency, withSymbol = false) })
        Hairline(px = 2)
        BarChart(s.chart.map { Totals(it.line.deposit, it.line.withdrawal) }, labels, s.currency)
        Hairline()
    }
}

/** Card detail's Pay: a white circle with an accent outline and accent text, beside +. */
@Composable
private fun PayFab(onClick: () -> Unit) {
    Box(
        Modifier.size(MbDimens.fab).shadow(6.dp, CircleShape).clip(CircleShape).background(Mb.colors.surface)
            .border(1.dp, Mb.colors.accent, CircleShape).clickable(onClick = onClick).semantics { contentDescription = "Pay" },
        contentAlignment = Alignment.Center,
    ) {
        FitText("Pay", MbType.fabLabel, Mb.colors.accent)
    }
}
