package com.moneybook.ui.common.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType
import kotlin.math.cos
import kotlin.math.sin

/** One pie slice: its label ("🍜 Food"), percent text ("28.5 %"), share of the whole (0..1) and color. */
data class PieEntry(val name: String, val percent: String, val share: Double, val color: Color)

/** A pie laid out with its label texts measured; [names] and [percents] are cut to each label's room. */
internal class MeasuredPie(
    val layout: PieLayout, val names: List<TextLayoutResult>, val percents: List<TextLayoutResult>, val gap: Float,
)

/**
 * Measures the labels at the current font size and lays the pie out with them: labels are spaced by
 * their real height (name + percent), and a label's text is cut with "…" to the room it has.
 */
internal fun measurePie(
    entries: List<PieEntry>, measurer: TextMeasurer, density: Density, width: Float, height: Float,
    nameStyle: TextStyle = MbType.pieLabel, percentStyle: TextStyle = MbType.piePercent,
): MeasuredPie = with(density) {
    val gap = 3.dp.toPx()
    val margin = MbDimens.sidePadding.toPx()
    fun name(i: Int, max: Int) =
        measurer.measure(pieLabelName(entries[i].name), nameStyle, overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = max))
    fun percent(i: Int, max: Int) =
        measurer.measure(entries[i].percent, percentStyle, overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = max))
    val half = (width / 2 - margin - gap).toInt().coerceAtLeast(0)
    val names = entries.indices.map { name(it, half) }.toMutableList()
    val percents = entries.indices.map { percent(it, half) }.toMutableList()
    val tallest = entries.indices.maxOfOrNull { names[it].size.height + percents[it].size.height } ?: 0
    val layout = layoutPie(
        shares = entries.map { it.share },
        widths = entries.indices.map { maxOf(names[it].size.width, percents[it].size.width).toFloat() },
        width = width, height = height, maxRadius = MbDimens.pieMaxDiameter.toPx() / 2,
        leader = MbDimens.pieLeader.toPx(), gap = gap, labelHeight = maxOf(MbDimens.pieLabelHeight.toPx(), tallest.toFloat()),
        margin = margin, centerX = width / 2 + MbDimens.pieCenterShift.toPx(),
        minRadius = MbDimens.pieMinDiameter.toPx() / 2, minRoom = MbDimens.pieLabelMinRoom.toPx(),
    )
    for (label in layout.labels) {
        val room = label.room.toInt().coerceAtLeast(0)
        if (names[label.index].size.width > room) names[label.index] = name(label.index, room)
        if (percents[label.index].size.width > room) percents[label.index] = percent(label.index, room)
    }
    MeasuredPie(layout, names, percents, gap)
}

/**
 * Realbyte's Stats pie. Slices run clockwise from 12 o'clock in the given order. Slices of at least
 * 2 % get an outside label (bold name, percent below) joined to the slice by a curved line in its color,
 * as many as fit beside the pie (see [layoutPie]); the pie shrinks when wide labels need the room.
 */
@Composable
fun PieChart(entries: List<PieEntry>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val nameStyle = MbType.pieLabel.copy(color = Mb.colors.textPrimary)
    val percentStyle = MbType.piePercent.copy(color = Mb.colors.textPrimary)
    Canvas(modifier.fillMaxWidth().height(MbDimens.statsPieArea).semantics { contentDescription = "Pie chart" }) {
        val pie = measurePie(entries, measurer, this, size.width, size.height, nameStyle, percentStyle)
        val leader = MbDimens.pieLeader.toPx()
        val center = Offset(size.width / 2 + MbDimens.pieCenterShift.toPx(), size.height / 2)
        val radius = pie.layout.radius
        sliceSweeps(entries.map { it.share }).forEachIndexed { i, (start, sweep) ->
            drawArc(
                entries[i].color, startAngle = start - 90f, sweepAngle = sweep, useCenter = true,
                topLeft = Offset(center.x - radius, center.y - radius), size = Size(radius * 2, radius * 2),
            )
        }
        for (label in pie.layout.labels) {
            val entry = entries[label.index]
            val rad = Math.toRadians(label.angle.toDouble())
            val dx = sin(rad).toFloat()
            val dy = -cos(rad).toFloat()
            val path = Path().apply {
                moveTo(center.x + dx * radius, center.y + dy * radius)
                quadraticTo(center.x + dx * (radius + leader / 2), center.y + dy * (radius + leader / 2), label.x, label.y)
            }
            drawPath(path, entry.color, style = Stroke(width = 1.dp.toPx()))
            val name = pie.names[label.index]
            val percent = pie.percents[label.index]
            val nameX = if (label.right) label.x + pie.gap else label.x - pie.gap - name.size.width
            val percentX = if (label.right) label.x + pie.gap else label.x - pie.gap - percent.size.width
            drawText(name, topLeft = Offset(nameX, label.y - name.size.height))
            drawText(percent, topLeft = Offset(percentX, label.y))
        }
    }
}
