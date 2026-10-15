package com.moneybook.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.moneybook.MoneyBookApp

/**
 * Runs [SyncEngine.sync]. Offline and server errors retry (WorkManager's backoff); anything else is a success for
 * WorkManager and shows on the Sync screen. A signed-out device (the engine answers Unauthorized, also after a
 * revoked session) has nothing left to schedule, so the work cancels itself.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (val outcome = runner(applicationContext)()) {
        SyncOutcome.Success -> Result.success()
        is SyncOutcome.Failure -> when (outcome.error) {
            is SyncError.Offline, is SyncError.Server -> Result.retry()
            SyncError.Unauthorized -> {
                SyncScheduler(applicationContext).cancelAll()
                Result.success()
            }
            else -> Result.success()
        }
    }

    companion object {
        /** Tests replace it. */
        var runner: (Context) -> suspend () -> SyncOutcome = { c -> (c.applicationContext as MoneyBookApp).container.sync::sync }
    }
}
