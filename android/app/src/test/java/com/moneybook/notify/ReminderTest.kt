package com.moneybook.notify

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class ReminderTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val alarms = app.getSystemService(AlarmManager::class.java)
    private val notifications = app.getSystemService(NotificationManager::class.java)

    @Before fun grant() = shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    // An inexact daily repeat may come up to 18 hours late, so the reminder is a one-shot alarm with a short window.
    @Test fun theReminderComesWithinAQuarterHourOfTheNextTime() {
        val scheduler = ReminderScheduler(app)
        scheduler.schedule(21 * 60, now = LocalDateTime.of(2026, 10, 6, 20, 30))
        val alarm = shadowOf(alarms).scheduledAlarms.single()
        assertEquals(LocalDateTime.of(2026, 10, 6, 21, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), alarm.triggerAtMs)
        assertEquals(0L, alarm.intervalMs)
        assertTrue(alarm.windowLengthMs in 1..15 * 60_000L)
        scheduler.cancel()
        assertEquals(0, shadowOf(alarms).scheduledAlarms.size)
    }

    @Test fun theReceiverPostsTheReminder() {
        ReminderReceiver().onReceive(app, Intent())
        val posted = shadowOf(notifications).allNotifications.single()
        assertEquals("MoneyBook", shadowOf(posted).contentTitle)
        assertEquals("Did you record today's money?", shadowOf(posted).contentText)
    }

    @Test fun theReminderSetsTheNextDaysAlarm() {
        ReminderScheduler(app).schedule(21 * 60)
        val fired = shadowOf(alarms).scheduledAlarms.single()
        shadowOf(alarms).scheduledAlarms.clear()
        ReminderReceiver().onReceive(app, shadowOf(fired.operation).savedIntent)
        val next = shadowOf(alarms).scheduledAlarms.single()
        val now = System.currentTimeMillis()
        assertTrue(next.triggerAtMs > now && next.triggerAtMs <= now + AlarmManager.INTERVAL_DAY)
        assertEquals(1, shadowOf(notifications).allNotifications.size)
    }

    @Test fun withoutPermissionNothingIsPosted() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ReminderReceiver().onReceive(app, Intent())
        assertEquals(0, shadowOf(notifications).allNotifications.size)
    }
}
