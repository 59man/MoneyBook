package com.moneybook.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** The summary widget's card: light measured on Realbyte's widget (realbyte_34 emulator), dark from the app's palette. */
data class WidgetPalette(val card: Color, val text: Color, val muted: Color, val tabOff: Color, val line: Color)

val LightWidgetPalette = WidgetPalette(
    card = Color.White, text = Color.Black, muted = Color(0xFF666666), tabOff = Color(0xFF7F7F7F), line = Color(0xFFD9D9D9),
)

val DarkWidgetPalette = WidgetPalette(
    card = DarkMbColors.surface, text = Color.White, muted = DarkMbColors.textSecondary,
    tabOff = DarkMbColors.tabUnselected, line = DarkMbColors.divider,
)

/** Colors that don't change with the widget's background (Realbyte's). The preview layouts repeat them in res/values/colors.xml. */
object WidgetColors {
    val tabOn = Color(0xFFFF603B)
    val good = Color(0xFF20BE00)
    val bad = Color(0xFFF64A3B)
    val redPanel = Color(0xFFF2443E)
    val redTile = Color(0xFFF4605B)
}

object WidgetDimens {
    val tab = 78.dp
}
