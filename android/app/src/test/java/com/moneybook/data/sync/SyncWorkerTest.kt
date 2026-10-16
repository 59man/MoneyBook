package com.moneybook.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker.Result
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val original = SyncWorker.runner

    @Before fun setUp() = WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())

    @After fun restore() {
        SyncWorker.runner = original
    }

    private fun run(outcome: SyncOutcome): Result {
        SyncWorker.runner = { { outcome } }
        return runBlocking { TestListenableWorkerBuilder<SyncWorker>(context).build().doWork() }
    }

    @Test fun successMapsToSuccess() = assertEquals(Result.success(), run(SyncOutcome.Success))

    @Test fun offlineAndServerRetry() {
        assertEquals(Result.retry(), run(SyncOutcome.Failure(SyncError.Offline(null))))
        assertEquals(Result.retry(), run(SyncOutcome.Failure(SyncError.Server(503))))
        assertEquals(Result.retry(), run(SyncOutcome.Failure(SyncError.VpnOff)))
        assertEquals(Result.retry(), run(SyncOutcome.Failure(SyncError.Forbidden))) // stays signed in, tried again later
    }

    @Test fun otherFailuresDoNotRetry() {
        assertEquals(Result.success(), run(SyncOutcome.Failure(SyncError.Rejected(400, "bad_request"))))
        assertEquals(Result.success(), run(SyncOutcome.Failure(SyncError.Other(null))))
    }

    @Test fun signedOutWorkerCancelsItselfAndSucceeds() {
        SyncScheduler(context).enqueuePeriodic()
        assertEquals(Result.success(), run(SyncOutcome.Failure(SyncError.Unauthorized)))
        val info = WorkManager.getInstance(context).getWorkInfosForUniqueWork(SyncScheduler.PERIODIC).get().single()
        assertEquals(WorkInfo.State.CANCELLED, info.state)
    }
}
