package com.moneybook.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val LocalMbColors = staticCompositionLocalOf { LightMbColors }

object Mb {
    val colors: MbColors
        @Composable @ReadOnlyComposable get() = LocalMbColors.current
}

/** [swapIncomeExpense]: Configuration → Income-Expenses Color Setting "Set. B" (income red, expenses blue). */
@Composable
fun MoneyBookTheme(darkTheme: Boolean = isSystemInDarkTheme(), swapIncomeExpense: Boolean = false, content: @Composable () -> Unit) {
    val base = if (darkTheme) DarkMbColors else LightMbColors
    val colors = if (!swapIncomeExpense) base else base.copy(
        income = base.expense, expense = base.income,
        incomeBar = base.expenseBar, expenseBar = base.incomeBar,
        ringIncome = base.ringExpense, ringExpense = base.ringIncome,
        incomeFaded = base.expenseFaded, expenseFaded = base.incomeFaded,
    )
    val scheme = if (darkTheme) {
        darkColorScheme(primary = colors.accent, onPrimary = Color.White, surface = colors.surface, background = colors.background)
    } else {
        lightColorScheme(primary = colors.accent, onPrimary = Color.White, surface = colors.surface, background = colors.background)
    }
    CompositionLocalProvider(LocalMbColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
