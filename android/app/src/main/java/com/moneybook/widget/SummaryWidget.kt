package com.moneybook.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.moneybook.MoneyBookApp
import com.moneybook.R
import com.moneybook.notify.Notifications
import com.moneybook.ui.common.money
import com.moneybook.ui.theme.MbType
import com.moneybook.ui.theme.WidgetColors
import com.moneybook.ui.theme.WidgetDimens
import kotlinx.coroutines.flow.map

/** Realbyte's 4×1 and 4×2 summary widget: Income / Expenses / Total tabs, This Month and vs. MoM (spec M2g). */
class SummaryWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val c = (context.applicationContext as MoneyBookApp).container
        val first = loadSummary(c.settings, c.txns, c.time.today())
        val firstLook = WidgetLook.of(c.device.current())
        val open = actionStartActivity(Notifications.openIntent(context, null))
        provideContent {
            // A session stays open for a while after each update, and update() then only recomposes it: the data and
            // the look are collected, so an open session follows every change too.
            val data by remember { summaryFlow(c.settings, c.txns) { c.time.today() } }.collectAsState(first)
            val look by remember { c.device.settings.map(WidgetLook::of) }.collectAsState(firstLook)
            SummaryContent(data, SummaryTab.from(currentState<Preferences>()[SummaryTab.KEY]), look, open)
        }
    }
}

/** A tab tap: remembered in this widget's own state, then only this widget is redrawn. */
class SetSummaryTab : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val tab = parameters[TAB] ?: return
        updateAppWidgetState(context, glanceId) { it[SummaryTab.KEY] = tab }
        SummaryWidget().update(context, glanceId)
    }

    companion object {
        val TAB = ActionParameters.Key<String>("tab")
    }
}

/** Both sizes draw the same card; the 4×2 centers it in its taller cell, as Realbyte's does. */
open class SummaryReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SummaryWidget()

    /** The first widget of this size: the month must roll over at midnight. */
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetClock(context).scheduleNext()
    }

    /** The last widget of this size is gone; the alarm stays while the other size is still placed. */
    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        if (!WidgetClock.anySummary(context)) WidgetClock(context).cancel()
    }
}

class Summary4x1Receiver : SummaryReceiver()

class Summary4x2Receiver : SummaryReceiver()

@Composable
fun SummaryContent(data: SummaryData, tab: SummaryTab, look: WidgetLook, openApp: Action) {
    val s = data.summary
    val spec = data.currency
    val text = look.color { it.text }
    val tabOn = ColorProvider(WidgetColors.tabOn)
    Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            GlanceModifier.fillMaxWidth()
                .background(ImageProvider(R.drawable.widget_card), alpha = look.cardAlpha, colorFilter = ColorFilter.tint(look.card()))
                .padding(horizontal = 12.dp),
        ) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                SummaryTab.entries.forEach { t ->
                    val on = t == tab
                    Column(GlanceModifier.width(WidgetDimens.tab), horizontalAlignment = Alignment.CenterHorizontally) {
                        // The label spans the tab, so the whole tab above the underline takes the tap.
                        Text(
                            t.label,
                            GlanceModifier.fillMaxWidth().padding(vertical = 12.dp)
                                .clickable(actionRunCallback<SetSummaryTab>(actionParametersOf(SetSummaryTab.TAB to t.name))),
                            style = TextStyle(color = if (on) tabOn else look.color { it.tabOff }, fontSize = MbType.widgetTab.fontSize, textAlign = TextAlign.Center),
                        )
                        Spacer(GlanceModifier.fillMaxWidth().height(2.dp).background(if (on) tabOn else look.color { it.line }))
                    }
                }
                Spacer(GlanceModifier.defaultWeight().height(2.dp).background(look.color { it.line }))
            }
            Row(GlanceModifier.fillMaxWidth().padding(vertical = 14.dp).clickable(openApp), verticalAlignment = Alignment.CenterVertically) {
                val amount = when (tab) {
                    SummaryTab.INCOME -> s.month.income
                    SummaryTab.EXPENSES -> s.month.expense
                    SummaryTab.TOTAL -> s.month.total
                }
                Column(GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (tab == SummaryTab.TOTAL) "Total" else "This Month", style = TextStyle(color = text, fontSize = MbType.widgetLabel.fontSize))
                    Text(
                        money(amount, spec, withSymbol = false),
                        style = TextStyle(color = text, fontSize = MbType.widgetAmount.fontSize, fontWeight = FontWeight.Bold),
                        maxLines = 1,
                    )
                }
                Column(GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (tab == SummaryTab.TOTAL) {
                        TotalLine("Income", money(s.month.income, spec, withSymbol = false), look)
                        TotalLine("Expenses", money(s.month.expense, spec, withSymbol = false), look)
                    } else {
                        val m = if (tab == SummaryTab.INCOME) s.income else s.expense
                        val tone = when (m.tone()) {
                            MomTone.GOOD -> ColorProvider(WidgetColors.good)
                            MomTone.BAD -> ColorProvider(WidgetColors.bad)
                            MomTone.NONE -> look.color { it.muted }
                        }
                        Text(momText(m, spec), style = TextStyle(color = tone, fontSize = MbType.widgetMom.fontSize), maxLines = 1)
                        Text("vs. MoM", style = TextStyle(color = look.color { it.muted }, fontSize = MbType.widgetMomLabel.fontSize))
                    }
                }
            }
        }
    }
}

@Composable
private fun TotalLine(label: String, value: String, look: WidgetLook) {
    Row(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, GlanceModifier.defaultWeight(), style = TextStyle(color = look.color { it.text }, fontSize = MbType.widgetTotalValue.fontSize))
        Text(
            value,
            style = TextStyle(color = look.color { it.text }, fontSize = MbType.widgetTotalValue.fontSize, textAlign = TextAlign.End),
        )
    }
}
