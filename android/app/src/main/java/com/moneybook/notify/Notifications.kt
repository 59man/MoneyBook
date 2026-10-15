package com.moneybook.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.moneybook.MainActivity
import com.moneybook.R

/** MoneyBook's two notifications: the daily reminder (Alarm Setting) and the ongoing quick add bar. */
object Notifications {
    const val REMINDER_CHANNEL = "reminders"
    const val QUICK_ADD_CHANNEL = "quick_add"
    const val REMINDER_ID = 1
    const val QUICK_ADD_ID = 2

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(REMINDER_CHANNEL, "Reminders", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(
            NotificationChannel(QUICK_ADD_CHANNEL, "Quick add", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
    }

    /** Whether MoneyBook may post: Android 13+ also needs the user's POST_NOTIFICATIONS grant. */
    fun allowed(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    /** MainActivity at [open] (MainActivity.OPEN_*; null: as it is). The data URI tells each target's intent apart. */
    fun openIntent(context: Context, open: String?): Intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        data = Uri.parse("moneybook://open/${open ?: "app"}")
        if (open != null) putExtra(MainActivity.EXTRA_OPEN, open)
    }

    /** Opens MoneyBook, optionally at [open] (MainActivity.OPEN_*); each target needs its own [requestCode]. */
    fun openApp(context: Context, open: String? = null, requestCode: Int = 0): PendingIntent =
        PendingIntent.getActivity(context, requestCode, openIntent(context, open), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    @SuppressLint("MissingPermission") // checked by allowed()
    fun showReminder(context: Context) {
        if (!allowed(context)) return
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, REMINDER_CHANNEL)
            .setSmallIcon(R.drawable.ic_wallet)
            .setContentTitle("MoneyBook")
            .setContentText("Did you record today's money?")
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(REMINDER_ID, notification)
    }
}
