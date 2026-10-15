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
import com.moneybook.ui.common.Hairline
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType

/**
 * Realbyte's trend chart:
 * - gridlines with compact amounts on the left;
 * - an accent line with dots on a gray plot area;
 * - one label under each point, and optionally its value.
 */
@Composable
fun LineChart(
    values: List<Long>, labels: List<String>, decimals: Int, maxLines: Int,
    modifier: Modifier = Modifier, valueTexts: List<String> = emptyList(),
) {
    if (values.isEmpty()) return
    val axis = niceAxis(values.min(), values.max(), maxLines)
    val measurer = rememberTextMeasurer()
    val colors = Mb.colors
    val axisStyle = MbType.chartAxis.copy(color = colors.textSecondary)
    Column(modifier.fillMaxWidth().background(colors.surface)) {
        Spacer(Modifier.height(MbDimens.lineChartTop))
        Canvas(Modifier.fillMaxWidth().height(MbDimens.lineChartHeight).semantics { contentDescription = "Line chart" }) {
            val left = MbDimens.chartAxisWidth.toPx()
            drawRect(colors.background, topLeft = Offset(left, 0f), size = Size(size.width - left, size.height))
            val yOf = drawValueGrid(axis, decimals, left, 0f, size.height, colors.divider, measurer, axisStyle)
            val cell = (size.width - left) / values.size
            val points = values.mapIndexed { i, v -> Offset(left + cell * (i + 0.5f), yOf(v)) }
            points.zipWithNext().forEach { (a, b) -> drawLine(colors.accent, a, b, strokeWidth = 2.dp.toPx()) }
            points.forEach { drawCircle(colors.accent, radius = 3.dp.toPx(), center = it) }
        }
        if (valueTexts.isEmpty()) {
            PointLabels(labels, top = 8.dp, bottom = 11.dp)
        } else {
            Spacer(Modifier.height(MbDimens.chartAxisGap))
            Hairline(px = 2)
            PointLabels(labels, listOf(valueTexts.map { it to colors.textBody }), top = 11.dp, bottom = 12.dp)
        }
    }
}
