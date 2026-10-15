package com.moneybook.ui.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moneybook.domain.Formats
import com.moneybook.ui.common.BigTotal
import com.moneybook.ui.common.Hairline
import com.moneybook.ui.common.MbTopBar
import com.moneybook.ui.common.MonthNavigator
import com.moneybook.ui.common.TitleText
import com.moneybook.ui.common.appContainer
import com.moneybook.ui.common.charts.BarChart
import com.moneybook.ui.common.charts.LineChart
import com.moneybook.ui.common.money
import com.moneybook.ui.theme.Mb

@Composable
fun TotalStatsScreen(onBack: () -> Unit) {
    val c = appContainer()
    val vm: TotalStatsViewModel = viewModel { TotalStatsViewModel(c.txns, c.accounts, c.settings, c.time, c.currencies) }
    val s by vm.state.collectAsStateWithLifecycle()
    TotalStatsContent(s, onBack, vm::previous, vm::next)
}

@Composable
fun TotalStatsContent(s: TotalStatsState, onBack: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Mb.colors.background).systemBarsPadding()) {
        MbTopBar(onBack = onBack, title = { TitleText("Total Stats") }, actions = { MonthNavigator(s.title, onPrevious, onNext) })
        Hairline(px = 2)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            BigTotal("Balance", money(s.balance, s.currency, withSymbol = false))
            Hairline(px = 2)
            if (s.months.isNotEmpty()) {
                val labels = s.months.map { Formats.monthName(it) }
                LineChart(
                    s.balances, labels, s.currency.decimals, maxLines = 4,
                    valueTexts = s.balances.map { money(it, s.currency, withSymbol = false) },
                )
                Hairline(px = 2)
                BarChart(s.totals, labels, s.currency)
                Hairline()
            }
        }
    }
}
