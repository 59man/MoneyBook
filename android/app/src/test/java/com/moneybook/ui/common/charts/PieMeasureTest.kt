package com.moneybook.ui.common.charts

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.ui.theme.MoneyBookTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PieMeasureTest {
    @get:Rule val rule = createComposeRule()

    @Test @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    fun labelsAtTheLargestFontNeitherOverlapNorLeaveTheScreen() {
        val names = listOf(
            "předplatné + členské poplatky", "🚕 Transport", "👬 Social Life", "🍜 Food", "🧘 Health/Fitness",
            "entertainment", "electronics", "🪑 Household",
        )
        val shares = listOf(0.30, 0.20, 0.15, 0.10, 0.09, 0.07, 0.05, 0.04)
        val entries = names.mapIndexed { i, n -> PieEntry(n, "${shares[i] * 100} %", shares[i], Color.Red) }
        var pie: MeasuredPie? = null
        var widthPx = 0f
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 2f)) {
                MoneyBookTheme(darkTheme = false) {
                    val measurer = rememberTextMeasurer()
                    val density = LocalDensity.current
                    widthPx = with(density) { 360f * density.density }
                    pie = measurePie(entries, measurer, density, widthPx, with(density) { com.moneybook.ui.theme.MbDimens.statsPieArea.toPx() })
                }
            }
        }
        rule.waitForIdle()
        val p = pie!!
        val margin = 15f * 3f // sidePadding at xxhdpi
        for ((_, side) in p.layout.labels.groupBy { it.right }) {
            val sorted = side.sortedBy { it.y }
            sorted.zipWithNext().forEach { (a, b) ->
                val needed = p.names[a.index].size.height + p.percents[a.index].size.height
                assertTrue("labels ${a.index} and ${b.index} overlap: ${b.y - a.y} < $needed", b.y - a.y >= needed - 0.5f)
            }
        }
        for (label in p.layout.labels) {
            for (text in listOf(p.names[label.index], p.percents[label.index])) {
                val w = text.size.width
                val left = if (label.right) label.x + p.gap else label.x - p.gap - w
                assertTrue("label ${label.index} off screen: $left..${left + w} of $widthPx", left >= margin - 0.5f && left + w <= widthPx - margin + 0.5f)
            }
        }
        assertTrue("pie radius ${p.layout.radius}", p.layout.radius >= 57f * 3f - 0.5f)
    }
}
