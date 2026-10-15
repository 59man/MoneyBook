package com.moneybook.domain

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.LocalTime

/** The app passcode, stored only as a salted SHA-256 hash in the device settings. */
object Passcode {
    private val random = SecureRandom()

    fun newSalt(): String = ByteArray(16).also(random::nextBytes).toHex()

    fun hash(code: String, salt: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + code).toByteArray()).toHex()

    fun matches(code: String, hash: String, salt: String): Boolean =
        MessageDigest.isEqual(hash(code, salt).toByteArray(), hash.toByteArray())

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

/** SET asks for a new passcode twice; CHECK asks for the current one (unlock, turning it off). */
enum class PasscodeMode { SET, CHECK }

/** What the passcode pad shows: the typed digits, the first entry while confirming, a message, and the accepted code. */
data class PasscodeEntry(
    val mode: PasscodeMode,
    val digits: String = "",
    val first: String? = null,
    val error: String? = null,
    val done: String? = null,
) {
    val title: String get() = if (mode == PasscodeMode.SET && first != null) "Confirm your passcode" else "Enter your passcode"

    /** [verify] checks a full code in CHECK mode. */
    fun press(digit: Char, verify: (String) -> Boolean): PasscodeEntry {
        if (done != null || digits.length >= LENGTH) return this
        val next = digits + digit
        if (next.length < LENGTH) return copy(digits = next, error = null)
        return when (mode) {
            PasscodeMode.SET -> when {
                first == null -> copy(digits = "", first = next, error = null)
                first == next -> copy(digits = next, done = next)
                else -> copy(digits = "", first = null, error = "Passcodes do not match")
            }
            PasscodeMode.CHECK -> if (verify(next)) copy(digits = next, done = next) else copy(digits = "", error = "Wrong passcode")
        }
    }

    fun backspace(): PasscodeEntry = if (done != null) this else copy(digits = digits.dropLast(1))

    companion object {
        const val LENGTH = 4
    }
}

/**
 * Wrong codes when checking the passcode (lock screen, turning it off): the [MAX_WRONG]th in a row makes the pad wait
 * [WAIT_MS] and restarts the count. Times are SystemClock.elapsedRealtime(), so moving the clock can't end a wait.
 */
object PasscodeRetry {
    const val MAX_WRONG = 5
    const val WAIT_MS = 30_000L

    /** The new count of wrong codes in a row, and when the pad may be used again (null: no wait). */
    fun afterWrong(wrong: Int, now: Long): Pair<Int, Long?> = if (wrong + 1 >= MAX_WRONG) 0 to now + WAIT_MS else (wrong + 1) to null

    /**
     * Milliseconds still to wait. elapsedRealtime restarts at a reboot, so an end more than [WAIT_MS] ahead means the
     * phone restarted, which takes longer than the wait itself: it is over.
     */
    fun remaining(retryAt: Long?, now: Long): Long {
        val left = (retryAt ?: return 0) - now
        return if (left > WAIT_MS) 0 else left.coerceAtLeast(0)
    }
}

/** When the app shows the lock screen: at a cold start, and once the time away reaches Request Passcode's delay. */
object LockPolicy {
    /** [backgroundSince] is null at a cold start. A delay of 0 locks on any real trip; a clock that went backwards locks too. */
    fun shouldLock(passcodeSet: Boolean, backgroundSince: Long?, now: Long, delayMs: Long): Boolean =
        passcodeSet && (backgroundSince == null || now < backgroundSince || now - backgroundSince >= delayMs)

    /** How long a trip to the camera or a picker is excused, so a phone put away with a picker open still locks. */
    const val OUTSIDE_MAX_MS = 600_000L

    /** Coming back from the camera or a picker: excused for [OUTSIDE_MAX_MS], or Request Passcode's delay when longer. */
    fun outsideTripOk(since: Long, now: Long, delayMs: Long): Boolean = now >= since && now - since < maxOf(delayMs, OUTSIDE_MAX_MS)
}

/** Request Passcode's choices (Realbyte's option dialog), as seconds and label; the first is the default. */
object LockDelay {
    val options: List<Pair<Int, String>> = listOf(
        0 to "Immediately", 60 to "After 1 minute", 300 to "After 5 minutes", 900 to "After 15 minutes", 3600 to "After 1 hour",
    )

    fun label(seconds: Int): String = (options.firstOrNull { it.first == seconds } ?: options.first()).second
}

/** The next daily reminder at [minutes] after midnight: today if that is still ahead of [now], else tomorrow. */
fun nextReminder(now: LocalDateTime, minutes: Int): LocalDateTime {
    val today = now.toLocalDate().atTime(LocalTime.of(minutes / 60, minutes % 60))
    return if (today.isAfter(now)) today else today.plusDays(1)
}
