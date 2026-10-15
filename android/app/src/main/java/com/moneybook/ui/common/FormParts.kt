package com.moneybook.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.moneybook.R
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType

/** One "Label | value" line of Realbyte's input forms, underlined; the underline turns colored while active. */
@Composable
fun FormRow(
    label: String,
    active: Boolean = false,
    activeColor: Color = Mb.colors.accent,
    onClick: (() -> Unit)? = null,
    /** Account Info's "Settlement\nDate" and "Payment\nDate" take two lines. */
    labelLines: Int = 1,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = MbDimens.formRow).height(IntrinsicSize.Min)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            // Top padding lowers the text 2 dp toward the underline, as in Realbyte.
            .padding(start = 17.dp, end = 13.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Grows past the label column instead of breaking the word (large fonts, wider system fonts).
        Text(label, style = MbType.formLabel, color = Mb.colors.textTertiary, maxLines = labelLines, modifier = Modifier.widthIn(min = MbDimens.formLabelWidth))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.weight(1f).fillMaxWidth().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically, content = content)
            if (active) Box(Modifier.fillMaxWidth().height(1.dp).background(activeColor)) else Hairline()
        }
    }
}

@Composable
fun ValueText(text: String?, modifier: Modifier = Modifier) {
    Text(text.orEmpty(), style = MbType.formValue, color = Mb.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

@Composable
fun FilledButton(text: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 45.dp).clip(RoundedCornerShape(6.dp)).background(color).clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        FitText(text, MbType.button, Color.White)
    }
}

@Composable
fun OutlinedButtonBox(text: String, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier.heightIn(min = 45.dp).clip(shape).border(1.dp, Mb.colors.textSecondary, shape).clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            MbIcon(icon, null, tint = Mb.colors.textBody, size = 20.dp)
            Spacer(Modifier.width(6.dp))
        }
        FitText(text, MbType.button, Mb.colors.textPrimary)
    }
}

/** Black header bar of Realbyte's bottom panels (keypad, pickers); no ✕ when [onClose] is null. */
@Composable
fun PanelHeader(title: String, onClose: (() -> Unit)?, titleInset: Dp = 16.dp, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().height(MbDimens.panelHeader).background(Mb.colors.keypadHeader).padding(start = titleInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MbType.panelTitle, color = Mb.colors.keypadHeaderText, modifier = Modifier.weight(1f))
        actions()
        if (onClose != null) IconButtonBox(R.drawable.ic_close, "Close", onClose, tint = Mb.colors.keypadHeaderText)
    }
}
