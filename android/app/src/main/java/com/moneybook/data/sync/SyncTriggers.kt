package com.moneybook.data.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Schedules sync work while the device is signed in and no merge-or-replace question is open: periodic and start work,
 * and a run 5 s after the rows become dirty. Signing out cancels it; a device that was never signed in since start
 * does not touch WorkManager at all (Ruling 12).
 */
class SyncTriggers(
    private val signedIn: Flow<Boolean>,
    private val pending: Flow<Boolean>,
    private val hasDirty: Flow<Boolean>,
    private val scheduler: SyncScheduler,
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            var wasActive = false
            combine(signedIn, pending) { s, p -> s && !p }.distinctUntilChanged().collectLatest { active ->
                if (active) {
                    wasActive = true
                    scheduler.enqueuePeriodic()
                    scheduler.enqueueStart()
                    hasDirty.filter { it }.collect { scheduler.enqueueSoon() }
                } else if (wasActive) {
                    wasActive = false
                    scheduler.cancelAll()
                }
            }
        }
    }
}
