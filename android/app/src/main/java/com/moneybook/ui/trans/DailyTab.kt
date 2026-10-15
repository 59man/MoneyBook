package com.moneybook.ui.trans

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneybook.domain.DayGroup
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.ui.common.DayHeader
import com.moneybook.ui.common.EmptyState
import com.moneybook.ui.common.Hairline
import com.moneybook.ui.common.TxnRowItem
import com.moneybook.ui.common.money
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens

/** [header] (category detail's subcategory breakdown) scrolls above the first day. [balances] (account detail) adds each entry's running balance: "(Balance  x)" on the newest, "(x)" on the rest. */
@Composable
fun DailyTab(groups: List<DayGroup>, spec: CurrencySpec, onOpenTxn: (String) -> Unit, balances: Map<String, Long>? = null, header: (@Composable () -> Unit)? = null) {
    if (groups.isEmpty()) {
        Box(Modifier.fillMaxSize().background(Mb.colors.surface)) { EmptyState() }
        return
    }
    val newestId = groups.first().rows.maxByOrNull { it.dateTime }?.id
    LazyColumn(
        Modifier.fillMaxSize().background(Mb.colors.background),
        contentPadding = PaddingValues(bottom = 88.dp),
    ) {
        header?.let { item(key = "header") { it() } }
        groups.forEach { group ->
            item(key = "h-${group.date}") { DayHeader(group, spec) }
            items(group.rows, key = { it.id }) { row ->
                val balance = balances?.get(row.id)?.let { b ->
                    val text = money(b, spec, withSymbol = false)
                    if (row.id == newestId) "(Balance  $text)" else "($text)"
                }
                TxnRowItem(row, spec, balance = balance) { onOpenTxn(row.id) }
            }
            item(key = "g-${group.date}") {
                Column {
                    Hairline()
                    Spacer(Modifier.height(MbDimens.groupGap))
                    Hairline()
                }
            }
        }
    }
}
