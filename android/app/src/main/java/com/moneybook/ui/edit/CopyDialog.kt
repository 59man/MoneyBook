package com.moneybook.ui.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.moneybook.R
import com.moneybook.data.CopyDateBy
import com.moneybook.ui.common.Hairline
import com.moneybook.ui.common.MbIcon
import com.moneybook.ui.common.LocalAppLocked
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType

/**
 * Edit → Copy, as measured on realbyte_34: an 80 % wide card with the title (no ✕) and the two option rows (the stored choice
 * in the accent color with a check, `dialog_common_adapter_check_list_item`), and OK on a card of its own below. A row tap
 * only moves the check and stores the choice at once; OK opens the copy. A tap outside and Back cancel.
 */
@Composable
fun CopyDialog(selected: CopyDateBy, onSelect: (CopyDateBy) -> Unit, onDismiss: () -> Unit, onOk: () -> Unit) {
    if (LocalAppLocked.current) return // a window of its own, above the lock screen
    val shape = RoundedCornerShape(MbDimens.choiceCorner)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(MbDimens.copyWidth)) {
            Column(Modifier.fillMaxWidth().clip(shape).background(Mb.colors.surface)) {
                Box(Modifier.fillMaxWidth().heightIn(min = MbDimens.choiceTitle).padding(start = 23.dp), contentAlignment = Alignment.CenterStart) {
                    Text("Copy", style = MbType.choiceTitle, color = Mb.colors.textPrimary)
                }
                CopyDateBy.entries.forEach { by ->
                    Hairline()
                    val on = by == selected
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = MbDimens.choiceRow).clickable { onSelect(by) }.padding(start = 23.dp, end = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(by.label, style = MbType.choiceText, color = if (on) Mb.colors.accent else Mb.colors.textPrimary, modifier = Modifier.weight(1f))
                        if (on) MbIcon(R.drawable.ic_check, "Selected", tint = Mb.colors.accent, size = 18.dp)
                    }
                }
            }
            Spacer(Modifier.height(MbDimens.copyGap))
            Box(
                Modifier.fillMaxWidth().heightIn(min = MbDimens.copyOk).clip(shape).background(Mb.colors.surface)
                    .border(1.dp, Mb.colors.divider, shape).clickable(onClick = onOk),
                contentAlignment = Alignment.Center,
            ) {
                Text("OK", style = MbType.choiceText, color = Mb.colors.textBody)
            }
        }
    }
}
