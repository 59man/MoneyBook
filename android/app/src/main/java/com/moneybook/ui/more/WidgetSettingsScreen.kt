package com.moneybook.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moneybook.data.DeviceSettings
import com.moneybook.data.StyleMode
import com.moneybook.domain.Mom
import com.moneybook.ui.common.FitText
import com.moneybook.ui.common.Hairline
import com.moneybook.ui.common.MbTopBar
import com.moneybook.ui.common.SettingsSection
import com.moneybook.ui.common.TitleText
import com.moneybook.ui.common.appContainer
import com.moneybook.ui.common.money
import com.moneybook.ui.theme.DarkWidgetPalette
import com.moneybook.ui.theme.LightWidgetPalette
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType
import com.moneybook.ui.theme.WidgetColors
import com.moneybook.ui.theme.WidgetDimens
import com.moneybook.ui.theme.WidgetPalette
import com.moneybook.widget.MomTone
import com.moneybook.widget.SummaryData
import com.moneybook.widget.SummaryTab
import com.moneybook.widget.loadSummary
import com.moneybook.widget.momText
import com.moneybook.widget.tone
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun WidgetSettingsScreen(onBack: () -> Unit) {
    val c = appContainer()
    val device by c.device.settings.collectAsStateWithLifecycle(initialValue = DeviceSettings())
    val data by produceState<SummaryData?>(null) { value = loadSummary(c.settings, c.txns, c.time.today()) }
    val scope = rememberCoroutineScope()
    // The widgets follow by themselves: WidgetUpdater redraws them on every device settings change.
    WidgetSettingsContent(
        data, device.widgetBackground, device.widgetOpacity,
        onBackground = { b -> scope.launch { c.device.update { it.copy(widgetBackground = b) } } },
        onOpacity = { o -> scope.launch { c.device.update { it.copy(widgetOpacity = o) } } },
        onBack = onBack,
    )
}

/** Configuration → Widget Settings (capture 55): a live preview, Background Color and Background Opacity. */
@OptIn(ExperimentalMaterial3Api::class) // Slider's thumb and track slots
@Composable
fun WidgetSettingsContent(
    data: SummaryData?,
    background: StyleMode,
    opacity: Int,
    onBackground: (StyleMode) -> Unit,
    onOpacity: (Int) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Mb.colors.background).systemBarsPadding()) {
        MbTopBar(onBack = onBack, title = { TitleText("Widget Settings") })
        Hairline(px = 2)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            // Realbyte's patterned background is their artwork; MoneyBook's is plain accent.
            Box(Modifier.fillMaxWidth().background(Mb.colors.accent).padding(horizontal = 16.dp, vertical = 40.dp)) {
                if (data != null) SummaryPreview(data, background, opacity)
            }
            SettingsSection("Background Color")
            Column(Modifier.fillMaxWidth().background(Mb.colors.surface)) {
                StyleMode.entries.forEach { mode ->
                    StyleModeRow(mode, mode == background) { onBackground(mode) }
                    Hairline()
                }
            }
            SettingsSection("Background Opacity")
            Row(
                Modifier.fillMaxWidth().background(Mb.colors.surface).heightIn(min = MbDimens.styleRow).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("$opacity%", style = MbType.dialogOption, color = Mb.colors.textBody, modifier = Modifier.width(64.dp))
                // Capture 55: a thin gray line and a white round knob; the value snaps to 5 % steps without tick marks.
                Slider(
                    value = opacity.toFloat(),
                    onValueChange = { onOpacity(((it / 5).roundToInt() * 5).coerceIn(0, 100)) },
                    valueRange = 0f..100f,
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Background Opacity" },
                    thumb = {
                        Box(Modifier.size(MbDimens.sliderKnob).shadow(2.dp, CircleShape).background(Color.White, CircleShape))
                    },
                    track = { Box(Modifier.fillMaxWidth().height(2.dp).background(Mb.colors.divider)) },
                )
            }
        }
    }
}

/** The summary widget's Expenses tab, drawn with Compose (Glance can't draw inside the app) in the chosen look. */
@Composable
fun SummaryPreview(data: SummaryData, background: StyleMode, opacity: Int) {
    val dark = when (background) {
        StyleMode.SYSTEM -> isSystemInDarkTheme()
        StyleMode.DARK -> true
        StyleMode.LIGHT -> false
    }
    val p = if (dark) DarkWidgetPalette else LightWidgetPalette
    val s = data.summary
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.card.copy(alpha = opacity.coerceIn(0, 100) / 100f))
            .padding(horizontal = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            SummaryTab.entries.forEach { t ->
                val on = t == SummaryTab.EXPENSES
                Column(Modifier.width(WidgetDimens.tab), horizontalAlignment = Alignment.CenterHorizontally) {
                    // A fixed-width slot: the label shrinks at large font sizes instead of breaking.
                    FitText(
                        t.label, MbType.widgetTab, if (on) WidgetColors.tabOn else p.tabOff,
                        Modifier.padding(horizontal = 4.dp, vertical = 12.dp), textAlign = TextAlign.Center,
                    )
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (on) WidgetColors.tabOn else p.line))
                }
            }
            Box(Modifier.weight(1f).height(2.dp).background(p.line))
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("This Month", style = MbType.widgetLabel, color = p.text)
                FitText(money(s.month.expense, data.currency, withSymbol = false), MbType.widgetAmount, p.text)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                FitText(momText(s.expense, data.currency), MbType.widgetMom, toneColor(s.expense, p))
                Text("vs. MoM", style = MbType.widgetMomLabel, color = p.muted)
            }
        }
    }
}

private fun toneColor(m: Mom, p: WidgetPalette): Color = when (m.tone()) {
    MomTone.GOOD -> WidgetColors.good
    MomTone.BAD -> WidgetColors.bad
    MomTone.NONE -> p.muted
}
