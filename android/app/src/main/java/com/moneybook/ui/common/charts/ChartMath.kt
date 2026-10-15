package com.moneybook.ui.common.charts

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A value axis with gridlines at [min], [min] + [step], … ([lines] of them). Values are minor units. */
data class Axis(val min: Long, val step: Long, val lines: Int) {
    val max: Long get() = min + step * (lines - 1)
    val ticks: List<Long> get() = List(lines) { min + step * it }

    /** 0 at [min], 1 at [max]. */
    fun fraction(value: Long): Float = (value - min).toFloat() / (max - min)
}

/**
 * The finest 1-2-5 step whose gridlines cover [low]..[high] in at most [maxLines] lines, like Realbyte's
 * "8k, 10k, 12k". [includeZero] starts the axis at 0 (bar charts). A flat series still gets two lines.
 */
fun niceAxis(low: Long, high: Long, maxLines: Int, includeZero: Boolean = false): Axis {
    require(maxLines >= 2)
    val lo = if (includeZero) min(low, 0L) else low
    val hi = if (high > lo) high else lo + 1
    var magnitude = 1L
    while (true) {
        for (m in longArrayOf(1, 2, 5)) {
            val step = m * magnitude
            val first = Math.floorDiv(lo, step)
            val last = -Math.floorDiv(-hi, step)
            if (last - first + 1 <= maxLines) return Axis(first * step, step, (last - first + 1).toInt())
        }
        magnitude *= 10
    }
}

private val THOUSAND = BigDecimal(1_000)
private val MILLION = BigDecimal(1_000_000)

/** Axis labels in major units without a currency: "8k", "12k", "1.5M", "350", "0". */
fun compactAmount(minor: Long, decimals: Int): String {
    val major = BigDecimal.valueOf(minor, decimals)
    val (value, suffix) = when {
        major.abs() >= MILLION -> major.divide(MILLION) to "M"
        major.abs() >= THOUSAND -> major.divide(THOUSAND) to "k"
        else -> major to ""
    }
    val scale = if (suffix.isEmpty()) 0 else 1
    return value.setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + suffix
}

/** Start angle and sweep of each slice, in degrees clockwise from 12 o'clock. */
fun sliceSweeps(shares: List<Double>): List<Pair<Float, Float>> {
    var start = 0.0
    return shares.map { share ->
        val from = start
        start += share * 360
        from.toFloat() to (share * 360).toFloat()
    }
}

/**
 * A labelled slice: the angle of its middle, the side its label goes to and the label's centre height.
 * [x] is where the label's line ends and [room] how wide its text may be before it would reach the
 * margin; `layoutPie` sets both, `layoutPieLabels` leaves them unset.
 */
data class PieLabel(
    val index: Int, val angle: Float, val right: Boolean, val y: Float, val x: Float = 0f, val room: Float = Float.MAX_VALUE,
)

private const val LABEL_CHARS = 15

/** Realbyte cuts pie label names after 15 characters (UTF-16 units, so an emoji counts double) and adds "...". */
fun pieLabelName(name: String): String {
    if (name.length <= LABEL_CHARS) return name
    val end = if (name[LABEL_CHARS - 1].isHighSurrogate()) LABEL_CHARS - 1 else LABEL_CHARS
    return name.take(end) + "..."
}

/** The pie's radius and its labels, see [layoutPie]. */
data class PieLayout(val radius: Float, val labels: List<PieLabel>)

/**
 * Realbyte's pie in a [width] × [height] area. Labels sit on a circle [leader] beyond the pie's edge:
 * at their slice's angle unless that would overlap another label on the same side, in which case they
 * move up or down ([labelHeight] apart) and follow the circle. A side keeps only the labels that fit
 * within the pie's height plus half a label above and below; the biggest slices win. The pie is as large as [maxRadius] allows unless
 * a label's text ([widths], drawn on the outer side) would come closer than [margin] to the edge, so a
 * pie with few labels is big and a crowded one small. It never gets smaller than [minRadius]: there,
 * each label's [PieLabel.room] tells how far its text may reach (the caller cuts it to fit), and a label
 * with less than [minRoom] is left out. The pie's centre is at [centerX].
 */
fun layoutPie(
    shares: List<Double>, widths: List<Float>, width: Float, height: Float,
    maxRadius: Float, leader: Float, gap: Float, labelHeight: Float, margin: Float, minShare: Double = 0.02,
    centerX: Float = width / 2, minRadius: Float = 0f, minRoom: Float = 0f,
): PieLayout {
    val cx = centerX
    val cy = height / 2
    val mids = sliceSweeps(shares).map { (start, sweep) -> start + sweep / 2 }
    val candidates = shares.indices.filter { shares[it] >= minShare }

    fun at(radius: Float, dropNarrow: Boolean = false): PieLayout {
        val reach = radius + leader
        val top = max(cy - radius - labelHeight / 2, labelHeight / 2)
        val bottom = min(cy + radius + labelHeight / 2, height - labelHeight / 2)
        val capacity = ((bottom - top) / labelHeight).toInt() + 1
        val kept = candidates.groupBy { mids[it] < 180f }.values
            .flatMap { side -> side.sortedByDescending { shares[it] }.take(capacity) }
        val labels = placeLabels(kept.sorted(), mids, cy, reach, labelHeight, top, bottom).map {
            val dy = it.y - cy
            val dx = sqrt(max(reach * reach - dy * dy, 0f))
            val x = if (it.right) cx + dx else cx - dx
            it.copy(x = x, room = if (it.right) width - margin - gap - x else x - gap - margin)
        }
        return PieLayout(radius, if (dropNarrow) labels.filter { it.room >= minRoom } else labels)
    }

    fun fits(pie: PieLayout) = pie.labels.all { label ->
        val w = widths[label.index]
        if (label.right) label.x + gap + w <= width - margin else label.x - gap - w >= margin
    }

    val biggest = at(maxRadius)
    if (fits(biggest)) return biggest
    val smallest = max(minRadius, 0f)
    if (smallest >= maxRadius || !fits(at(smallest))) return at(min(smallest, maxRadius), dropNarrow = true)
    // Larger pies push labels further out, so the largest radius that fits is found by halving.
    var low = smallest
    var high = maxRadius
    repeat(20) {
        val mid = (low + high) / 2
        if (fits(at(mid))) low = mid else high = mid
    }
    return at(low)
}

/**
 * Slices of at least [minShare] get a label beside the pie, at the height where their middle points
 * ([reach] from the centre), on the side it points to. Labels on one side that would be closer than
 * [labelHeight] are pushed down, then back up if they run past [bottom].
 */
fun layoutPieLabels(
    shares: List<Double>, centerY: Float, reach: Float, labelHeight: Float, top: Float, bottom: Float, minShare: Double = 0.02,
): List<PieLabel> {
    val mids = sliceSweeps(shares).map { (start, sweep) -> start + sweep / 2 }
    return placeLabels(shares.indices.filter { shares[it] >= minShare }, mids, centerY, reach, labelHeight, top, bottom)
}

private fun placeLabels(
    indices: List<Int>, mids: List<Float>, centerY: Float, reach: Float, labelHeight: Float, top: Float, bottom: Float,
): List<PieLabel> {
    val wanted = indices.map { i ->
        val y = centerY - cos(Math.toRadians(mids[i].toDouble())).toFloat() * reach
        PieLabel(i, mids[i], right = mids[i] < 180f, y = y)
    }
    return wanted.groupBy { it.right }.values
        .flatMap { side -> spread(side.sortedBy { it.y }, labelHeight, top, bottom) }
        .sortedBy { it.index }
}

private fun spread(labels: List<PieLabel>, gap: Float, top: Float, bottom: Float): List<PieLabel> {
    val ys = labels.map { it.y.coerceIn(top, bottom) }.toMutableList()
    for (i in 1 until ys.size) ys[i] = max(ys[i], ys[i - 1] + gap)
    if (ys.isNotEmpty() && ys.last() > bottom) {
        ys[ys.lastIndex] = bottom
        for (i in ys.lastIndex - 1 downTo 0) ys[i] = min(ys[i], ys[i + 1] - gap)
    }
    return labels.mapIndexed { i, label -> label.copy(y = ys[i]) }
}
