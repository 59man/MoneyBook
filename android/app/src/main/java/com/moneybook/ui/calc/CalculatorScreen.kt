package com.moneybook.ui.calc

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.moneybook.R
import com.moneybook.domain.CalcKey
import com.moneybook.domain.Calculator
import com.moneybook.ui.common.FitText
import com.moneybook.ui.common.IconButtonBox
import com.moneybook.ui.common.MbIcon
import com.moneybook.ui.common.cellLines
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType

private sealed interface CalcCell {
    data class Key(val label: String, val key: CalcKey, val gray: Boolean = false) : CalcCell
    data object Backspace : CalcCell
    data object Done : CalcCell
}

private fun digit(c: Char) = CalcCell.Key(c.toString(), CalcKey.Digit(c))
private fun op(c: Char) = CalcCell.Key(c.toString(), CalcKey.Op(c), gray = true)

/** Capture 17: `AC ÷ × ⌫ / 7 8 9 − / 4 5 6 + / 1 2 3 = / 00 0 . DONE`; the top row and right column are gray. */
private val LAYOUT: List<List<CalcCell>> = listOf(
    listOf(CalcCell.Key("AC", CalcKey.Clear, gray = true), op('÷'), op('×'), CalcCell.Backspace),
    listOf(digit('7'), digit('8'), digit('9'), op('−')),
    listOf(digit('4'), digit('5'), digit('6'), op('+')),
    listOf(digit('1'), digit('2'), digit('3'), CalcCell.Key("=", CalcKey.Equals, gray = true)),
    listOf(CalcCell.Key("00", CalcKey.DoubleZero), digit('0'), CalcCell.Key(".", CalcKey.Dot), CalcCell.Done),
)

/** Amount keypad → calc key: full-screen calculator; DONE hands the result back, ✕ leaves the amount alone. */
@Composable
fun CalculatorScreen(initial: Calculator, decimals: Int, onDone: (Long) -> Unit, onClose: () -> Unit) {
    var calc by remember { mutableStateOf(initial) }
    val c = Mb.colors
    val done = {
        val minor = calc.minorUnits(decimals)
        if (minor != null) onDone(minor) else if (calc.expr.isEmpty()) onClose()
    }
    // A full-screen overlay over the entry form: it takes every tap, so none reaches the form behind it.
    Column(Modifier.fillMaxSize().background(c.surface).pointerInput(Unit) {}.systemBarsPadding()) {
        Column(Modifier.weight(2.4f).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().height(MbDimens.topBar), horizontalArrangement = Arrangement.End) {
                IconButtonBox(R.drawable.ic_close, "Close", onClose)
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.End,
            ) {
                if (calc.expressionLine.isNotEmpty()) {
                    // FitText shrinks a long sum instead of breaking a number across lines.
                    FitText(calc.expressionLine, MbType.calcExpression, c.textSecondary, Modifier.fillMaxWidth(), textAlign = TextAlign.End)
                }
                FitText(calc.display(), MbType.calcResult, c.textPrimary, Modifier.fillMaxWidth(), textAlign = TextAlign.End)
            }
        }
        LAYOUT.forEach { row ->
            Row(Modifier.weight(1f).fillMaxWidth()) {
                row.forEach { cell ->
                    val m = Modifier.weight(1f).fillMaxHeight()
                    when (cell) {
                        is CalcCell.Key -> Box(
                            m.background(if (cell.gray) c.background else c.surface).cellLines(c.divider).clickable { calc = calc.press(cell.key) },
                            contentAlignment = Alignment.Center,
                        ) { FitText(cell.label, MbType.calcKey, c.textPrimary) }
                        CalcCell.Backspace -> Box(
                            m.background(c.background).cellLines(c.divider).clickable { calc = calc.press(CalcKey.Backspace) },
                            contentAlignment = Alignment.Center,
                        ) { MbIcon(R.drawable.ic_backspace, "Backspace", tint = c.textPrimary, size = 32.dp) }
                        CalcCell.Done -> Box(m.background(c.accent).clickable(onClick = done), contentAlignment = Alignment.Center) {
                            FitText("DONE", MbType.calcKey, Color.White)
                        }
                    }
                }
            }
        }
    }
}
