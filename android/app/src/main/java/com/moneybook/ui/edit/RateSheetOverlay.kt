package com.moneybook.ui.edit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.moneybook.R
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.ui.common.FormRow
import com.moneybook.ui.common.IconButtonBox
import com.moneybook.ui.common.PanelHeader
import com.moneybook.ui.common.money
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType

/** The rate keypads (the rate sheet, Sub Currency Setting's Exchange rate): C instead of minus, and no calculator. */
private val RATE_LAYOUT: List<List<KeyCell>> = listOf(
    listOf(digit('1'), digit('2'), digit('3'), KeyCell.Icon(R.drawable.ic_backspace, "Backspace", KeypadKey.Backspace)),
    listOf(digit('4'), digit('5'), digit('6'), KeyCell.Label("C", KeypadKey.Clear)),
    listOf(digit('7'), digit('8'), digit('9'), KeyCell.Space),
    listOf(KeyCell.Blank, digit('0'), KeyCell.Label(".", KeypadKey.Dot), KeyCell.Done),
)

/** A rate field follows the amount keypad's rules: digits (at most 10 decimals), one dot, ⌫, and C clears. */
internal fun typeRate(text: String, key: KeypadKey): String = when (key) {
    is KeypadKey.Digit -> if ('.' in text && text.substringAfter('.').length >= 10) text else if (text == "0") key.char.toString() else text + key.char
    KeypadKey.Dot -> if ('.' in text) text else text.ifEmpty { "0" } + "."
    KeypadKey.Backspace -> text.dropLast(1)
    KeypadKey.Clear -> ""
    KeypadKey.Minus -> text
}

@Composable
private fun RateKeys(onKey: (KeypadKey) -> Unit, onDone: () -> Unit) {
    RATE_LAYOUT.forEach { row ->
        Row(Modifier.fillMaxWidth().height(MbDimens.keyHeight)) {
            row.forEach { cell -> KeyView(cell, Modifier.weight(1f).fillMaxHeight(), onKey, onDone, null) }
        }
    }
}

/** A bottom panel with the rate keys under a [title] header (Sub Currency Setting → Exchange rate). */
@Composable
fun RateKeypad(title: String, onKey: (KeypadKey) -> Unit, onDone: () -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Mb.colors.surface)) {
        PanelHeader(title, onClose)
        RateKeys(onKey, onDone)
    }
}

/**
 * The rate sheet's frame: a bottom sheet over a scrim, titled "Exchange rate" with ✕, the [rows], then the rate keys.
 * Drawn inside the screen, not as a window, so the lock screen covers it.
 */
@Composable
fun RateSheetFrame(onKey: (KeypadKey) -> Unit, onDone: () -> Unit, onClose: () -> Unit, rows: @Composable () -> Unit) {
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable(onClick = onClose))
        Column(
            // Above the navigation bar, which the scrim still covers (as in Realbyte).
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).background(Mb.colors.surface)
                // Taps between the rows must not reach the scrim.
                .clickable(remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Exchange rate", style = MbType.dialogTitle, color = Mb.colors.textPrimary, modifier = Modifier.weight(1f))
                IconButtonBox(R.drawable.ic_close, "Close", onClose, tint = Mb.colors.textPrimary)
            }
            rows()
            Box(Modifier.height(12.dp))
            RateKeys(onKey, onDone)
        }
    }
}

/**
 * Amount → "Exchange rate: …": a bottom sheet over a scrim with the entry's main amount and its rate (Realbyte's
 * rb_ratetap). Drawn inside the screen, not as a window, so the lock screen covers it.
 */
@Composable
fun RateSheetOverlay(
    sheet: RateSheet,
    mainSpec: CurrencySpec,
    onFocus: (RateField) -> Unit,
    onKey: (KeypadKey) -> Unit,
    onDone: () -> Unit,
    onClose: () -> Unit,
) {
    RateSheetFrame(onKey, onDone, onClose) {
        FormRow(mainSpec.code, active = sheet.focus == RateField.MAIN, onClick = { onFocus(RateField.MAIN) }) {
            SheetValue(money(sheet.main.minorUnits() ?: 0, mainSpec), sheet.focus == RateField.MAIN)
        }
        FormRow("Exchange\nrate", active = sheet.focus == RateField.RATE, onClick = { onFocus(RateField.RATE) }, labelLines = 2) {
            SheetValue(sheet.rate, sheet.focus == RateField.RATE)
        }
    }
}

/** The focused field's value is dark, the other gray (rb_ratetap). */
@Composable
private fun SheetValue(text: String, focused: Boolean) =
    Text(text, style = MbType.formValue, color = if (focused) Mb.colors.textPrimary else Mb.colors.textTertiary, maxLines = 1)
