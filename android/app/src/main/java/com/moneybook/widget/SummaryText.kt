package com.moneybook.widget

import androidx.datastore.preferences.core.stringPreferencesKey
import com.moneybook.domain.Mom
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.ui.common.money
import kotlin.math.abs

/** The summary widget's tabs. A new widget opens on Total, as Realbyte's does. */
enum class SummaryTab(val label: String) {
    INCOME("Income"),
    EXPENSES("Expenses"),
    TOTAL("Total"),
    ;

    companion object {
        /** The key of each widget's own tab in its Glance state. */
        val KEY = stringPreferencesKey("summary_tab")

        fun from(name: String?): SummaryTab = entries.firstOrNull { it.name == name } ?: TOTAL
    }
}

enum class MomTone { GOOD, BAD, NONE }

fun Mom.tone(): MomTone = when (good) {
    true -> MomTone.GOOD
    false -> MomTone.BAD
    null -> MomTone.NONE
}

/** "▼ 50.00 (50%)"; "▲ 500.00" after a month of nothing; a plain "0.00" when nothing changed (Realbyte). */
fun momText(m: Mom, spec: CurrencySpec): String {
    if (m.delta == 0L) return money(0, spec, withSymbol = false)
    val arrow = if (m.delta > 0) "▲" else "▼"
    val amount = money(abs(m.delta), spec, withSymbol = false)
    return if (m.percent == null) "$arrow $amount" else "$arrow $amount (${m.percent}%)"
}
