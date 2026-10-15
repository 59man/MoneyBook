package com.moneybook.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SyncTriggersTest {
    private class Recorder(context: Context) : SyncScheduler(context) {
        val calls = mutableListOf<String>()
        override fun enqueueStart() { calls += "start" }
        override fun enqueueSoon() { calls += "soon" }
        override fun enqueuePeriodic() { calls += "periodic" }
        override fun cancelAll() { calls += "cancel" }
    }

    private val signedIn = MutableStateFlow(false)
    private val pending = MutableStateFlow(false)
    private val dirty = MutableStateFlow(false)
    private val rec = Recorder(ApplicationProvider.getApplicationContext())
    private val job = Job()

    private fun start() = SyncTriggers(signedIn, pending, dirty, rec).start(CoroutineScope(job + UnconfinedTestDispatcher()))

    @After fun stop() = job.cancel()

    @Test fun signedInEnqueuesPeriodicAndStart() {
        signedIn.value = true
        start()
        assertEquals(listOf("periodic", "start"), rec.calls)
    }

    @Test fun dirtyAfterSignedInEnqueuesSoonOnceUntilCleanAgain() {
        signedIn.value = true
        start()
        rec.calls.clear()
        dirty.value = true
        assertEquals(listOf("soon"), rec.calls)
        dirty.value = true
        assertEquals(listOf("soon"), rec.calls)
        dirty.value = false
        dirty.value = true
        assertEquals(listOf("soon", "soon"), rec.calls)
    }

    @Test fun signedOutAtStartTouchesNothing() {
        dirty.value = true
        start()
        dirty.value = false
        dirty.value = true
        assertEquals(emptyList<String>(), rec.calls)
    }

    @Test fun loggingOutCancelsAll() {
        signedIn.value = true
        start()
        rec.calls.clear()
        signedIn.value = false
        assertEquals(listOf("cancel"), rec.calls)
        dirty.value = true // nothing is watched any more
        assertEquals(listOf("cancel"), rec.calls)
    }

    @Test fun pendingChoiceHoldsEverything() {
        signedIn.value = true
        pending.value = true
        start()
        dirty.value = true
        assertEquals(emptyList<String>(), rec.calls)
    }

    @Test fun pendingClearedEnqueues() {
        signedIn.value = true
        pending.value = true
        start()
        pending.value = false
        assertEquals(listOf("periodic", "start"), rec.calls)
    }
}
