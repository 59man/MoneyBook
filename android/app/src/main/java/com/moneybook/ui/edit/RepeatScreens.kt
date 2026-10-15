package com.moneybook.ui.edit

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.moneybook.R
import com.moneybook.shared.model.RepeatFrequency
import com.moneybook.ui.common.FilledButton
import com.moneybook.ui.common.Hairline
import com.moneybook.ui.common.MbIcon
import com.moneybook.ui.common.MbTopBar
import com.moneybook.ui.common.PanelHeader
import com.moneybook.ui.common.TitleText
import com.moneybook.ui.common.cellLines
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType

/** Rep/Inst. → Repeat (capture 12): Nothing, then Realbyte's frequencies; the current one carries a check. */
@Composable
fun RepeatPickScreen(selected: RepeatFrequency?, includeNothing: Boolean, onPick: (RepeatFrequency?) -> Unit, onBack: () -> Unit) {
    // A full-screen overlay over the entry form: it takes every tap, so none reaches the form behind it.
    Column(Modifier.fillMaxSize().background(Mb.colors.surface).pointerInput(Unit) {}.systemBarsPadding()) {
        MbTopBar(onBack = onBack, title = { TitleText("Repeat") })
        Hairline(px = 2)
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (includeNothing) item(key = "nothing") { OptionRow("Nothing", selected == null) { onPick(null) } }
            items(RepeatFrequency.entries, key = { it.name }) { f -> OptionRow(f.label, f == selected) { onPick(f) } }
        }
    }
}

@Composable
private fun OptionRow(label: String, checked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = MbDimens.repeatRow).clickable(onClick = onClick).padding(horizontal = 23.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MbType.repeatOption, color = Mb.colors.textPrimary, modifier = Modifier.weight(1f))
        if (checked) MbIcon(R.drawable.ic_check, "Selected", tint = Mb.colors.accent)
    }
}

/** Rep/Inst. → Installment (capture 13): the number of months, typed on Realbyte's small pad; 2 to 60. */
@Composable
fun InstallmentScreen(initial: Int?, onSave: (Int) -> Unit, onBack: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial?.toString().orEmpty()) }
    val context = LocalContext.current
    val save = {
        val months = text.toIntOrNull()
        if (months != null && months in 2..60) onSave(months)
        else Toast.makeText(context, "Enter 2 to 60 months.", Toast.LENGTH_SHORT).show()
    }
    Column(Modifier.fillMaxSize().background(Mb.colors.surface).pointerInput(Unit) {}.systemBarsPadding()) {
        MbTopBar(onBack = onBack, title = { TitleText("Installment") })
        Hairline(px = 2)
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = MbDimens.memoInset)) {
            Box(Modifier.fillMaxWidth().heightIn(min = MbDimens.installmentField), contentAlignment = Alignment.CenterStart) {
                Text(
                    text.ifEmpty { "Monthly installment plan" }, style = MbType.memoText,
                    color = if (text.isEmpty()) Mb.colors.textSecondary else Mb.colors.textPrimary,
                )
            }
            Hairline()
            Spacer(Modifier.height(30.dp))
            FilledButton("Save", Mb.colors.accent, Modifier.fillMaxWidth(), save)
        }
        MonthsPad(
            onDigit = { d -> if (text.length < 2) text = (text + d).trimStart('0') },
            onBackspace = { text = text.dropLast(1) },
            onClear = { text = "" },
            onSave = save,
        )
    }
}

/** The pad under the Installment screen: `1 2 3 ⌫ / 4 5 6 C / 7 8 9 · / · 0 · Save`. */
@Composable
private fun MonthsPad(onDigit: (Char) -> Unit, onBackspace: () -> Unit, onClear: () -> Unit, onSave: () -> Unit) {
    val c = Mb.colors
    val lines = Modifier.cellLines(c.divider)
    @Composable
    fun Key(label: String, modifier: Modifier, onClick: () -> Unit) =
        Box(modifier.background(c.surface).then(lines).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Text(label, style = MbType.key, color = c.textPrimary)
        }
    @Composable
    fun Blank(modifier: Modifier) = Box(modifier.background(c.background).then(lines))
    Column(Modifier.fillMaxWidth().background(c.surface)) {
        PanelHeader("Installment", onClose = null)
        listOf("123", "456", "789").forEachIndexed { r, digits ->
            Row(Modifier.fillMaxWidth().height(MbDimens.keyHeight)) {
                digits.forEach { d -> Key(d.toString(), Modifier.weight(1f).fillMaxHeight()) { onDigit(d) } }
                val cell = Modifier.weight(1f).fillMaxHeight()
                when (r) {
                    0 -> Box(cell.background(c.surface).then(lines).clickable(onClick = onBackspace), contentAlignment = Alignment.Center) {
                        MbIcon(R.drawable.ic_backspace, "Backspace", tint = c.textPrimary, size = 30.dp)
                    }
                    1 -> Key("C", cell, onClear)
                    else -> Blank(cell)
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(MbDimens.keyHeight)) {
            Blank(Modifier.weight(1f).fillMaxHeight())
            Key("0", Modifier.weight(1f).fillMaxHeight()) { onDigit('0') }
            Blank(Modifier.weight(1f).fillMaxHeight())
            Box(
                Modifier.weight(1f).fillMaxHeight().background(c.accent).clickable(onClick = onSave).semantics { contentDescription = "Save months" },
                contentAlignment = Alignment.Center,
            ) {
                Text("Save", style = MbType.keyDone, color = Color.White)
            }
        }
    }
}
