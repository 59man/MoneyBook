package com.moneybook.notify

import android.annotation.SuppressLint
import android.content.Context
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.moneybook.MainActivity
import com.moneybook.R
import com.moneybook.data.DeviceSettings

/** Configuration → Quick add: an ongoing, silent bar of shortcuts in the notification shade (capture 54, without the SMS button). */
class QuickAddNotifier(private val context: Context) {
    @SuppressLint("MissingPermission") // checked by Notifications.allowed()
    fun show() {
        if (!Notifications.allowed(context)) return
        Notifications.ensureChannels(context)
        val bar = RemoteViews(context.packageName, R.layout.quick_add).apply {
            setOnClickPendingIntent(R.id.qa_open, Notifications.openApp(context, null, 10))
            setOnClickPendingIntent(R.id.qa_search, Notifications.openApp(context, MainActivity.OPEN_SEARCH, 11))
            setOnClickPendingIntent(R.id.qa_bookmarks, Notifications.openApp(context, MainActivity.OPEN_BOOKMARKS, 12))
            setOnClickPendingIntent(R.id.qa_add, Notifications.openApp(context, MainActivity.OPEN_ADD, 13))
        }
        val notification = NotificationCompat.Builder(context, Notifications.QUICK_ADD_CHANNEL)
            .setSmallIcon(R.drawable.ic_wallet)
            .setCustomContentView(bar)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setContentIntent(Notifications.openApp(context))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        NotificationManagerCompat.from(context).notify(Notifications.QUICK_ADD_ID, notification)
    }

    fun hide() = NotificationManagerCompat.from(context).cancel(Notifications.QUICK_ADD_ID)

    fun apply(s: DeviceSettings) = if (s.quickAdd) show() else hide()
}
