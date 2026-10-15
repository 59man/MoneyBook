package com.moneybook.ui.common.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.moneybook.domain.Totals
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.ui.common.money
import com.moneybook.ui.common.Hairline
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType

/** Realbyte's monthly bars: income (light blue) and expense (light red) side by side, with both values under each month. */
@Composable
fun BarChart(totals: List<Totals>, labels: List<String>, spec: CurrencySpec, modifier: Modifier = Modifier) {
    if (totals.isEmpty()) return
    val axis = niceAxis(0, totals.maxOf { maxOf(it.income, it.expense) }, maxLines = 3, includeZero = true)
    val measurer = rememberTextMeasurer()
    val colors = Mb.colors
    val axisStyle = MbType.chartAxis.copy(color = colors.textSecondary)
    Column(modifier.fillMaxWidth().background(colors.surface)) {
        Spacer(Modifier.height(MbDimens.barChartTop))
        Canvas(Modifier.fillMaxWidth().height(MbDimens.barChartHeight).semantics { contentDescription = "Bar chart" }) {
            val left = MbDimens.chartAxisWidth.toPx()
            drawRect(colors.background, topLeft = Offset(left, 0f), size = Size(size.width - left, size.height))
            val yOf = drawValueGrid(axis, spec.decimals, left, 0f, size.height, colors.divider, measurer, axisStyle)
            val cell = (size.width - left) / totals.size
            val bar = MbDimens.barWidth.toPx()
            val gap = 5.dp.toPx()
            val base = yOf(0)
            totals.forEachIndexed { i, t ->
                val center = left + cell * (i + 0.5f)
                drawRect(colors.incomeBar, Offset(center - gap / 2 - bar, yOf(t.income)), Size(bar, base - yOf(t.income)))
                drawRect(colors.expenseBar, Offset(center + gap / 2, yOf(t.expense)), Size(bar, base - yOf(t.expense)))
            }
        }
        Spacer(Modifier.height(MbDimens.chartAxisGap))
        Hairline(px = 2)
        PointLabels(
            labels,
            listOf(
                totals.map { money(it.income, spec, withSymbol = false) to colors.income },
                totals.map { money(it.expense, spec, withSymbol = false) to colors.expense },
            ),
            top = 11.dp, bottom = 12.dp,
        )
    }
}
