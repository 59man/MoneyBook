package com.moneybook.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.moneybook.R
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType

/** One tappable line of a settings list (Realbyte's Configuration and Backup screens). [value] shows on the right in accent. */
@Composable
fun SettingsRow(title: String, value: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Mb.colors.surface).clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = MbDimens.listRow).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FitText(title, MbType.listItem, if (enabled) Mb.colors.textPrimary else Mb.colors.textSecondary, Modifier.weight(1f), maxLines = 2)
        if (value != null) Text(value, style = MbType.listItem, color = Mb.colors.accent)
    }
}

/** A [SettingsRow]-sized line with a switch at the right (Passcode → Biometrics). */
@Composable
fun SettingsSwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Mb.colors.surface).clickable { onChange(!checked) }
            .heightIn(min = MbDimens.listRow).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FitText(title, MbType.listItem, Mb.colors.textPrimary, Modifier.weight(1f), maxLines = 2)
        Switch(
            checked = checked, onCheckedChange = onChange, modifier = Modifier.semantics { contentDescription = title },
            colors = SwitchDefaults.colors(checkedTrackColor = Mb.colors.accent, checkedThumbColor = Color.White),
        )
    }
}

/** A run of [SettingsRow]s. Realbyte draws no lines between them, only one under the last. */
@Composable
fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        content()
        Hairline()
    }
}

/** Gray band that titles a group of rows ("Category/Repeat", "Reset"); its text sits below the middle. */
@Composable
fun SettingsSection(title: String) {
    Column(Modifier.fillMaxWidth().background(Mb.colors.background)) {
        Text(
            title, style = MbType.listItem, color = Mb.colors.textTertiary,
            modifier = Modifier.fillMaxWidth().heightIn(min = MbDimens.listRow).padding(start = 23.dp, end = 20.dp, top = 11.dp)
                .wrapContentHeight(),
        )
        Hairline(px = 2)
    }
}

/** The bordered gray strip above Realbyte's reorderable lists (category and account group settings). */
@Composable
fun ListTopGap() {
    Column(Modifier.fillMaxWidth().background(Mb.colors.background)) {
        Spacer(Modifier.height(MbDimens.listTopGap))
        Hairline()
    }
}

/** Realbyte's red "−" circle at the start of rows that can be deleted. */
@Composable
fun RemoveBadge(contentDescription: String, rotated: Boolean = false, onClick: () -> Unit) {
    val angle by animateFloatAsState(if (rotated) 90f else 0f, label = "removeBadge")
    Box(
        Modifier.width(MbDimens.removeSlot).fillMaxHeight().clickable(onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(18.dp).rotate(angle).clip(CircleShape).background(Mb.colors.danger), contentAlignment = Alignment.Center) {
            Box(Modifier.size(width = 9.dp, height = 2.dp).background(Color.White))
        }
    }
}

/** Realbyte's red delete box that slides in at the right of a row once its [RemoveBadge] is tapped. */
@Composable
fun TrashBox(visible: Boolean, contentDescription: String, onClick: () -> Unit) {
    AnimatedVisibility(visible, enter = slideInHorizontally { it }, exit = slideOutHorizontally { it }) {
        Box(
            Modifier.width(MbDimens.trashBox).fillMaxHeight().background(Mb.colors.danger).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { MbIcon(R.drawable.ic_delete, contentDescription, Color.White) }
    }
}

/** Asks before a destructive action; [confirm] is the red button's label. */
@Composable
fun ConfirmDialog(title: String, message: String, confirm: String, dismiss: String = "Cancel", onConfirm: () -> Unit, onDismiss: () -> Unit) {
    if (LocalAppLocked.current) return // a window of its own, above the lock screen
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirm, color = Mb.colors.danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismiss, color = Mb.colors.textBody) } },
    )
}

@Composable
fun MessageDialog(title: String, message: String, onDismiss: () -> Unit) {
    if (LocalAppLocked.current) return // a window of its own, above the lock screen
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK", color = Mb.colors.accent) } },
    )
}

/**
 * Realbyte's option dialog (capture 70): a white card with a bold title and ✕, an optional [description], then
 * one row per option between hairlines; the selected one is in the accent color with a check. Long lists scroll.
 */
@Composable
fun ChoiceDialog(
    title: String,
    options: List<String>,
    selected: Int? = null,
    onDismiss: () -> Unit,
    description: String? = null,
    illustration: (@Composable () -> Unit)? = null,
    onChoose: (Int) -> Unit,
) {
    if (LocalAppLocked.current) return // a window of its own, above the lock screen
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(MbDimens.choiceWidth).clip(RoundedCornerShape(MbDimens.choiceCorner)).background(Mb.colors.surface)) {
            Row(Modifier.fillMaxWidth().heightIn(min = MbDimens.choiceTitle).height(IntrinsicSize.Min).padding(start = 21.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MbType.choiceTitle, color = Mb.colors.textPrimary, modifier = Modifier.weight(1f))
                IconButtonBox(R.drawable.ic_close, "Close", onDismiss, width = 57.5.dp, iconSize = 22.dp)
            }
            if (description != null) {
                Text(
                    description, style = MbType.choiceText, color = Mb.colors.textBody,
                    modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 13.dp),
                )
            }
            if (illustration != null) {
                Box(Modifier.fillMaxWidth().padding(start = 23.dp, end = 23.dp, bottom = 25.dp)) { illustration() }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                options.forEachIndexed { i, label ->
                    Hairline()
                    val on = i == selected
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = MbDimens.choiceRow).clickable { onChoose(i) }.padding(start = 23.dp, end = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, style = MbType.choiceText, color = if (on) Mb.colors.accent else Mb.colors.textPrimary, modifier = Modifier.weight(1f))
                        if (on) MbIcon(R.drawable.ic_check, null, tint = Mb.colors.accent, size = 18.dp)
                    }
                }
            }
        }
    }
}

/** Realbyte's radio button: a gray ring, or an accent ring with an accent dot when selected (captures 45, 67). */
@Composable
fun RadioMark(selected: Boolean) {
    Box(
        Modifier.size(MbDimens.radio).border(2.dp, if (selected) Mb.colors.accent else Mb.colors.divider, CircleShape)
            .semantics { contentDescription = if (selected) "Selected" else "Not selected" },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(MbDimens.radio / 2).clip(CircleShape).background(Mb.colors.accent))
    }
}

/** Realbyte's ≡ reorder handle; put the ReorderableColumn handle modifier on it. */
@Composable
fun DragHandle(handle: Modifier, contentDescription: String) {
    Box(
        handle.width(MbDimens.rowIcon).fillMaxHeight().semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        MbIcon(R.drawable.ic_drag, null, tint = Mb.colors.iconMuted, size = 19.dp)
    }
}

/** Realbyte's gray pencil in category and account group rows. */
@Composable
fun EditPencil(contentDescription: String, onClick: () -> Unit) {
    IconButtonBox(R.drawable.ic_edit, contentDescription, onClick, width = MbDimens.rowIcon, tint = Mb.colors.iconMuted, iconSize = 22.dp)
}
