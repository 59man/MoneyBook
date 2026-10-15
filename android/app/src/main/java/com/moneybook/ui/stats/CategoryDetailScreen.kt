package com.moneybook.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moneybook.domain.StatsPeriod
import com.moneybook.shared.model.TxnType
import com.moneybook.ui.common.BigTotal
import com.moneybook.ui.common.Hairline
import com.moneybook.ui.common.MbTopBar
import com.moneybook.ui.common.MonthNavigator
import com.moneybook.ui.common.TitleText
import com.moneybook.ui.common.appContainer
import com.moneybook.ui.common.charts.LineChart
import com.moneybook.ui.common.money
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.trans.DailyTab

@Composable
fun CategoryDetailScreen(key: String, name: String, type: TxnType, period: StatsPeriod, onBack: () -> Unit, onOpenTxn: (String) -> Unit) {
    val c = appContainer()
    val vm: CategoryDetailViewModel = viewModel { CategoryDetailViewModel(key, name, type, period, c.txns, c.categories, c.settings) }
    val s by vm.state.collectAsStateWithLifecycle()
    CategoryDetailContent(s, onBack, vm::previous, vm::next, onOpenTxn)
}

@Composable
fun CategoryDetailContent(
    s: CategoryDetailState,
    onBack: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpenTxn: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Mb.colors.background).systemBarsPadding()) {
        MbTopBar(
            onBack = onBack,
            title = { TitleText(s.name) },
            actions = { if (s.period.movable) MonthNavigator(s.period.title(), onPrevious, onNext) },
        )
        Hairline(px = 2)
        BigTotal("Total Balance", money(s.total, s.currency))
        if (s.trend.isNotEmpty()) {
            Hairline()
            LineChart(s.trend, s.trendLabels, s.currency.decimals, maxLines = 3)
        }
        Hairline()
        Box(Modifier.weight(1f)) {
            DailyTab(s.dayGroups, s.currency, onOpenTxn, header = s.subSlices.takeIf { it.isNotEmpty() }?.let { { SubcategoryBreakdown(it, s.currency) } })
        }
    }
}
