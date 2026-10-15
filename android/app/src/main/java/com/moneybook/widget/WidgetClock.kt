package com.moneybook.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.moneybook.MoneyBookApp
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * A one-shot alarm just after midnight (a 10-minute window, no exact-alarm permission) that redraws the summary
 * widgets, so This Month and the vs. MoM span move on. Set while any summary widget is placed.
 */
class WidgetClock(private val context: Context) {
    private val alarms get() = context.getSystemService(AlarmManager::class.java)

    private fun operation(): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, MidnightReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun scheduleNext(now: LocalDateTime = LocalDateTime.now()) {
        val at = now.toLocalDate().plusDays(1).atTime(0, 1).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        alarms.setWindow(AlarmManager.RTC, at, WINDOW_MS, operation())
    }

    fun cancel() = alarms.cancel(operation())

    companion object {
        const val WINDOW_MS = 10 * 60_000L

        /** Whether a summary widget of either size is on the home screen. */
        fun anySummary(context: Context): Boolean {
            val manager = AppWidgetManager.getInstance(context)
            return listOf(Summary4x1Receiver::class.java, Summary4x2Receiver::class.java)
                .any { manager.getAppWidgetIds(ComponentName(context, it)).isNotEmpty() }
        }
    }
}

/** Midnight: redraws the summary widgets and sets the next night's alarm. */
class MidnightReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending: PendingResult? = goAsync()
        val app = context.applicationContext as MoneyBookApp
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                runCatching { app.container.widgets.updateAll() }
                WidgetClock(context).scheduleNext()
            } finally {
                pending?.finish()
            }
        }
    }
}
