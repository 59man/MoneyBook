package com.moneybook.ui.trans

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.moneybook.domain.Formats
import com.moneybook.domain.MonthRow
import com.moneybook.domain.Totals
import com.moneybook.domain.WeekRow
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.ui.common.AmountText
import com.moneybook.ui.common.FitText
import com.moneybook.ui.common.Hairline
import com.moneybook.ui.common.money
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType
import java.time.YearMonth

@Composable
fun MonthlyTab(months: List<MonthRow>, spec: CurrencySpec, onToggle: (YearMonth) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().background(Mb.colors.background), contentPadding = PaddingValues(bottom = 88.dp)) {
        months.forEach { m ->
            item(key = m.month.toString()) { MonthRowView(m, spec) { onToggle(m.month) } }
            items(m.weeks, key = { "${m.month}-${it.range.start}" }) { w -> WeekRowView(w, spec) }
        }
    }
}

@Composable
private fun MonthRowView(m: MonthRow, spec: CurrencySpec, onClick: () -> Unit) {
    Column(Modifier.background(Mb.colors.surface).clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = if (m.weeks.isEmpty()) MbDimens.monthRowCollapsed else MbDimens.monthRow)
                .padding(start = MbDimens.sidePadding, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(Formats.monthName(m.month), style = MbType.monthName, color = Mb.colors.textBody)
                if (m.weeks.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    FitText(Formats.rangeShort(m.range), MbType.monthRange, Mb.colors.textTertiary)
                }
            }
            // An expanded month's amounts sit slightly above center in Realbyte; collapsed ones are centered.
            AmountColumns(m.totals, spec, totalPrefix = "", if (m.weeks.isEmpty()) Modifier else Modifier.padding(bottom = 3.dp))
        }
        Hairline()
    }
}

@Composable
private fun WeekRowView(w: WeekRow, spec: CurrencySpec) {
    val background: Color = if (w.isCurrent) Mb.colors.currentWeek else Mb.colors.background
    Column(Modifier.background(background)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = MbDimens.weekRow).padding(start = MbDimens.weekRowInset, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Realbyte puts the range on the income/expense baseline; the group as a whole stays centered.
            Row(Modifier.weight(1f)) {
                FitText(Formats.rangeShort(w.range), MbType.weekRange, Mb.colors.textBody, Modifier.weight(1f).alignByBaseline())
                AmountColumns(w.totals, spec, totalPrefix = if (w.isCurrent) "Total " else "", Modifier.alignByBaseline())
            }
        }
        Hairline()
    }
}

@Composable
private fun AmountColumns(t: Totals, spec: CurrencySpec, totalPrefix: String, modifier: Modifier = Modifier) {
    Column(modifier.width(MbDimens.monthlyColumn * 2), horizontalAlignment = Alignment.End) {
        Row {
            // The start padding keeps a gap between the columns once large amounts fill them.
            AmountText(t.income, spec, Mb.colors.income, Modifier.width(MbDimens.monthlyColumn).padding(start = 4.dp), MbType.monthlyAmount)
            AmountText(t.expense, spec, Mb.colors.expense, Modifier.width(MbDimens.monthlyColumn).padding(start = 4.dp), MbType.monthlyAmount)
        }
        Spacer(Modifier.height(2.dp))
        FitText(totalPrefix + money(t.total, spec), MbType.monthlyTotal, Mb.colors.textSecondary)
    }
}
