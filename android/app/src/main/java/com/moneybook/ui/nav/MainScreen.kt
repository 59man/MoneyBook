package com.moneybook.ui.nav

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.moneybook.R
import com.moneybook.ui.accounts.AccountsScreen
import com.moneybook.ui.common.FitText
import com.moneybook.ui.common.Hairline
import com.moneybook.ui.common.MbIcon
import com.moneybook.ui.more.MoreScreen
import com.moneybook.ui.stats.StatsScreen
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType
import com.moneybook.ui.trans.TransScreen
import java.time.LocalDate

/** Bottom navigation entries. */
enum class MainTab(val label: String, @DrawableRes val icon: Int) {
    TRANS("Trans.", R.drawable.ic_nav_trans),
    STATS("Stats", R.drawable.ic_nav_stats),
    ACCOUNTS("Accounts", R.drawable.ic_nav_accounts),
    MORE("More", R.drawable.ic_nav_more),
}

@Composable
fun MainScreen(onAddTxn: ((LocalDate?) -> Unit)?, onOpenTxn: (String) -> Unit, onOpenAccount: (String) -> Unit, onAddAccount: () -> Unit, onOpenConfiguration: () -> Unit, onOpenAccountSettings: () -> Unit, onOpenSync: () -> Unit, onOpenBackup: () -> Unit, onOpenCategoryStats: (CategoryStatsRoute) -> Unit, onOpenTotalStats: () -> Unit, onNavigate: (Any) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(MainTab.TRANS) }
    Column(Modifier.fillMaxSize().background(Mb.colors.surface).statusBarsPadding()) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                MainTab.TRANS -> TransScreen(
                    onAddTxn = onAddTxn, onOpenTxn = onOpenTxn,
                    onOpenMemo = { id, date -> onNavigate(MemoEditRoute(id, date?.toString())) },
                    onOpenBookmarks = { onNavigate(BookmarksRoute) },
                    onOpenSearch = { onNavigate(SearchRoute) },
                    onNavigate = onNavigate,
                )
                MainTab.STATS -> StatsScreen(onOpenCategory = { slice, type, period ->
                    onOpenCategoryStats(CategoryStatsRoute(slice.key, slice.name, type.name, period.encode()))
                })
                MainTab.ACCOUNTS -> AccountsScreen(onOpenAccount = onOpenAccount, onAddAccount = onAddAccount, onOpenTotalStats = onOpenTotalStats)
                MainTab.MORE -> MoreScreen(onOpenConfiguration = onOpenConfiguration, onOpenAccountSettings = onOpenAccountSettings, onOpenSync = onOpenSync, onOpenBackup = onOpenBackup)
            }
        }
        BottomNav(tab) { tab = it }
    }
}

@Composable
internal fun BottomNav(selected: MainTab, onSelect: (MainTab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Mb.colors.navBackground).navigationBarsPadding()) {
        Hairline(px = 2)
        Row(Modifier.fillMaxWidth().heightIn(min = MbDimens.bottomNav).height(IntrinsicSize.Min)) {
            MainTab.entries.forEach { t ->
                val color = if (t == selected) Mb.colors.accent else Mb.colors.navUnselected
                Column(
                    Modifier.weight(1f).fillMaxHeight().clickable { onSelect(t) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    MbIcon(t.icon, null, tint = color)
                    FitText(t.label, MbType.navLabel, color)
                }
            }
        }
    }
}
