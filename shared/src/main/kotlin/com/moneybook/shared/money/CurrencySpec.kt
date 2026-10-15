package com.moneybook.shared.money

enum class SymbolPosition { START, END }

/** How amounts in one currency are stored and displayed. Realbyte's defaults for CZK: "Kč" after the number, 2 decimals. */
data class CurrencySpec(
    val code: String,
    val symbol: String,
    val position: SymbolPosition,
    /** Stored minor-unit decimals (ISO); amounts are Long at this scale. */
    val decimals: Int,
    /** Decimals shown (Main/Sub Currency Setting → Decimal point), rounded half up. */
    val display: Int = decimals,
) {
    companion object {
        /** Realbyte's defaults for [code]; the app overrides symbol, position and display from its currency table. */
        fun of(code: String): CurrencySpec = RealbyteCurrencies.defaultSpec(code)

        /** ISO minor-unit digits; 2 for codes Java does not know (NTD). */
        fun isoDecimals(code: String): Int =
            runCatching { java.util.Currency.getInstance(code.trim().uppercase()).defaultFractionDigits }.getOrNull()?.takeIf { it >= 0 } ?: 2
    }
}
