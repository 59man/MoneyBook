package com.moneybook.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moneybook.R
import com.moneybook.data.StyleMode
import com.moneybook.ui.common.Hairline
import com.moneybook.ui.common.MbIcon
import com.moneybook.ui.common.MbTopBar
import com.moneybook.ui.common.RadioMark
import com.moneybook.ui.common.TitleText
import com.moneybook.ui.common.appContainer
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType

@Composable
fun StyleScreen(onBack: () -> Unit) {
    val c = appContainer()
    val vm: ConfigurationViewModel = viewModel { ConfigurationViewModel(c.settings, c.time) }
    val s by vm.state.collectAsStateWithLifecycle()
    val settings = s ?: return
    StyleContent(settings.style, onPick = { vm.change(settings.copy(style = it)) }, onBack = onBack)
}

/**
 * Configuration → Style (capture 45): System, Dark or Light mode, applied at once. Realbyte's color dots under
 * them are left out: no capture shows what they change.
 */
@Composable
fun StyleContent(style: StyleMode, onPick: (StyleMode) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Mb.colors.background).systemBarsPadding()) {
        MbTopBar(onBack = onBack, title = { TitleText("Style") })
        Hairline(px = 2)
        Column(Modifier.fillMaxWidth().background(Mb.colors.surface)) {
            StyleMode.entries.forEach { mode ->
                StyleModeRow(mode, mode == style) { onPick(mode) }
                Hairline()
            }
        }
    }
}

/** A System / Dark / Light Mode row with its icon and radio mark (Style, Widget Settings' Background Color). */
@Composable
internal fun StyleModeRow(mode: StyleMode, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = MbDimens.styleRow).clickable(onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = when (mode) {
            StyleMode.SYSTEM -> R.drawable.ic_system_mode
            StyleMode.DARK -> R.drawable.ic_dark_mode
            StyleMode.LIGHT -> R.drawable.ic_light_mode
        }
        MbIcon(icon, null, tint = Mb.colors.iconMuted, size = 28.dp)
        Spacer(Modifier.width(20.dp))
        Text(mode.label, style = MbType.dialogOption, color = Mb.colors.textBody, modifier = Modifier.weight(1f))
        RadioMark(selected)
    }
}
