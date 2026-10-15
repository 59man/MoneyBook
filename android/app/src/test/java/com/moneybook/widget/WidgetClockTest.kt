package com.moneybook.widget

import android.app.AlarmManager
import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class WidgetClockTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val alarms = app.getSystemService(AlarmManager::class.java)

    @Test fun theMonthRollsOverJustAfterMidnight() {
        val clock = WidgetClock(app)
        clock.scheduleNext(now = LocalDateTime.of(2026, 10, 31, 22, 15))
        val alarm = shadowOf(alarms).scheduledAlarms.single()
        assertEquals(LocalDateTime.of(2026, 11, 1, 0, 1).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), alarm.triggerAtMs)
        assertEquals(WidgetClock.WINDOW_MS, alarm.windowLengthMs)
        clock.cancel()
        assertEquals(0, shadowOf(alarms).scheduledAlarms.size)
    }

    @Test fun withoutASummaryWidgetThereIsNothingToRollOver() {
        assertFalse(WidgetClock.anySummary(app))
    }

    // Two providers draw the summary: removing the last 4×1 keeps the alarm while a 4×2 is still placed.
    @Test fun theAlarmStaysWhileTheOtherSizeIsPlaced() {
        // bindAppWidgetId, not createWidget: createWidget calls Glance's onUpdate directly, whose goAsync() is null there.
        shadowOf(AppWidgetManager.getInstance(app)).bindAppWidgetId(1, ComponentName(app, Summary4x2Receiver::class.java))
        val clock = WidgetClock(app)
        clock.scheduleNext()
        // Through a real broadcast: called directly, Glance's goAsync() is null and its finish() throws later on another thread.
        app.sendBroadcast(Intent(AppWidgetManager.ACTION_APPWIDGET_DISABLED).setComponent(ComponentName(app, Summary4x1Receiver::class.java)))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, shadowOf(alarms).scheduledAlarms.size)
        assertTrue(WidgetClock.anySummary(app))
    }
}
