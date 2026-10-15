package com.moneybook.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.moneybook.data.DeviceSettings
import com.moneybook.domain.nextReminder
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Alarm Setting: a one-shot alarm within a quarter hour of the time (no exact-alarm permission needed). ReminderReceiver
 * posts the reminder and sets the next day's. An inexact daily repeat was not used: Android lets it come 18 hours late.
 */
class ReminderScheduler(private val context: Context) {
    private val alarms get() = context.getSystemService(AlarmManager::class.java)

    /** The extras don't take part in matching, so cancel() finds the alarm whatever its minutes. */
    private fun operation(minutes: Int? = null): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, ReminderReceiver::class.java).apply { if (minutes != null) putExtra(EXTRA_MINUTES, minutes) },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun schedule(minutes: Int, now: LocalDateTime = LocalDateTime.now()) {
        val at = nextReminder(now, minutes).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        alarms.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, operation(minutes))
    }

    fun cancel() = alarms.cancel(operation())

    /** Follows the device settings: scheduled at its time while on, cancelled while off. */
    fun apply(s: DeviceSettings) = if (s.alarmOn) schedule(s.alarmMinutes) else cancel()

    companion object {
        /** The reminder's time (minutes after midnight), carried to ReminderReceiver for the next day's alarm. */
        const val EXTRA_MINUTES = "minutes"
        const val WINDOW_MS = 15 * 60_000L
    }
}
