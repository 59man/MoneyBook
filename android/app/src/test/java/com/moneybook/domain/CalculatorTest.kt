package com.moneybook.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalculatorTest {
    /** Presses [keys]: digits, D = 00, . , + − × ÷, < = ⌫, C = AC, = . */
    private fun calc(keys: String): Calculator = keys.fold(Calculator()) { c, ch -> c.press(key(ch)) }

    private fun key(ch: Char): CalcKey = when (ch) {
        in '0'..'9' -> CalcKey.Digit(ch)
        'D' -> CalcKey.DoubleZero
        '.' -> CalcKey.Dot
        '+', '−', '×', '÷' -> CalcKey.Op(ch)
        '<' -> CalcKey.Backspace
        'C' -> CalcKey.Clear
        '=' -> CalcKey.Equals
        else -> error("no key for $ch")
    }

    @Test fun `times and divide go before plus and minus`() {
        assertEquals("14", calc("2+3×4=").expr)
        assertEquals("4", calc("10−2×3=").expr)
        assertEquals("3.5", calc("7÷2=").display())
    }

    @Test fun `the display shows the value so far and the line above the sum`() {
        val c = calc("120+85.5")
        assertEquals("205.5", c.display())
        assertEquals("120+85.5", c.expressionLine)
        assertEquals("", calc("120").expressionLine)
        assertEquals("0", Calculator().display())
        assertEquals("120", calc("120+").display()) // a trailing operator is ignored
    }

    @Test fun `minor units round half up to the currency`() {
        assertEquals(20_550L, calc("120+85.5").minorUnits(2))
        assertEquals(33L, calc("1÷3").minorUnits(2))
        assertEquals(67L, calc("2÷3").minorUnits(2))
        assertEquals(30L, calc("0.1+0.2").minorUnits(2))
        assertNull(Calculator().minorUnits(2))
    }

    @Test fun `operators, dots and zeros follow phone calculator rules`() {
        assertEquals("5×", calc("5+×").expr) // a second operator replaces the first
        assertEquals("0+", calc("+").expr)
        assertEquals("0.5", calc(".5").expr)
        assertEquals("1.5", calc("1..5").expr)
        assertEquals("0", calc("DD").expr)
        assertEquals("100", calc("1D").expr)
        assertEquals("7", calc("07").expr)
        assertEquals("12", calc("123<").expr)
        assertEquals("", calc("12+3C").expr)
    }

    @Test fun `dividing by zero shows Error until the next key`() {
        val err = calc("5÷0=")
        assertEquals("Error", err.display())
        assertNull(err.minorUnits(2))
        assertEquals("7", err.press(CalcKey.Digit('7')).expr)
        assertEquals("", err.press(CalcKey.Backspace).expr)
    }

    @Test fun `input stops at fifteen digits and a lone minus is not a number`() {
        assertEquals("123456789012345", calc("1234567890123456").expr)
        assertNull(Calculator("-").minorUnits(2))
        assertEquals("0", Calculator("-").display())
    }

    @Test fun `an amount opens the calculator with its value`() {
        assertEquals("123.5", Calculator.of(12_350, 2).expr)
        assertEquals("", Calculator.of(0, 2).expr)
        assertEquals(-500L, Calculator.of(-500, 2).minorUnits(2))
    }
}
