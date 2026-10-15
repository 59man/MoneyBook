package com.moneybook.ui.memo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moneybook.R
import com.moneybook.domain.Formats
import com.moneybook.ui.common.ConfirmDialog
import com.moneybook.ui.common.DatePickDialog
import com.moneybook.ui.common.Hairline
import com.moneybook.ui.common.IconButtonBox
import com.moneybook.ui.common.MbTopBar
import com.moneybook.ui.common.TitleText
import com.moneybook.ui.common.appContainer
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType
import java.time.LocalDate

/** What the memo editor's controls do; the defaults do nothing (tests). */
data class MemoActions(
    val onBack: () -> Unit = {},
    val onTitle: (String) -> Unit = {},
    val onText: (String) -> Unit = {},
    val onDate: (LocalDate) -> Unit = {},
    val onColor: (Int?) -> Unit = {},
    val onPin: () -> Unit = {},
    val onPalette: () -> Unit = {},
    val onDelete: () -> Unit = {},
)

@Composable
fun MemoEditScreen(memoId: String?, date: LocalDate?, onClose: () -> Unit) {
    val c = appContainer()
    val vm: MemoEditViewModel = viewModel { MemoEditViewModel(memoId, date, c.memos, c.time) }
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.events.collect { onClose() } }
    BackHandler { vm.close() }
    MemoEditContent(
        s,
        MemoActions(
            onBack = vm::close, onTitle = vm::setTitle, onText = vm::setText, onDate = vm::setDate, onColor = vm::setColor,
            onPin = vm::togglePin, onPalette = vm::togglePalette, onDelete = vm::delete,
        ),
    )
}

/** Realbyte's Note editor (capture 06): date chip, Title and Note; palette and pin in the top bar. Back saves. */
@Composable
fun MemoEditContent(s: MemoEditState, actions: MemoActions = MemoActions()) {
    var picking by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val colors = Mb.colors
    Column(Modifier.fillMaxSize().background(colors.surface).systemBarsPadding().imePadding()) {
        MbTopBar(
            onBack = actions.onBack,
            title = { TitleText("Note") },
            actions = {
                IconButtonBox(R.drawable.ic_palette, "Color", actions.onPalette, tint = s.color?.let { Color(it) } ?: colors.textBody)
                IconButtonBox(R.drawable.ic_pin, if (s.pinned) "Unpin" else "Pin", actions.onPin, tint = if (s.pinned) colors.accent else colors.textBody)
                if (!s.isNew) IconButtonBox(R.drawable.ic_delete, "Delete", { confirmDelete = true }, tint = colors.textBody)
            },
        )
        Hairline(px = 2)
        if (s.paletteOpen) PaletteRow(s.color, actions.onColor)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = MbDimens.memoInset)) {
            Spacer(Modifier.height(18.dp))
            val shape = RoundedCornerShape(4.dp)
            Text(
                Formats.memoDate(s.date), style = MbType.memoDateChip, color = colors.textBody,
                modifier = Modifier.clip(shape).border(1.dp, colors.textBody, shape).clickable { picking = true }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(18.dp))
            MemoField(s.title, actions.onTitle, "Title", MbType.memoTitle, singleLine = true)
            Spacer(Modifier.height(14.dp))
            MemoField(s.text, actions.onText, "Note", MbType.memoText, singleLine = false)
        }
    }
    if (picking) DatePickDialog(s.date, onPick = { actions.onDate(it); picking = false }, onDismiss = { picking = false })
    if (confirmDelete) {
        ConfirmDialog("Delete note", "Delete this note?", "Delete", onConfirm = actions.onDelete, onDismiss = { confirmDelete = false })
    }
}

@Composable
private fun MemoField(value: String, onChange: (String) -> Unit, placeholder: String, style: TextStyle, singleLine: Boolean) {
    Box(Modifier.fillMaxWidth()) {
        if (value.isEmpty()) Text(placeholder, style = style, color = Mb.colors.textSecondary)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = singleLine,
            textStyle = style.copy(color = Mb.colors.textPrimary), cursorBrush = SolidColor(Mb.colors.accent),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder },
        )
    }
}

/** No color, then the first eight chart colors; the chosen one has a dark ring. */
@Composable
private fun PaletteRow(selected: Int?, onPick: (Int?) -> Unit) {
    val choices = listOf<Color?>(null) + Mb.colors.chartPalette.take(8)
    Column(Modifier.fillMaxWidth().background(Mb.colors.surface)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = MbDimens.memoInset, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            choices.forEachIndexed { i, color ->
                val argb = color?.toArgb()
                val ring = if (argb == selected) Mb.colors.textPrimary else Mb.colors.divider
                Box(
                    Modifier.size(MbDimens.paletteDot).clip(CircleShape).background(color ?: Mb.colors.surface)
                        .border(2.dp, ring, CircleShape).clickable { onPick(argb) }
                        .semantics { contentDescription = if (color == null) "No color" else "Color $i" },
                )
            }
        }
        Hairline()
    }
}
