package com.moneybook.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** The three scheduled sync jobs, all with a network. Only used while signed in (Ruling 12). Open for the trigger tests' recorder. */
open class SyncScheduler(private val context: Context) {
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    private val work get() = WorkManager.getInstance(context)

    /** Unique one-time [START]. */
    open fun enqueueStart() {
        work.enqueueUniqueWork(START, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(network).build())
    }

    /** Unique one-time [SOON], 5 s from now; rows changed before it starts ride along, and one asked for during a run follows it. */
    open fun enqueueSoon() {
        work.enqueueUniqueWork(
            SOON, ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(network).setInitialDelay(5, TimeUnit.SECONDS).build(),
        )
    }

    /** Unique periodic [PERIODIC], every 15 minutes. */
    open fun enqueuePeriodic() {
        work.enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(network).build(),
        )
    }

    open fun cancelAll() {
        listOf(START, SOON, PERIODIC).forEach { work.cancelUniqueWork(it) }
    }

    companion object {
        const val START = "sync-start"
        const val SOON = "sync-soon"
        const val PERIODIC = "sync-periodic"
    }
}
