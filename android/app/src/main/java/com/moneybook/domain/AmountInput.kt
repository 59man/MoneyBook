package com.moneybook.domain

import com.moneybook.shared.money.CurrencySpec
import com.moneybook.shared.money.MoneyFormat
import java.math.BigDecimal

/** What the user has typed on the amount keypad, e.g. "-1234.5". Immutable; every key returns a new value. */
/** [decimals] is the stored scale; [maxFraction] how many the keypad accepts (the currency's Decimal point, at most [decimals]). */
data class AmountInput(val text: String = "", val decimals: Int = 2, val maxFraction: Int = decimals) {
    private val negative get() = text.startsWith("-")
    private val body get() = text.removePrefix("-")
    private fun withBody(newBody: String) = copy(text = (if (negative) "-" else "") + newBody)

    val isEmpty: Boolean get() = body.isEmpty()

    fun digit(d: Char): AmountInput {
        require(d in '0'..'9') { "not a digit: $d" }
        val dot = body.indexOf('.')
        if (dot >= 0 && body.length - dot - 1 >= maxFraction) return this
        if (dot < 0 && body.length >= MAX_INT_DIGITS) return this
        return withBody(if (body == "0") d.toString() else body + d)
    }

    fun dot(): AmountInput {
        if (maxFraction == 0 || body.contains('.')) return this
        return withBody(body.ifEmpty { "0" } + ".")
    }

    fun backspace(): AmountInput = copy(text = text.dropLast(1))

    fun toggleSign(): AmountInput = copy(text = if (negative) text.drop(1) else "-$text")

    fun minorUnits(): Long? {
        val cleaned = body.removeSuffix(".")
        if (cleaned.isEmpty()) return null
        val value = MoneyFormat.parseMinor(cleaned, decimals)
        return if (negative) -value else value
    }

    /** "1234567.5" -> "1,234,567.5" while typing. */
    fun display(): String {
        val dot = body.indexOf('.')
        val integer = if (dot >= 0) body.substring(0, dot) else body
        val rest = if (dot >= 0) body.substring(dot) else ""
        return (if (negative) "-" else "") + MoneyFormat.groupDigits(integer) + rest
    }

    /** The same typed value in [spec]: the fraction cut to what [spec] accepts (EUR 12.5 → JPY 12). */
    fun withCurrency(spec: CurrencySpec): AmountInput {
        val max = minOf(spec.display, spec.decimals)
        val dot = body.indexOf('.')
        val kept = when {
            dot < 0 -> body
            max == 0 -> body.substring(0, dot)
            else -> body.substring(0, minOf(body.length, dot + 1 + max))
        }
        return AmountInput((if (negative) "-" else "") + kept, spec.decimals, max)
    }

    companion object {
        const val MAX_INT_DIGITS = 12

        /** [minor] of [spec], typed as the keypad would. */
        fun of(minor: Long, spec: CurrencySpec): AmountInput =
            fromMinor(minor, spec.decimals).copy(maxFraction = minOf(spec.display, spec.decimals))

        fun fromMinor(minor: Long, decimals: Int): AmountInput {
            if (minor == 0L) return AmountInput("", decimals)
            val plain = BigDecimal.valueOf(minor, decimals).stripTrailingZeros().let {
                if (it.scale() < 0) it.setScale(0) else it
            }.toPlainString()
            return AmountInput(plain, decimals)
        }
    }
}
