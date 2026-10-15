package com.moneybook.shared.money

import java.math.BigDecimal
import java.math.RoundingMode

/** Converts minor units between currencies. [rates]: 1 unit of the code = rate units of [main]. Unknown codes count 1:1. */
class Fx(val main: String, private val rates: Map<String, BigDecimal>) {
    fun rate(code: String): BigDecimal = if (code == main) BigDecimal.ONE else rates[code] ?: BigDecimal.ONE

    fun convert(minor: Long, from: String, to: String): Long {
        if (from == to) return minor
        val value = BigDecimal.valueOf(minor, dec(from)).multiply(rate(from)).divide(rate(to), dec(to), RoundingMode.HALF_UP)
        return value.unscaledValue().toLong()
    }

    fun toMain(minor: Long, code: String): Long = convert(minor, code, main)
    fun fromMain(minorMain: Long, code: String): Long = convert(minorMain, main, code)

    companion object {
        private fun dec(code: String) = CurrencySpec.isoDecimals(code)

        /** The rate an entry was saved at: its main amount over its amount. */
        fun rateOf(amount: Long, code: String, amountMain: Long, main: String): BigDecimal {
            if (amount == 0L) return BigDecimal.ONE
            return BigDecimal.valueOf(amountMain, dec(main)).divide(BigDecimal.valueOf(amount, dec(code)), 10, RoundingMode.HALF_UP)
        }

        /** [amount] of [code] at [rate], in [main]'s minor units. */
        fun mainAt(amount: Long, code: String, rate: BigDecimal, main: String): Long =
            BigDecimal.valueOf(amount, dec(code)).multiply(rate).setScale(dec(main), RoundingMode.HALF_UP).unscaledValue().toLong()

        /** "1.0", "1.1", "1.25". */
        fun formatRate(rate: BigDecimal): String {
            val r = rate.stripTrailingZeros()
            return (if (r.scale() < 1) r.setScale(1) else r).toPlainString()
        }

        /** A positive rate with at most 10 decimals, or null. */
        fun parseRate(text: String): BigDecimal? {
            val r = text.trim().toBigDecimalOrNull() ?: return null
            return r.takeIf { it.signum() > 0 && it.stripTrailingZeros().scale() <= 10 }
        }
    }
}
