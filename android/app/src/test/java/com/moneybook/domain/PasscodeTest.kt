package com.moneybook.domain

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PasscodeTest {
    @Test fun `a passcode matches only with its own code and salt`() {
        val salt = Passcode.newSalt()
        val hash = Passcode.hash("1234", salt)
        assertTrue(Passcode.matches("1234", hash, salt))
        assertFalse(Passcode.matches("1235", hash, salt))
        assertFalse(Passcode.matches("1234", hash, Passcode.newSalt()))
        assertNotEquals(Passcode.newSalt(), Passcode.newSalt())
    }

    private fun PasscodeEntry.type(code: String, verify: (String) -> Boolean = { false }) = code.fold(this) { e, d -> e.press(d, verify) }

    @Test fun `setting asks twice and starts over on a mismatch`() {
        var e = PasscodeEntry(PasscodeMode.SET)
        assertEquals("Enter your passcode", e.title)
        e = e.type("1234")
        assertEquals("Confirm your passcode", e.title)
        assertEquals("", e.digits)
        e = e.type("9999")
        assertEquals("Passcodes do not match", e.error)
        assertEquals("Enter your passcode", e.title)
        e = e.type("1234").type("1234")
        assertEquals("1234", e.done)
    }

    @Test fun `checking accepts only the right code`() {
        val verify = { code: String -> code == "2468" }
        val wrong = PasscodeEntry(PasscodeMode.CHECK).type("1111", verify)
        assertEquals("Wrong passcode", wrong.error)
        assertEquals("", wrong.digits)
        assertNull(wrong.done)
        assertEquals("2468", wrong.type("2468", verify).done)
    }

    @Test fun `backspace removes a digit and nothing is typed after the fourth`() {
        val e = PasscodeEntry(PasscodeMode.CHECK).type("12").backspace()
        assertEquals("1", e.digits)
        val done = PasscodeEntry(PasscodeMode.CHECK).type("1234") { true }
        assertEquals("1234", done.press('5') { true }.digits)
    }

    @Test fun `the app locks at a cold start whatever the delay`() {
        assertTrue(LockPolicy.shouldLock(passcodeSet = true, backgroundSince = null, now = 0, delayMs = 3_600_000))
        assertFalse(LockPolicy.shouldLock(passcodeSet = false, backgroundSince = null, now = 0, delayMs = 0))
    }

    @Test fun `a delay of zero locks on any real trip away`() {
        assertTrue(LockPolicy.shouldLock(passcodeSet = true, backgroundSince = 1_000, now = 1_000, delayMs = 0))
        assertTrue(LockPolicy.shouldLock(passcodeSet = true, backgroundSince = 1_000, now = 1_001, delayMs = 0))
        assertFalse(LockPolicy.shouldLock(passcodeSet = false, backgroundSince = 1_000, now = 9_000, delayMs = 0))
    }

    @Test fun `a longer delay locks only once the time away reaches it`() {
        for (seconds in listOf(60, 300, 900, 3600)) {
            val delay = seconds * 1000L
            assertFalse(LockPolicy.shouldLock(true, backgroundSince = 1_000, now = 1_000 + delay - 1, delayMs = delay))
            assertTrue(LockPolicy.shouldLock(true, backgroundSince = 1_000, now = 1_000 + delay, delayMs = delay))
        }
    }

    @Test fun `the request passcode choices are stored as seconds and read back as their label`() {
        assertEquals(listOf(0, 60, 300, 900, 3600), LockDelay.options.map { it.first })
        assertEquals("After 5 minutes", LockDelay.label(300))
        assertEquals("Immediately", LockDelay.label(7))
    }

    @Test fun `a clock that went backwards locks`() {
        assertTrue(LockPolicy.shouldLock(passcodeSet = true, backgroundSince = 50_000, now = 10_000, delayMs = 3_600_000))
    }

    @Test fun `the next reminder is today's time if it is still ahead, else tomorrow's`() {
        val now = LocalDateTime.of(2026, 10, 6, 20, 30)
        assertEquals(LocalDateTime.of(2026, 10, 6, 21, 0), nextReminder(now, 21 * 60))
        assertEquals(LocalDateTime.of(2026, 10, 7, 20, 30), nextReminder(now, 20 * 60 + 30))
        assertEquals(LocalDateTime.of(2026, 10, 7, 8, 0), nextReminder(now, 8 * 60))
    }

    @Test fun `the fifth wrong code in a row starts a thirty second wait and restarts the count`() {
        assertEquals(1 to null, PasscodeRetry.afterWrong(wrong = 0, now = 1_000))
        assertEquals(4 to null, PasscodeRetry.afterWrong(wrong = 3, now = 1_000))
        assertEquals(0 to 31_000L, PasscodeRetry.afterWrong(wrong = 4, now = 1_000))
    }

    @Test fun `the wait counts down, and a reboot ends it`() {
        assertEquals(0L, PasscodeRetry.remaining(retryAt = null, now = 5_000))
        assertEquals(30_000L, PasscodeRetry.remaining(retryAt = 31_000, now = 1_000))
        assertEquals(1L, PasscodeRetry.remaining(retryAt = 31_000, now = 30_999))
        assertEquals(0L, PasscodeRetry.remaining(retryAt = 31_000, now = 31_000))
        // elapsedRealtime restarts at a reboot, so an end more than thirty seconds ahead means the phone restarted,
        // which itself takes longer than the wait: it is over (a cap would keep it going until the old uptime).
        assertEquals(0L, PasscodeRetry.remaining(retryAt = 900_000, now = 2_000))
        assertEquals(0L, PasscodeRetry.remaining(retryAt = 900_000, now = 40_000))
    }
}
