package com.moneybook.shared.money

import java.math.BigDecimal
import java.math.RoundingMode

/** Formatting and parsing of amounts held as Long minor units (1 Kč = 100). */
object MoneyFormat {

    /** 52500 CZK -> "525.00Kč"; withSymbol = false -> "525.00". Grouping ',' and decimal '.', as Realbyte shows in English. */
    fun format(minor: Long, spec: CurrencySpec, withSymbol: Boolean = true): String {
        val shown = BigDecimal.valueOf(minor, spec.decimals).abs().setScale(spec.display, RoundingMode.HALF_UP).toPlainString()
        val whole = shown.substringBefore('.')
        val number = if ('.' in shown) "${groupDigits(whole)}.${shown.substringAfter('.')}" else groupDigits(whole)
        val body = when {
            !withSymbol -> number
            spec.position == SymbolPosition.START -> spec.symbol + number
            else -> number + spec.symbol
        }
        return if (minor < 0) "-$body" else body
    }

    /** "525.0" -> 52500 for 2 decimals, rounding half up. Throws NumberFormatException for non-numbers. */
    fun parseMinor(text: String, decimals: Int): Long =
        BigDecimal(text.trim())
            .setScale(decimals, RoundingMode.HALF_UP)
            .movePointRight(decimals)
            .longValueExact()

    /** "1234567" -> "1,234,567". Input must be plain digits. */
    fun groupDigits(digits: String): String {
        val sb = StringBuilder()
        digits.forEachIndexed { i, ch ->
            if (i > 0 && (digits.length - i) % 3 == 0) sb.append(',')
            sb.append(ch)
        }
        return sb.toString()
    }

    fun pow10(n: Int): Long {
        var result = 1L
        repeat(n) { result *= 10 }
        return result
    }
}
