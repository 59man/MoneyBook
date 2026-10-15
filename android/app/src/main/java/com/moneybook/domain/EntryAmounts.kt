package com.moneybook.domain

import com.moneybook.shared.money.Fx

data class StoredAmounts(val amount: Long, val currency: String, val amountMain: Long, val accountAmount: Long?, val toAmount: Long?)

/**
 * What an entry stores: [amount] of [currency] as entered; [amountMain] from the rate sheet, else at the current
 * rate; and each account's side in its own currency (null when that is the entered currency).
 */
fun entryAmounts(amount: Long, currency: String, amountMain: Long?, accountCurrency: String, toAccountCurrency: String?, fx: Fx): StoredAmounts {
    val main = amountMain ?: fx.toMain(amount, currency)
    fun side(c: String): Long? = when (c) {
        currency -> null
        fx.main -> main
        else -> fx.fromMain(main, c)
    }
    return StoredAmounts(amount, currency, main, side(accountCurrency), toAccountCurrency?.let(::side))
}
