package com.moneybook.widget

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.unit.ColorProvider
import com.moneybook.MainActivity
import com.moneybook.R
import com.moneybook.notify.Notifications
import com.moneybook.ui.theme.WidgetColors

enum class QuickAddSize { TWO_BY_ONE, TWO_BY_TWO, FOUR_BY_ONE }

/** Realbyte's two versions: an accent panel with white icons, or a white panel with accent icons. */
enum class QuickAddStyle { RED, WHITE }

/** A button: its icon, its TalkBack label and the screen MainActivity opens (null: the app as it is). */
enum class QuickButton(@param:DrawableRes val icon: Int, val label: String, val open: String?) {
    APP(R.drawable.ic_wallet, "Open MoneyBook", null),
    SEARCH(R.drawable.ic_search, "Search", MainActivity.OPEN_SEARCH),
    INCOME(R.drawable.ic_add, "Income", MainActivity.OPEN_ADD_INCOME),
    EXPENSE(R.drawable.ic_remove, "Expense", MainActivity.OPEN_ADD_EXPENSE),
    BOOKMARKS(R.drawable.ic_star, "Bookmarks", MainActivity.OPEN_BOOKMARKS),
}

/** The rows of buttons per size, as in Realbyte's widget previews. */
fun quickAddRows(size: QuickAddSize): List<List<QuickButton>> = when (size) {
    QuickAddSize.TWO_BY_ONE -> listOf(listOf(QuickButton.INCOME, QuickButton.EXPENSE))
    QuickAddSize.TWO_BY_TWO -> listOf(listOf(QuickButton.INCOME, QuickButton.EXPENSE), listOf(QuickButton.BOOKMARKS))
    QuickAddSize.FOUR_BY_ONE -> listOf(
        listOf(QuickButton.APP, QuickButton.SEARCH, QuickButton.INCOME, QuickButton.EXPENSE, QuickButton.BOOKMARKS),
    )
}

/** The quick add widgets. They show no data, so nothing ever redraws them. */
open class QuickAddWidget(private val size: QuickAddSize, private val style: QuickAddStyle) : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { QuickAddContent(size, style) { actionStartActivity(Notifications.openIntent(context, it.open)) } }
    }
}

class QuickAdd2x1Receiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickAddWidget(QuickAddSize.TWO_BY_ONE, QuickAddStyle.RED)
}

class QuickAdd2x1WhiteReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickAddWidget(QuickAddSize.TWO_BY_ONE, QuickAddStyle.WHITE)
}

class QuickAdd2x2Receiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickAddWidget(QuickAddSize.TWO_BY_TWO, QuickAddStyle.RED)
}

class QuickAdd2x2WhiteReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickAddWidget(QuickAddSize.TWO_BY_TWO, QuickAddStyle.WHITE)
}

class QuickAdd4x1Receiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickAddWidget(QuickAddSize.FOUR_BY_ONE, QuickAddStyle.RED)
}

class QuickAdd4x1WhiteReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickAddWidget(QuickAddSize.FOUR_BY_ONE, QuickAddStyle.WHITE)
}

@Composable
fun QuickAddContent(size: QuickAddSize, style: QuickAddStyle, action: (QuickButton) -> Action) {
    val red = style == QuickAddStyle.RED
    val icon = ColorProvider(if (red) Color.White else WidgetColors.redPanel)
    Column(
        GlanceModifier.fillMaxSize().background(ImageProvider(if (red) R.drawable.widget_panel_red else R.drawable.widget_panel_white))
            .padding(4.dp),
    ) {
        quickAddRows(size).forEach { row ->
            Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                row.forEach { b ->
                    Box(GlanceModifier.defaultWeight().fillMaxHeight().padding(4.dp)) {
                        Box(
                            GlanceModifier.fillMaxSize()
                                .background(ImageProvider(if (red) R.drawable.widget_tile_red else R.drawable.widget_tile_white))
                                .clickable(action(b))
                                .semantics { contentDescription = b.label },
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(ImageProvider(b.icon), contentDescription = null, modifier = GlanceModifier.size(26.dp), colorFilter = ColorFilter.tint(icon))
                        }
                    }
                }
            }
        }
    }
}
