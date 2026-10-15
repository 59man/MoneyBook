package com.moneybook.domain

/**
 * Account Info's Settlement Date / Payment Date keypad: a day of month, 1–31. The first digit after opening
 * replaces the value; a digit that would pass 31 is ignored.
 */
data class DayInput(val value: Int = 1, val fresh: Boolean = true) {
    fun digit(d: Int): DayInput = when {
        fresh -> if (d == 0) this else DayInput(d, fresh = false)
        value * 10 + d in 1..31 -> DayInput(value * 10 + d, fresh = false)
        else -> this
    }

    fun backspace(): DayInput = if (value >= 10) DayInput(value / 10, fresh = false) else DayInput(1, fresh = true)

    fun clear(): DayInput = DayInput(1, fresh = true)
}
