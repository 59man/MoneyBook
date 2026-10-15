package com.moneybook.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.moneybook.data.CurrencyState
import com.moneybook.domain.Totals
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.shared.money.MoneyFormat
import com.moneybook.ui.theme.LightMbColors
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType

/** The currency table as the UI sees it; MoneyBookApp keeps it current. Reads during composition recompose on change. */
object Currencies {
    var state: CurrencyState by mutableStateOf(CurrencyState("USD", emptyList()))
}

/** [code]'s display settings: the currency table over Realbyte's defaults. */
fun specOf(code: String): CurrencySpec = Currencies.state.spec(code)

/** Re-resolved by code, so a changed symbol, unit position or decimal point shows everywhere at once. */
fun money(minor: Long, spec: CurrencySpec, withSymbol: Boolean = true): String = MoneyFormat.format(minor, specOf(spec.code), withSymbol)

@Composable
fun AmountText(
    minor: Long,
    spec: CurrencySpec,
    color: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = MbType.amount,
    withSymbol: Boolean = true,
) {
    FitText(money(minor, spec, withSymbol), style, color, modifier, textAlign = TextAlign.End)
}

/** Widest a free-standing amount may get: one amount column, grown with the system font scale. */
@Composable
fun amountMaxWidth(): Dp = MbDimens.amountColumn * LocalDensity.current.fontScale

/** The Income / Expenses / Total block under the Trans. tabs (values without currency symbol). */
fun incomeExpenseTotal(totals: Totals, spec: CurrencySpec): List<SummaryItem> = listOf(
    SummaryItem("Income", money(totals.income, spec, withSymbol = false), SummaryColor.INCOME),
    SummaryItem("Expenses", money(totals.expense, spec, withSymbol = false), SummaryColor.EXPENSE),
    SummaryItem("Total", money(totals.total, spec, withSymbol = false), SummaryColor.BODY),
)

enum class SummaryColor { INCOME, EXPENSE, BODY, PRIMARY }

data class SummaryItem(val label: String, val value: String, val color: SummaryColor)

internal fun SummaryColor.resolve(colors: com.moneybook.ui.theme.MbColors = LightMbColors): Color = when (this) {
    SummaryColor.INCOME -> colors.income
    SummaryColor.EXPENSE -> colors.expense
    SummaryColor.BODY -> colors.textBody
    SummaryColor.PRIMARY -> colors.textPrimary
}
