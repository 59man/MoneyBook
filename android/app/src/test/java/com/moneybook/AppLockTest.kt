package com.moneybook

import com.moneybook.data.DeviceSettings
import com.moneybook.data.DeviceSettingsRepository
import com.moneybook.domain.Passcode
import com.moneybook.testutil.FakeDeviceSettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AppLockTest {
    private val salt = Passcode.newSalt()
    private val withPasscode = DeviceSettings(passcodeHash = Passcode.hash("1234", salt), passcodeSalt = salt)

    @Test fun withoutAPasscodeTheAppIsOpen() = runTest {
        val lock = AppLock(FakeDeviceSettingsRepository())
        assertEquals(LockState.CHECKING, lock.state.value)
        lock.onStart(now = 0)
        assertEquals(LockState.OPEN, lock.state.value)
    }

    @Test fun aColdStartLocksAndATripShorterThanRequestPasscodeDoesNot() = runTest {
        val lock = AppLock(FakeDeviceSettingsRepository(withPasscode.copy(lockDelaySeconds = 60)))
        lock.onStart(now = 0)
        assertEquals(LockState.LOCKED, lock.state.value)
        lock.unlock()
        lock.onStop(now = 10_000)
        lock.onStart(now = 69_999) // 59.999 s away
        assertEquals(LockState.OPEN, lock.state.value)
        lock.onStop(now = 100_000)
        lock.onStart(now = 160_000) // 60 s away
        assertEquals(LockState.LOCKED, lock.state.value)
    }

    @Test fun immediatelyLocksOnAnyRealTripAway() = runTest {
        val lock = AppLock(FakeDeviceSettingsRepository(withPasscode))
        lock.onStart(now = 0)
        lock.unlock()
        lock.onStop(now = 10_000)
        lock.onStart(now = 10_001)
        assertEquals(LockState.LOCKED, lock.state.value)
    }

    @Test fun theNewDelayAppliesToTheNextTripWithoutRestartingTheApp() = runTest {
        val fake = FakeDeviceSettingsRepository(withPasscode.copy(lockDelaySeconds = 3600))
        val lock = AppLock(fake)
        lock.onStart(now = 0)
        lock.unlock()
        fake.update { it.copy(lockDelaySeconds = 0) }
        lock.onStop(now = 1_000)
        lock.onStart(now = 2_000)
        assertEquals(LockState.LOCKED, lock.state.value)
    }

    @Test fun withoutAPasscodeATripAwayNeverHidesTheApp() = runTest {
        val lock = AppLock(FakeDeviceSettingsRepository())
        lock.onStart(now = 0)
        lock.onStop(now = 1_000)
        lock.onStart(now = 5_000)
        assertEquals(LockState.OPEN, lock.state.value)
    }

    @Test fun stayingLockedInTheBackgroundStaysLocked() = runTest {
        val lock = AppLock(FakeDeviceSettingsRepository(withPasscode))
        lock.onStart(now = 0)
        lock.onStop(now = 1_000)
        lock.onStart(now = 2_000)
        assertEquals(LockState.LOCKED, lock.state.value)
    }

    @Test fun aLongTripAwayHidesTheAppWhileTheSettingsAreRead() = runTest {
        var gate: CompletableDeferred<Unit>? = null
        val fake = FakeDeviceSettingsRepository(withPasscode)
        val slow = object : DeviceSettingsRepository by fake {
            override suspend fun current(): DeviceSettings {
                gate?.await()
                return fake.state.value
            }
        }
        val lock = AppLock(slow)
        lock.onStart(now = 0)
        lock.unlock()
        lock.onStop(now = 1_000)
        gate = CompletableDeferred()
        launch { lock.onStart(now = 40_000) } // Immediately: any trip locks
        runCurrent()
        assertNotEquals(LockState.OPEN, lock.state.value)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(LockState.LOCKED, lock.state.value)
    }

    @Test fun expectedReturnNeverLocksButTheNextRealTripDoes() = runTest {
        val lock = AppLock(FakeDeviceSettingsRepository(withPasscode))
        lock.onStart(now = 0)
        lock.unlock()
        lock.expectReturn()
        lock.onStop(now = 1_000)
        lock.onStart(now = 1_000 + 599_999) // a camera session of almost 10 minutes
        assertEquals(LockState.OPEN, lock.state.value)
        lock.onStop(now = 700_000) // the same stop/start pair is not excused twice (delay 0 = Immediately)
        lock.onStart(now = 700_100)
        assertEquals(LockState.LOCKED, lock.state.value)
    }

    @Test fun anExpectedTripLeftOpenFor10MinutesLocks() = runTest {
        val lock = AppLock(FakeDeviceSettingsRepository(withPasscode))
        lock.onStart(now = 0)
        lock.unlock()
        lock.expectReturn()
        lock.onStop(now = 1_000) // the picker opens, then the phone is put away with it on screen
        lock.onStart(now = 1_000 + 600_000)
        assertEquals(LockState.LOCKED, lock.state.value)
    }

    @Test fun anExpectedTripGetsAtLeastRequestPasscodesDelay() = runTest {
        val lock = AppLock(FakeDeviceSettingsRepository(withPasscode.copy(lockDelaySeconds = 3600)))
        lock.onStart(now = 0)
        lock.unlock()
        lock.expectReturn()
        lock.onStop(now = 1_000)
        lock.onStart(now = 1_000 + 3_000_000) // 50 minutes, under After 1 hour
        assertEquals(LockState.OPEN, lock.state.value)
    }

    @Test fun aReturnedTripThatNeverStoppedTheActivityExcusesNothingLater() = runTest {
        val lock = AppLock(FakeDeviceSettingsRepository(withPasscode))
        lock.onStart(now = 0)
        lock.unlock()
        lock.expectReturn()
        lock.returned() // a translucent picker: only onPause, then the result
        lock.onStop(now = 1_000)
        lock.onStart(now = 100_000)
        assertEquals(LockState.LOCKED, lock.state.value)
    }
}
