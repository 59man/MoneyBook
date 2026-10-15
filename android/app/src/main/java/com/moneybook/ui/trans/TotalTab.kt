package com.moneybook.ui.trans

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.moneybook.domain.BudgetLine
import com.moneybook.ui.common.Hairline
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.moneybook.R
import com.moneybook.domain.DateRange
import com.moneybook.domain.Formats
import com.moneybook.domain.TotalBreakdown
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.ui.common.FitText
import com.moneybook.ui.common.MbIcon
import com.moneybook.ui.common.money
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType

/** Trans → Total (`rb-total1`): the Budget section, then the Accounts card for the month, then [exportButton]. */
@Composable
fun TotalTab(
    breakdown: TotalBreakdown?, range: DateRange?, spec: CurrencySpec, budgets: List<BudgetLine>, budgetToday: Float?,
    onBudgetSetting: () -> Unit, onBudget: (BudgetLine) -> Unit, exportButton: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Mb.colors.background).verticalScroll(rememberScrollState())) {
        BudgetSection(budgets, budgetToday, spec, onBudgetSetting, onBudget)
        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth().background(Mb.colors.surface).padding(horizontal = MbDimens.totalInset, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = MbDimens.listRow), verticalAlignment = Alignment.CenterVertically) {
                MbIcon(R.drawable.ic_nav_accounts, null, tint = Mb.colors.textBody, size = 22.dp)
                Spacer(Modifier.width(12.dp))
                Text("Accounts", style = MbType.sectionTitle, color = Mb.colors.textPrimary)
                Spacer(Modifier.weight(1f))
                if (range != null) FitText(Formats.rangeWithYear(range), MbType.totalPeriod, Mb.colors.textTertiary)
            }
            Spacer(Modifier.height(3.dp))
            val shape = RoundedCornerShape(6.dp)
            Column(Modifier.fillMaxWidth().border(1.dp, Mb.colors.divider, shape).padding(horizontal = 18.dp, vertical = 12.5.dp)) {
                TotalLine("Compared Expenses (Last month)", breakdown?.comparedPercent?.let { "$it%" } ?: "-")
                TotalLine("Expenses (Cash, Accounts)", money(breakdown?.nonCardExpenses ?: 0, spec))
                TotalLine("Expenses (Card)", money(breakdown?.cardExpenses ?: 0, spec))
                TotalLine("Transfer (Cash, Accounts→ )", money(breakdown?.nonCardTransfers ?: 0, spec))
            }
            Spacer(Modifier.height(15.dp))
            exportButton()
            Spacer(Modifier.height(MbDimens.totalBottom))
        }
    }
}

@Composable
private fun TotalLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = MbDimens.totalLine), verticalAlignment = Alignment.CenterVertically) {
        FitText(label, MbType.formLabel, Mb.colors.textTertiary, Modifier.weight(1f), maxLines = 2)
        FitText(value, MbType.formLabel, Mb.colors.textPrimary)
    }
}

/** Trans → Total's Budget section (Realbyte header_budget_fragment + main_tab_item_budget, `rb-total1`). */
@Composable
private fun BudgetSection(lines: List<BudgetLine>, today: Float?, spec: CurrencySpec, onSetting: () -> Unit, onLine: (BudgetLine) -> Unit) {
    val colors = Mb.colors
    Column(Modifier.fillMaxWidth().background(colors.surface)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = MbDimens.listRow).padding(horizontal = MbDimens.totalInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MbIcon(R.drawable.ic_budget, null, tint = colors.textBody, size = 22.dp)
            Spacer(Modifier.width(12.dp))
            FitText("Budget", MbType.sectionTitle, colors.textPrimary, Modifier.weight(1f))
            Row(
                Modifier.weight(1f, fill = false).clip(RoundedCornerShape(5.dp)).background(colors.background).clickable(onClick = onSetting)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FitText("Budget Setting", MbType.formLabel, colors.textSecondary, Modifier.weight(1f, fill = false))
                MbIcon(R.drawable.ic_chevron_right, null, tint = colors.textSecondary, size = 16.dp)
            }
        }
        if (lines.isEmpty()) return@Column
        if (today != null) TodayBubble(today)
        lines.forEach { line ->
            BudgetRow(line, today, spec) { onLine(line) }
            Hairline()
        }
    }
}

/** Left column 4.7 %–34.7 % of the width, bar 34.7 %–95.8 % (Realbyte's guidelines). */
private const val BAR_START = 0.347f
private const val SIDE_START = 0.047f
private const val BAR_END = 0.958f

@Composable
private fun TodayBubble(fraction: Float) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        val barStart = maxWidth * BAR_START
        val barWidth = maxWidth * (BAR_END - BAR_START)
        Column(
            Modifier.offset(x = (barStart + barWidth * fraction - 30.dp).coerceIn(0.dp, maxWidth - 60.dp)).width(60.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FitText(
                "Today", MbType.budgetBubble, Color.White,
                Modifier.clip(RoundedCornerShape(9.dp)).background(Mb.colors.todayBubble).padding(horizontal = 6.dp, vertical = 3.dp),
            )
            MbIcon(R.drawable.ic_expand, null, tint = Mb.colors.todayBubble, size = 14.dp)
        }
    }
}

@Composable
private fun BudgetRow(line: BudgetLine, today: Float?, spec: CurrencySpec, onClick: () -> Unit) {
    val colors = Mb.colors
    BoxWithConstraints(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        val w = maxWidth
        Row(Modifier.fillMaxWidth().padding(start = w * SIDE_START, end = w * (1 - BAR_END), top = 9.dp, bottom = 13.dp)) {
            Column(Modifier.width(w * (BAR_START - SIDE_START)).padding(end = 8.dp)) {
                Spacer(Modifier.height(MbDimens.budgetBarHeight - 13.dp))
                FitText(line.name, MbType.budgetName, colors.textSecondary, maxLines = 2)
                FitText(money(line.amount, spec), MbType.budgetAmount, colors.textPrimary)
            }
            Column(Modifier.weight(1f)) {
                BudgetBar(line, today)
                Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                    val usedText = money(line.used, spec).let { if (it.contains("-")) "($it)" else it }
                    FitText(usedText, MbType.budgetAmount, if (line.over) colors.budgetOver else colors.budgetBar, Modifier.weight(1f))
                    FitText(
                        (if (line.over) "Excess " else "") + money(line.remaining, spec), MbType.budgetAmount, colors.textPrimary,
                        Modifier.weight(1f), textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

@Composable
private fun BudgetBar(line: BudgetLine, today: Float?) {
    val colors = Mb.colors
    val shape = RoundedCornerShape(MbDimens.budgetBarRadius)
    val fill = if (line.amount <= 0) 1f else (line.used.toFloat() / line.amount).coerceIn(0f, 1f)
    BoxWithConstraints(Modifier.fillMaxWidth().height(MbDimens.budgetBarHeight).clip(shape).background(colors.background)) {
        if (fill > 0f) {
            Box(Modifier.fillMaxHeight().width(maxWidth * fill).background(if (line.over) colors.budgetOver else colors.budgetBar))
        }
        if (today != null) Box(Modifier.offset(x = maxWidth * today).width(1.dp).fillMaxHeight().background(colors.budgetTodayLine))
        FitText(
            line.rate?.let { "$it%" } ?: "-", MbType.budgetAmount, if (line.over) Color.White else colors.textPrimary,
            Modifier.align(Alignment.CenterEnd).padding(start = 4.dp, end = 10.dp),
        )
    }
}
