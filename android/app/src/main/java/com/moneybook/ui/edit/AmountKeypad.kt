package com.moneybook.ui.edit

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.moneybook.R
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.ui.common.IconButtonBox
import com.moneybook.ui.common.MbIcon
import com.moneybook.ui.common.PanelHeader
import com.moneybook.ui.common.cellLines
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType

internal sealed interface KeyCell {
    data class Label(val text: String, val key: KeypadKey) : KeyCell
    data class Icon(@DrawableRes val icon: Int, val description: String, val key: KeypadKey) : KeyCell
    data object Blank : KeyCell
    /** An empty white key (the rate sheet's keypad). */
    data object Space : KeyCell
    data object Calc : KeyCell
    data object Done : KeyCell
}

internal fun digit(c: Char) = KeyCell.Label(c.toString(), KeypadKey.Digit(c))

/** Realbyte's amount keypad. The calc key shows when the caller can open the calculator (the entry form); the account form leaves it blank. */
private val LAYOUT: List<List<KeyCell>> = listOf(
    listOf(digit('1'), digit('2'), digit('3'), KeyCell.Icon(R.drawable.ic_backspace, "Backspace", KeypadKey.Backspace)),
    listOf(digit('4'), digit('5'), digit('6'), KeyCell.Label("-", KeypadKey.Minus)),
    listOf(digit('7'), digit('8'), digit('9'), KeyCell.Calc),
    listOf(KeyCell.Blank, digit('0'), KeyCell.Label(".", KeypadKey.Dot), KeyCell.Done),
)

/** Account Info's Settlement Date / Payment Date keypad (days 1–31). */
private val DAY_LAYOUT: List<List<KeyCell>> = listOf(
    listOf(digit('1'), digit('2'), digit('3'), KeyCell.Icon(R.drawable.ic_backspace, "Backspace", KeypadKey.Backspace)),
    listOf(digit('4'), digit('5'), digit('6'), KeyCell.Label("C", KeypadKey.Clear)),
    listOf(digit('7'), digit('8'), digit('9'), KeyCell.Blank),
    listOf(KeyCell.Blank, digit('0'), KeyCell.Blank, KeyCell.Done),
)

@Composable
fun DayKeypad(title: String, onKey: (KeypadKey) -> Unit, onDone: () -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Mb.colors.surface)) {
        PanelHeader(title, onClose)
        DAY_LAYOUT.forEach { row ->
            Row(Modifier.fillMaxWidth().height(MbDimens.keyHeight)) {
                row.forEach { cell -> KeyView(cell, Modifier.weight(1f).fillMaxHeight(), onKey, onDone, null) }
            }
        }
    }
}

/**
 * The amount keypad. With sub-currencies, [strip] (main first) shows above the keys and [onCurrency] picks one;
 * [onGlobe] adds the header's Sub Currency Setting button. [clearKey] puts C in place of the minus (budget amounts).
 */
@Composable
fun AmountKeypad(
    onKey: (KeypadKey) -> Unit,
    onDone: () -> Unit,
    onClose: () -> Unit,
    onCalc: (() -> Unit)? = null,
    clearKey: Boolean = false,
    title: String = "Amount",
    strip: List<CurrencySpec> = emptyList(),
    selected: String? = null,
    onCurrency: (String) -> Unit = {},
    onGlobe: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().background(Mb.colors.surface)) {
        PanelHeader(title, onClose) {
            if (onGlobe != null) IconButtonBox(R.drawable.ic_globe, "Sub Currency Setting", onGlobe, tint = Mb.colors.keypadHeaderText)
        }
        if (strip.size > 1) CurrencyStrip(strip, selected, onCurrency)
        LAYOUT.forEach { row ->
            Row(Modifier.fillMaxWidth().height(MbDimens.keyHeight)) {
                row.forEach { cell ->
                    val shown = when {
                        cell == KeyCell.Calc && onCalc == null -> KeyCell.Blank
                        clearKey && cell is KeyCell.Label && cell.key == KeypadKey.Minus -> KeyCell.Label("C", KeypadKey.Clear)
                        else -> cell
                    }
                    KeyView(shown, Modifier.weight(1f).fillMaxHeight(), onKey, onDone, onCalc)
                }
            }
        }
    }
}

@Composable
private fun CurrencyStrip(specs: List<CurrencySpec>, selected: String?, onPick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().height(MbDimens.currencyStrip).background(Mb.colors.keypadStrip).horizontalScroll(rememberScrollState())) {
        specs.forEach { spec ->
            Box(
                Modifier.fillMaxHeight().widthIn(min = 50.dp)
                    .background(if (spec.code == selected) Mb.colors.keypadStripSelected else Color.Transparent)
                    .clickable { onPick(spec.code) }.padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text(spec.symbol, style = MbType.formValue, color = Color.White) }
        }
    }
}

@Composable
internal fun KeyView(cell: KeyCell, modifier: Modifier, onKey: (KeypadKey) -> Unit, onDone: () -> Unit, onCalc: (() -> Unit)?) {
    val c = Mb.colors
    // Lines draw after each key's background, or the background would paint over them.
    val lines = Modifier.cellLines(c.divider)
    when (cell) {
        is KeyCell.Label -> Box(modifier.background(c.surface).then(lines).clickable { onKey(cell.key) }, contentAlignment = Alignment.Center) {
            Text(cell.text, style = MbType.key, color = c.textPrimary)
        }
        is KeyCell.Icon -> Box(modifier.background(c.surface).then(lines).clickable { onKey(cell.key) }, contentAlignment = Alignment.Center) {
            MbIcon(cell.icon, cell.description, tint = c.textPrimary, size = 30.dp)
        }
        KeyCell.Calc -> Box(modifier.background(c.surface).then(lines).clickable { onCalc?.invoke() }, contentAlignment = Alignment.Center) {
            MbIcon(R.drawable.ic_calculator, "Calculator", tint = c.textPrimary, size = 30.dp)
        }
        KeyCell.Blank -> Box(modifier.background(c.background).then(lines))
        KeyCell.Space -> Box(modifier.background(c.surface).then(lines))
        KeyCell.Done -> Box(modifier.background(c.accent).clickable(onClick = onDone), contentAlignment = Alignment.Center) {
            Text("Done", style = MbType.keyDone, color = Color.White)
        }
    }
}
