package com.moneybook.widget

import androidx.compose.ui.graphics.Color
import androidx.glance.unit.ColorProvider
import com.moneybook.data.DeviceSettings
import com.moneybook.data.StyleMode
import com.moneybook.ui.theme.DarkWidgetPalette
import com.moneybook.ui.theme.LightWidgetPalette
import com.moneybook.ui.theme.WidgetPalette
import androidx.glance.color.ColorProvider as dayNight

/** Widget Settings turned into Glance colors. System follows the phone's night mode, not the app's Style. */
data class WidgetLook(val background: StyleMode = StyleMode.SYSTEM, val opacity: Int = 100) {
    fun color(pick: (WidgetPalette) -> Color): ColorProvider = provider(pick)

    /** The card behind the text, opaque: a tint's alpha only blends over the white shape, so [cardAlpha] is separate. */
    fun card(): ColorProvider = provider { it.card }

    /** Background Opacity: the card's alpha. It never touches the text. */
    val cardAlpha: Float get() = opacity.coerceIn(0, 100) / 100f

    private fun provider(pick: (WidgetPalette) -> Color): ColorProvider = when (background) {
        StyleMode.LIGHT -> ColorProvider(pick(LightWidgetPalette))
        StyleMode.DARK -> ColorProvider(pick(DarkWidgetPalette))
        StyleMode.SYSTEM -> dayNight(day = pick(LightWidgetPalette), night = pick(DarkWidgetPalette))
    }

    companion object {
        fun of(s: DeviceSettings) = WidgetLook(s.widgetBackground, s.widgetOpacity)
    }
}
