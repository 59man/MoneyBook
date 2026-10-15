package com.moneybook.ui.common.charts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.moneybook.ui.common.FitText
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType

/**
 * Gridlines at [axis]'s ticks from [left] to the right edge, with compact amounts to the left of [left].
 * Returns where a value sits between [top] and [bottom].
 */
internal fun DrawScope.drawValueGrid(
    axis: Axis, decimals: Int, left: Float, top: Float, bottom: Float,
    gridColor: Color, measurer: TextMeasurer, labelStyle: TextStyle,
): (Long) -> Float {
    val yOf = { value: Long -> bottom - axis.fraction(value) * (bottom - top) }
    for (tick in axis.ticks) {
        val y = yOf(tick)
        drawLine(gridColor, Offset(left, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        val label = measurer.measure(compactAmount(tick, decimals), labelStyle)
        drawText(label, topLeft = Offset(left - label.size.width - 4.dp.toPx(), y - label.size.height / 2f))
    }
    return yOf
}

/**
 * The labels under a chart's points, each followed by optional value lines (a text and color per point).
 * The cells line up with the charts' point columns: an axis-wide gap, then equal cells. [top] and [bottom]
 * pad the row; Realbyte puts more room under a chart whose labels form a table below an axis line.
 */
@Composable
internal fun PointLabels(
    labels: List<String>, valueRows: List<List<Pair<String, Color>>> = emptyList(), top: Dp, bottom: Dp, axisWidth: Dp = MbDimens.chartAxisWidth,
) {
    Row(Modifier.fillMaxWidth().padding(top = top, bottom = bottom)) {
        Spacer(Modifier.width(axisWidth))
        labels.forEachIndexed { i, label ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                FitText(label, MbType.chartAxis, Mb.colors.textSecondary, textAlign = TextAlign.Center)
                for (row in valueRows) {
                    row.getOrNull(i)?.let { (text, color) -> FitText(text, MbType.chartValue, color, textAlign = TextAlign.Center) }
                }
            }
        }
    }
}
