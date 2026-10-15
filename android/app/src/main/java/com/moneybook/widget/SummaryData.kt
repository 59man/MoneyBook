package com.moneybook.widget

import com.moneybook.data.SettingsRepository
import com.moneybook.data.TxnRepository
import com.moneybook.domain.DateRange
import com.moneybook.domain.WidgetSummary
import com.moneybook.domain.momRanges
import com.moneybook.domain.widgetSummary
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.ui.common.specOf
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/** What the summary widget and Widget Settings' preview show. */
data class SummaryData(val summary: WidgetSummary, val currency: CurrencySpec)

/** Reads the entries of this month and the previous one once, under the Monthly Start Date (the same rows as Trans). */
suspend fun loadSummary(settings: SettingsRepository, txns: TxnRepository, today: LocalDate): SummaryData {
    val s = settings.current()
    val ranges = momRanges(today, s.monthStartDay, s.monthStartWeekend)
    val rows = txns.observeRange(DateRange(ranges.previous.start, ranges.month.endExclusive)).first()
    return SummaryData(widgetSummary(rows, ranges, s.cardExpenseMode), specOf(s.mainCurrency))
}

/**
 * The same, kept current: a new entry or a settings change emits again. A Glance session that is still open recomposes
 * from it; [today] is read on each settings change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun summaryFlow(settings: SettingsRepository, txns: TxnRepository, today: () -> LocalDate): Flow<SummaryData> =
    settings.settings.flatMapLatest { s ->
        val ranges = momRanges(today(), s.monthStartDay, s.monthStartWeekend)
        val spec = specOf(s.mainCurrency)
        txns.observeRange(DateRange(ranges.previous.start, ranges.month.endExclusive)).map { SummaryData(widgetSummary(it, ranges, s.cardExpenseMode), spec) }
    }
