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
import com.moneybook.domain.BudgetYearPoint
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType

/**
 * Budget detail's year chart: a bar per month for the spending, the budget as an accent line through the cell
 * centres, and the month at index [selected] marked with a ringed dot.
 */
@Composable
fun BudgetChart(points: List<BudgetYearPoint>, selected: Int, decimals: Int, modifier: Modifier = Modifier) {
    if (points.isEmpty()) return
    // Realbyte's chart keeps 40 % room above the highest value with no gridline at the very top
    // (budgetLineBar.html: maxPadding 0.4, endOnTick false): gridlines go up to the ceiling, the plot to the ceiling.
    val high = maxOf(points.maxOf { it.spent }, points.maxOf { it.budget })
    val nice = niceAxis(0, high, 5, includeZero = true)
    val ceiling = maxOf(nice.max, high + high * 2 / 5)
    val axis = nice.copy(lines = nice.lines + ((ceiling - nice.max) / nice.step).toInt())
    val measurer = rememberTextMeasurer()
    val colors = Mb.colors
    val axisStyle = MbType.chartAxis.copy(color = colors.textSecondary)
    Column(modifier.fillMaxWidth().background(colors.surface)) {
        Spacer(Modifier.height(MbDimens.lineChartTop))
        Canvas(Modifier.fillMaxWidth().height(MbDimens.budgetChartHeight).semantics { contentDescription = "Budget chart" }) {
            val left = MbDimens.budgetChartAxisWidth.toPx()
            drawRect(colors.background, topLeft = Offset(left, 0f), size = Size(size.width - left, size.height))
            val gridTop = size.height * (1 - axis.max.toFloat() / ceiling)
            val yOf = drawValueGrid(axis, decimals, left, gridTop, size.height, colors.divider, measurer, axisStyle)
            val cell = (size.width - left) / points.size
            val zero = yOf(axis.min)
            points.forEachIndexed { i, p ->
                val barWidth = cell * 0.4f
                val x = left + cell * (i + 0.5f) - barWidth / 2
                val top = yOf(p.spent.coerceIn(axis.min, axis.max))
                drawRect(colors.budgetChartBar, topLeft = Offset(x, top), size = Size(barWidth, zero - top))
            }
            val line = points.mapIndexed { i, p -> Offset(left + cell * (i + 0.5f), yOf(p.budget.coerceIn(axis.min, axis.max))) }
            line.zipWithNext().forEach { (a, b) -> drawLine(colors.accent, a, b, strokeWidth = 2.dp.toPx()) }
            line.forEachIndexed { i, o ->
                if (i == selected) {
                    drawCircle(colors.accent, radius = 5.dp.toPx(), center = o)
                    drawCircle(colors.surface, radius = 3.dp.toPx(), center = o)
                } else {
                    drawCircle(colors.accent, radius = 3.dp.toPx(), center = o)
                }
            }
        }
        PointLabels(points.map { it.label }, top = 8.dp, bottom = 11.dp, axisWidth = MbDimens.budgetChartAxisWidth)
    }
}
