package com.moneybook.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.moneybook.MoneyBookApp

/** The daily alarm: posts the reminder and sets the next day's alarm. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Notifications.showReminder(context)
        val minutes = intent.getIntExtra(ReminderScheduler.EXTRA_MINUTES, -1)
        if (minutes >= 0) ReminderScheduler(context).schedule(minutes)
    }
}

/** After a reboot: re-arms the reminder and re-posts quick add, if they are on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        (context.applicationContext as MoneyBookApp).applyDeviceSettings { pending.finish() }
    }
}
