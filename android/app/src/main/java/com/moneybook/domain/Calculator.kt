package com.moneybook.domain

import java.math.BigDecimal
import java.math.RoundingMode

/** A key of Realbyte's calculator; [Op.symbol] is one of + − × ÷ (the minus is U+2212). */
sealed interface CalcKey {
    data class Digit(val char: Char) : CalcKey
    data object DoubleZero : CalcKey
    data object Dot : CalcKey
    data class Op(val symbol: Char) : CalcKey
    data object Backspace : CalcKey
    data object Clear : CalcKey
    data object Equals : CalcKey
}

/**
 * The calculator behind the amount keypad's calc key: an expression of numbers and + − × ÷, with × and ÷
 * first, as on a phone calculator. Immutable; every key returns a new value.
 */
data class Calculator(val expr: String = "", val error: Boolean = false) {
    private val lastNumber: String get() = expr.takeLastWhile { it.isDigit() || it == '.' }

    fun press(key: CalcKey): Calculator {
        // After "Error" the next key starts afresh; ⌫ and AC just clear.
        if (error) return if (key == CalcKey.Backspace || key == CalcKey.Clear) Calculator() else Calculator().press(key)
        return when (key) {
            is CalcKey.Digit -> digits(key.char.toString())
            CalcKey.DoubleZero -> if (lastNumber.isEmpty() || lastNumber == "0") digits("0") else digits("00")
            CalcKey.Dot -> when {
                lastNumber.contains('.') -> this
                lastNumber.isEmpty() -> copy(expr = expr + "0.")
                else -> copy(expr = "$expr.")
            }
            is CalcKey.Op -> when {
                expr.isEmpty() -> copy(expr = "0${key.symbol}")
                expr.last() in OPS -> copy(expr = expr.dropLast(1) + key.symbol)
                else -> copy(expr = expr + key.symbol)
            }
            CalcKey.Backspace -> copy(expr = expr.dropLast(1))
            CalcKey.Clear -> Calculator()
            CalcKey.Equals -> try {
                evaluate()?.let { Calculator(it.plain()) } ?: this
            } catch (e: ArithmeticException) {
                copy(error = true)
            }
        }
    }

    private fun digits(d: String): Calculator {
        if (lastNumber.count { it.isDigit() } + d.length > MAX_DIGITS) return this
        return if (lastNumber == "0") copy(expr = expr.dropLast(1) + d) else copy(expr = expr + d)
    }

    /** The big line: "Error", the value so far (a trailing operator ignored), or 0. */
    fun display(): String = if (error) "Error" else (valueOrNull() ?: BigDecimal.ZERO).plain()

    /** The line above it: the expression, once it has an operator. */
    val expressionLine: String get() = if (expr.any { it in OPS }) expr else ""

    /** The value rounded half-up to [decimals] places, in minor units; null when empty, invalid or after ÷ 0. */
    fun minorUnits(decimals: Int): Long? = if (error) null else runCatching {
        valueOrNull()?.setScale(decimals, RoundingMode.HALF_UP)?.movePointRight(decimals)?.longValueExact()
    }.getOrNull()

    private fun valueOrNull(): BigDecimal? = try {
        evaluate()
    } catch (e: ArithmeticException) {
        null
    }

    /** Null for an empty or unfinished expression; throws ArithmeticException on ÷ 0. */
    private fun evaluate(): BigDecimal? {
        val body = expr.trimEnd(*OPS)
        if (body.isEmpty()) return null
        val numbers = mutableListOf<BigDecimal>()
        val ops = mutableListOf<Char>()
        val current = StringBuilder()
        for (c in body) {
            if (c in OPS) {
                numbers += current.toString().toBigDecimalOrNull() ?: return null
                ops += c
                current.clear()
            } else {
                current.append(c)
            }
        }
        numbers += current.toString().toBigDecimalOrNull() ?: return null
        // × and ÷ apply to the last term; + and − start a new one.
        val terms = mutableListOf(numbers[0])
        ops.forEachIndexed { i, op ->
            val n = numbers[i + 1]
            when (op) {
                '×' -> terms[terms.lastIndex] = terms.last().multiply(n)
                '÷' -> {
                    if (n.signum() == 0) throw ArithmeticException("division by zero")
                    terms[terms.lastIndex] = terms.last().divide(n, SCALE, RoundingMode.HALF_UP)
                }
                '−' -> terms += n.negate()
                else -> terms += n
            }
        }
        return terms.reduce(BigDecimal::add)
    }

    companion object {
        private val OPS = charArrayOf('+', '−', '×', '÷')
        private const val MAX_DIGITS = 15
        private const val SCALE = 10

        private fun BigDecimal.plain(): String = stripTrailingZeros().toPlainString()

        /** The calculator opened on an amount; a negative amount is written as 0 − x. */
        fun of(minor: Long, decimals: Int): Calculator {
            if (minor == 0L) return Calculator()
            val value = BigDecimal.valueOf(minor, decimals).abs().plain()
            return Calculator(if (minor < 0) "0−$value" else value)
        }
    }
}
