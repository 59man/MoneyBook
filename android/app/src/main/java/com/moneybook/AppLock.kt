package com.moneybook

import com.moneybook.data.DeviceSettings
import com.moneybook.data.DeviceSettingsRepository
import com.moneybook.domain.LockPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** CHECKING until the device settings are read at start; MainActivity draws nothing of the app until then. */
enum class LockState { CHECKING, LOCKED, OPEN }

/** The passcode lock for the whole process: MainActivity reports start and stop, the lock screen unlocks. */
class AppLock(private val device: DeviceSettingsRepository) {
    private val _state = MutableStateFlow(LockState.CHECKING)
    val state: StateFlow<LockState> = _state.asStateFlow()

    /** When the app last went to the background while open; null until then (a cold start). */
    private var backgroundSince: Long? = null

    /** [expectReturn] was followed by a stop at this time: the next start is the way back, checked by [LockPolicy.outsideTripOk]. */
    private var outsideSince: Long? = null
    private var expectingReturn = false

    /** The latest device settings seen (MoneyBookApp feeds every change), so the app can hide before the read in [onStart]. */
    @Volatile private var known: DeviceSettings? = null

    fun noteSettings(settings: DeviceSettings) {
        known = settings
    }

    /**
     * Call right before leaving for the camera or a picker: the next onStop/onStart pair does not count as time away,
     * so coming back within [LockPolicy.OUTSIDE_MAX_MS] never locks, whatever Request Passcode says. It is for one trip: [returned] (the launcher's result,
     * also on Cancel) drops it if the other screen never stopped MainActivity, so it can't excuse a later real trip.
     */
    fun expectReturn() {
        expectingReturn = true
    }

    /** The launched screen gave its result. */
    fun returned() {
        expectingReturn = false
    }

    suspend fun onStart(now: Long) {
        outsideSince?.let { since ->
            outsideSince = null
            if (LockPolicy.outsideTripOk(since, now, (known?.lockDelaySeconds ?: 0) * 1000L)) return
            backgroundSince = since // left on the picker too long: an ordinary trip away
        }
        // Once the delay is over, hide the app before the read below suspends, so it never shows for a frame.
        val since = backgroundSince
        val seen = known
        if (_state.value == LockState.OPEN && since != null &&
            (seen == null || LockPolicy.shouldLock(seen.passcodeSet, since, now, seen.lockDelaySeconds * 1000L))
        ) _state.value = LockState.CHECKING
        val s = device.current().also { known = it }
        val lock = LockPolicy.shouldLock(s.passcodeSet, backgroundSince, now, s.lockDelaySeconds * 1000L)
        _state.value = if (lock || _state.value == LockState.LOCKED) LockState.LOCKED else LockState.OPEN
    }

    fun onStop(now: Long) {
        if (expectingReturn) {
            expectingReturn = false
            if (_state.value == LockState.OPEN) outsideSince = now
        } else if (_state.value == LockState.OPEN) backgroundSince = now
    }

    fun unlock() {
        _state.value = LockState.OPEN
    }
}
