package com.moneybook.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moneybook.R
import com.moneybook.data.RepeatRepository
import com.moneybook.data.db.RepeatRuleRow
import com.moneybook.domain.Formats
import com.moneybook.domain.Occurrence
import com.moneybook.domain.RepeatSchedule
import com.moneybook.shared.model.TxnType
import com.moneybook.ui.common.AmountText
import com.moneybook.ui.common.FitText
import com.moneybook.ui.common.Hairline
import com.moneybook.ui.common.IconButtonBox
import com.moneybook.ui.common.LocalListOptions
import com.moneybook.ui.common.MbTopBar
import com.moneybook.ui.common.TitleText
import com.moneybook.ui.common.amountMaxWidth
import com.moneybook.ui.common.appContainer
import com.moneybook.ui.common.specOf
import com.moneybook.ui.edit.typeColor
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType
import java.time.LocalDate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** A Repeat Setting row: the rule and its next occurrence (null once an installment plan is complete). */
data class RepeatItem(val row: RepeatRuleRow, val next: Occurrence?)

/** Repeat Setting's rows, soonest next date first; complete installment plans last. */
fun repeatItems(rows: List<RepeatRuleRow>): List<RepeatItem> = rows
    .map { r -> RepeatItem(r, RepeatSchedule.next(r.frequency, LocalDate.parse(r.startDate), LocalDate.parse(r.lastDate), r.installments)) }
    .sortedWith(compareBy(nullsLast<LocalDate>()) { item: RepeatItem -> item.next?.date })

/** "Every Month", or an installment plan's coming part, "Installment 3/6". */
fun RepeatItem.title(): String = when (val parts = row.installments) {
    null -> row.frequency.label
    else -> "Installment ${next?.let { it.index + 1 } ?: parts}/$parts"
}

fun RepeatItem.dateText(): String = next?.let { Formats.memoDate(it.date) } ?: "Finished"

data class RepeatSettingState(val items: List<RepeatItem> = emptyList())

class RepeatSettingViewModel(repeats: RepeatRepository) : ViewModel() {
    val state: StateFlow<RepeatSettingState> = repeats.observeRows().map { RepeatSettingState(repeatItems(it)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RepeatSettingState())
}

private const val HELP_TITLE = "How to setup 'Repeat' transactions?"

/** Realbyte's help text on Repeat Setting (capture 44). */
private const val HELP = "You can add 'repetitive' income, expenses and transfer transaction by tapping the recycle icon(Rep/Inst.)."

@Composable
fun RepeatSettingScreen(onBack: () -> Unit, onAdd: () -> Unit, onOpen: (String) -> Unit) {
    val c = appContainer()
    val vm: RepeatSettingViewModel = viewModel { RepeatSettingViewModel(c.repeats) }
    val s by vm.state.collectAsStateWithLifecycle()
    RepeatSettingContent(s, onBack, onAdd, onOpen)
}

/** Configuration → Repeat Setting: help card, the timing, then the rules; a tap opens the rule in the entry form. */
@Composable
fun RepeatSettingContent(s: RepeatSettingState, onBack: () -> Unit, onAdd: () -> Unit, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxSize().background(Mb.colors.background).systemBarsPadding()) {
        MbTopBar(onBack = onBack, title = { TitleText("Repeat Setting") }, actions = { IconButtonBox(R.drawable.ic_add, "Add", onAdd) })
        Hairline(px = 2)
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item(key = "help") {
                Column(Modifier.fillMaxWidth().background(Mb.colors.background).padding(horizontal = 23.dp, vertical = 16.dp)) {
                    Text(HELP_TITLE, style = MbType.helpTitle, color = Mb.colors.textPrimary)
                    Spacer(Modifier.height(10.dp))
                    Text(HELP, style = MbType.helpText, color = Mb.colors.textSecondary)
                }
                Hairline()
            }
            item(key = "timing") {
                // MoneyBook has only Realbyte's default timing, so the row shows it without a chevron and does nothing.
                Row(
                    Modifier.fillMaxWidth().background(Mb.colors.surface).heightIn(min = MbDimens.listRow).padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Timing of reflection", style = MbType.listItem, color = Mb.colors.textPrimary, modifier = Modifier.weight(1f))
                    Text("On the date", style = MbType.listItem, color = Mb.colors.accent)
                }
                Hairline()
            }
            items(s.items, key = { it.row.id }) { item -> RuleRow(item) { onOpen(item.row.id) } }
        }
    }
}

@Composable
private fun RuleRow(item: RepeatItem, onClick: () -> Unit) {
    val r = item.row
    val colors = Mb.colors
    Column(Modifier.fillMaxWidth().background(colors.surface).clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = MbDimens.searchRow).padding(horizontal = MbDimens.sidePadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.width(MbDimens.searchDateColumn)) {
                FitText(item.title(), MbType.rowAccount, colors.accent)
                FitText(item.dateText(), MbType.rowAccount, colors.textSecondary)
            }
            Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                val category = when {
                    r.type == TxnType.TRANSFER -> "Transfer"
                    r.parentCategoryName == null -> r.categoryName.orEmpty()
                    LocalListOptions.current.subcategory -> "${r.parentCategoryName}/${r.categoryName.orEmpty()}"
                    else -> r.parentCategoryName
                }
                Text(category, style = MbType.rowCategory, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (r.note.isNotEmpty()) {
                    Text(r.note, style = MbType.rowNote, color = colors.textBody, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            AmountText(r.amount, specOf(r.currency), typeColor(r.type), Modifier.widthIn(max = amountMaxWidth()))
        }
        Hairline()
    }
}
