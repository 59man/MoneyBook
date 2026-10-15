package com.moneybook.ui.common.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartMathTest {
    @Test fun `axis steps are 1-2-5 multiples that cover the values`() {
        val trend = niceAxis(822_900, 1_190_000, maxLines = 3)
        assertEquals(Axis(800_000, 200_000, 3), trend)
        assertEquals(listOf(800_000L, 1_000_000L, 1_200_000L), trend.ticks)
        assertEquals(Axis(30_000_000, 5_000_000, 4), niceAxis(34_000_000, 41_000_000, maxLines = 4))
        assertEquals(Axis(0, 5_000_000, 3), niceAxis(1_000_000, 8_790_000, maxLines = 3, includeZero = true))
    }

    @Test fun `a flat or empty series still gets two gridlines`() {
        val flat = niceAxis(500_000, 500_000, maxLines = 3)
        assertEquals(2, flat.lines)
        assertTrue(flat.min <= 500_000 && flat.max > 500_000)
        assertEquals(Axis(0, 1, 2), niceAxis(0, 0, maxLines = 3, includeZero = true))
        assertEquals(0.5f, Axis(0, 100, 3).fraction(100))
    }

    @Test fun `compact amounts drop the currency and use k and M`() {
        assertEquals("8k", compactAmount(800_000, 2))
        assertEquals("12k", compactAmount(1_200_000, 2))
        assertEquals("450k", compactAmount(45_000_000, 2))
        assertEquals("2.5k", compactAmount(250_000, 2))
        assertEquals("1.5M", compactAmount(150_000_000, 2))
        assertEquals("0", compactAmount(0, 2))
        assertEquals("350", compactAmount(35_000, 2))
        assertEquals("8k", compactAmount(8_000, 0))
        assertEquals("-5k", compactAmount(-500_000, 2))
    }

    @Test fun `slices sweep the full circle clockwise from 12 o'clock`() {
        assertEquals(listOf(0f to 180f, 180f to 90f, 270f to 90f), sliceSweeps(listOf(0.5, 0.25, 0.25)))
    }

    @Test fun `labels sit on the side their slice points to`() {
        val labels = layoutPieLabels(listOf(0.5, 0.5), centerY = 150f, reach = 70f, labelHeight = 30f, top = 15f, bottom = 285f)
        assertEquals(listOf(true, false), labels.map { it.right })
        assertEquals(listOf(90f, 270f), labels.map { it.angle })
        assertEquals(150f, labels[0].y, 0.01f)
    }

    @Test fun `slices under two percent get no label`() {
        val labels = layoutPieLabels(listOf(0.97, 0.02, 0.01), centerY = 150f, reach = 70f, labelHeight = 30f, top = 15f, bottom = 285f)
        assertEquals(listOf(0, 1), labels.map { it.index })
        assertEquals(1, layoutPieLabels(listOf(1.0), 150f, 70f, 30f, 15f, 285f).size)
    }

    @Test fun `crowded labels are pushed apart and kept inside the chart`() {
        val shares = List(8) { 0.02 } + 0.84 // eight small slices crowd the top right
        val labels = layoutPieLabels(shares, centerY = 150f, reach = 70f, labelHeight = 30f, top = 15f, bottom = 285f)
        for (side in labels.groupBy { it.right }.values) {
            val ys = side.map { it.y }.sorted()
            ys.zipWithNext().forEach { (a, b) -> assertTrue("labels overlap: $ys", b - a >= 30f - 0.01f) }
            assertTrue("labels outside the chart: $ys", ys.all { it in 15f..285f })
        }
    }

    @Test fun `long label names are cut after 15 characters like Realbyte`() {
        assertEquals("kupóny stravenk...", pieLabelName("kupóny stravenky"))
        assertEquals("\uD83E\uDDD8\uD83C\uDFFC Health/Fit...", pieLabelName("\uD83E\uDDD8\uD83C\uDFFC Health/Fitness"))
        assertEquals("entertainment", pieLabelName("entertainment"))
        // never split an emoji's surrogate pair
        assertEquals("abcdefghijklmn...", pieLabelName("abcdefghijklmn\uD83C\uDF5C Food"))
    }

    private fun layout(shares: List<Double>, widths: List<Float>, labelHeight: Float = 38f) = layoutPie(
        shares, widths, width = 360f, height = 300f, maxRadius = 91f, leader = 22f, gap = 3f, labelHeight = labelHeight, margin = 15f,
        minRadius = 57f, minRoom = 40f,
    )

    /** Every label's text, cut to its room, stays inside the 15 px margins. */
    private fun assertOnScreen(pie: PieLayout, widths: List<Float>) {
        for (label in pie.labels) {
            val w = minOf(widths[label.index], label.room)
            assertTrue("label ${label.index} has room ${label.room}", label.room >= 40f - 0.01f)
            val left = if (label.right) label.x + 3f else label.x - 3f - w
            assertTrue("label ${label.index} off screen: $left..${left + w}", left >= 15f - 0.01f && left + w <= 345f + 0.01f)
        }
    }

    @Test fun `two big slices get the largest pie`() {
        val pie = layout(listOf(0.931, 0.069), listOf(60f, 106f))
        assertEquals(91f, pie.radius, 0.01f)
        assertEquals(listOf(0, 1), pie.labels.map { it.index })
    }

    @Test fun `wide labels beside the pie shrink it until they stay on screen`() {
        val shares = listOf(0.31, 0.25, 0.22, 0.04, 0.04, 0.04, 0.035, 0.03, 0.025)
        val widths = listOf(50f, 75f, 80f, 60f, 85f, 50f, 85f, 70f, 80f)
        val pie = layout(shares, widths)
        assertTrue("radius ${pie.radius}", pie.radius < 91f && pie.radius >= 57f)
        assertOnScreen(pie, widths)
    }

    @Test fun `a side keeps only the labels that fit beside the pie, largest first`() {
        val shares = listOf(0.30) + List(10) { 0.07 - it * 0.002 }
        val pie = layout(shares, List(shares.size) { 40f })
        val band = 2 * (pie.radius + 22f)
        for ((_, side) in pie.labels.groupBy { it.right }) {
            assertTrue("too many labels: ${side.size}", side.size <= (band / 38f).toInt() + 1)
            val ys = side.map { it.y }.sorted()
            ys.zipWithNext().forEach { (a, b) -> assertTrue("overlap $ys", b - a >= 38f - 0.01f) }
        }
        val kept = pie.labels.map { it.index }.toSet()
        val dropped = shares.indices.filter { it !in kept }
        assertTrue("a smaller slice kept over a bigger one", dropped.all { d -> kept.all { shares[it] >= shares[d] } })
    }

    @Test fun `labels pushed up or down stay outside the pie`() {
        val shares = listOf(0.285, 0.257, 0.172, 0.163, 0.04, 0.036, 0.025, 0.021)
        val pie = layout(shares, List(shares.size) { 70f })
        for (label in pie.labels) {
            val dx = label.x - 180f
            val dy = label.y - 150f
            assertTrue("label ${label.index} inside the pie", dx * dx + dy * dy >= (pie.radius + 22f - 0.5f).let { it * it })
        }
    }

    @Test fun `a long name on the biggest slice keeps the pie at its smallest size and is cut to fit`() {
        val widths = listOf(300f, 50f, 50f)
        val pie = layout(listOf(0.5, 0.3, 0.2), widths)
        assertEquals(57f, pie.radius, 0.01f)
        assertTrue(0 in pie.labels.map { it.index })
        assertOnScreen(pie, widths)
    }

    @Test fun `labels as tall as large-font text neither overlap nor leave the screen`() {
        val shares = listOf(0.30, 0.25, 0.15, 0.10, 0.08, 0.06, 0.04, 0.02)
        val widths = List(shares.size) { 190f }
        val pie = layout(shares, widths, labelHeight = 57f)
        assertTrue(pie.radius >= 57f)
        for ((_, side) in pie.labels.groupBy { it.right }) {
            val ys = side.map { it.y }.sorted()
            ys.zipWithNext().forEach { (a, b) -> assertTrue("overlap $ys", b - a >= 57f - 0.01f) }
        }
        assertOnScreen(pie, widths)
    }
}
