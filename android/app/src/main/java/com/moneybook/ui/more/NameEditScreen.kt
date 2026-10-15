package com.moneybook.ui.more

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.moneybook.ui.common.FilledButton
import com.moneybook.ui.common.Hairline
import com.moneybook.ui.common.MbTopBar
import com.moneybook.ui.common.TitleText
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType

/** Realbyte's "Change Category" form: one name field, an optional [below] block and Save. [onSave] gets the trimmed, non-blank name. */
@Composable
fun NameEditScreen(title: String, initial: String, onBack: () -> Unit, below: @Composable () -> Unit = {}, onSave: (String) -> Unit) {
    var name by rememberSaveable(initial) { mutableStateOf(initial) }
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().background(Mb.colors.surface).systemBarsPadding().imePadding()) {
        MbTopBar(onBack = onBack, title = { TitleText(title) })
        Hairline(px = 2)
        Column(Modifier.padding(start = 10.dp, end = 20.dp, top = 15.dp)) {
            BasicTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                textStyle = MbType.formValue.copy(color = Mb.colors.textPrimary),
                cursorBrush = SolidColor(Mb.colors.accent),
                modifier = Modifier.fillMaxWidth().heightIn(min = MbDimens.nameField).padding(start = 10.dp).wrapContentHeight()
                    .semantics { contentDescription = "Name" },
            )
            Hairline()
        }
        below()
        FilledButton("Save", Mb.colors.accent, Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, top = 36.dp, bottom = 15.dp)) {
            if (name.isBlank()) Toast.makeText(context, "Please enter a name.", Toast.LENGTH_SHORT).show() else onSave(name.trim())
        }
    }
}
