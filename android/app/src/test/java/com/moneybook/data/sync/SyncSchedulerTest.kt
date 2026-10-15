package com.moneybook.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncSchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scheduler = SyncScheduler(context)

    @Before fun setUp() = WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())

    private fun infos(name: String): List<WorkInfo> = WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get()

    @Test fun soonHasFiveSecondDelayAndNetworkConstraint() {
        scheduler.enqueueSoon()
        val i = infos(SyncScheduler.SOON).single()
        assertEquals(TimeUnit.SECONDS.toMillis(5), i.initialDelayMillis)
        assertEquals(NetworkType.CONNECTED, i.constraints.requiredNetworkType)
    }

    @Test fun startNeedsNetworkAndNoDelay() {
        scheduler.enqueueStart()
        val i = infos(SyncScheduler.START).single()
        assertEquals(0L, i.initialDelayMillis)
        assertEquals(NetworkType.CONNECTED, i.constraints.requiredNetworkType)
    }

    @Test fun soonAskedAgainWhileQueuedIsChainedNotDropped() {
        scheduler.enqueueSoon()
        val first = infos(SyncScheduler.SOON).single().id
        scheduler.enqueueSoon()
        // APPEND_OR_REPLACE: the second request is appended to the first, so one asked for during a run still runs after it
        assertEquals(2, infos(SyncScheduler.SOON).size)
        assertTrue(first in infos(SyncScheduler.SOON).map { it.id })
    }

    @Test fun periodicIsFifteenMinutesAndUnique() {
        scheduler.enqueuePeriodic()
        val first = infos(SyncScheduler.PERIODIC).single()
        assertEquals(TimeUnit.MINUTES.toMillis(15), first.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(NetworkType.CONNECTED, first.constraints.requiredNetworkType)
        scheduler.enqueuePeriodic()
        assertEquals(first.id, infos(SyncScheduler.PERIODIC).single().id)
        assertNotNull(first.periodicityInfo)
    }

    @Test fun cancelAllRemovesAllThree() {
        scheduler.enqueueStart()
        scheduler.enqueueSoon()
        scheduler.enqueuePeriodic()
        scheduler.cancelAll()
        listOf(SyncScheduler.START, SyncScheduler.SOON, SyncScheduler.PERIODIC).forEach { name ->
            assertEquals(WorkInfo.State.CANCELLED, infos(name).single().state)
        }
    }
}
