package com.moneybook.ui.trans

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.moneybook.domain.CalendarCell
import com.moneybook.domain.DayGroup
import com.moneybook.domain.Formats
import com.moneybook.domain.Totals
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.ui.common.AddFab
import com.moneybook.ui.common.DayHeader
import com.moneybook.ui.common.EmptyState
import com.moneybook.ui.common.FitText
import com.moneybook.ui.common.Hairline
import com.moneybook.ui.common.LocalAppLocked
import com.moneybook.ui.common.TxnRowItem
import com.moneybook.ui.common.cellLines
import com.moneybook.ui.common.money
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType
import java.time.DayOfWeek
import java.time.LocalDate

@Composable
fun CalendarTab(cells: List<CalendarCell>, weekStart: DayOfWeek, spec: CurrencySpec, onDayClick: (LocalDate) -> Unit) {
    Column(Modifier.fillMaxSize().background(Mb.colors.surface).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().heightIn(min = MbDimens.calendarHeader).height(IntrinsicSize.Min).background(Mb.colors.background)) {
            Formats.weekdays(weekStart).forEach { day ->
                val color = when (day) {
                    DayOfWeek.SUNDAY -> Mb.colors.sundayHeader
                    DayOfWeek.SATURDAY -> Mb.colors.saturday
                    else -> Mb.colors.textTertiary
                }
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Text(Formats.weekdayShort(day), style = MbType.calendarHeader, color = color)
                }
            }
        }
        Hairline(px = 2)
        cells.chunked(7).forEach { week ->
            // Cells grow with large fonts; IntrinsicSize keeps a week's cells the same height.
            Row(Modifier.fillMaxWidth().heightIn(min = MbDimens.calendarCell).height(IntrinsicSize.Min)) {
                week.forEach { cell ->
                    CalendarCellView(cell, spec, Modifier.weight(1f).fillMaxHeight().clickable { onDayClick(cell.date) })
                }
            }
        }
    }
}

@Composable
private fun CalendarCellView(cell: CalendarCell, spec: CurrencySpec, modifier: Modifier) {
    val c = Mb.colors
    val divider = c.divider
    Column(
        modifier.background(if (cell.inPeriod) c.surface else c.background).cellLines(divider, with(LocalDensity.current) { 2.toDp() }),
    ) {
        val dayColor = when {
            cell.isToday -> c.todayText
            !cell.inPeriod -> c.dayOutside
            cell.date.dayOfWeek == DayOfWeek.SUNDAY -> c.sunday
            else -> c.textBody
        }
        Text(
            Formats.calendarDay(cell.date),
            style = MbType.calendarDay,
            color = dayColor,
            modifier = Modifier.fillMaxWidth().heightIn(min = 19.dp)
                .then(if (cell.isToday) Modifier.background(c.today) else Modifier)
                .padding(start = 2.dp, top = 1.dp),
        )
        val t: Totals = cell.totals
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
            Slot(if (t.income != 0L) money(t.income, spec, withSymbol = false) else null, if (cell.inPeriod) c.income else c.incomeFaded)
            Slot(if (t.expense != 0L) money(t.expense, spec, withSymbol = false) else null, if (cell.inPeriod) c.expense else c.expenseFaded)
            Slot(if (t.income != 0L && t.expense != 0L) money(t.total, spec, withSymbol = false) else null, c.textBody)
        }
    }
}

@Composable
private fun Slot(text: String?, color: Color) {
    Box(Modifier.fillMaxWidth().heightIn(min = 16.dp).padding(end = 3.dp), contentAlignment = Alignment.CenterEnd) {
        if (text != null) FitText(text, MbType.calendarAmount, color)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DaySheet(
    date: LocalDate,
    group: DayGroup?,
    spec: CurrencySpec,
    onDismiss: () -> Unit,
    onOpenTxn: (String) -> Unit,
    onAdd: ((LocalDate) -> Unit)?,
) {
    if (LocalAppLocked.current) return // a window of its own, above the lock screen
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Mb.colors.surface) {
        DayHeader(group ?: DayGroup(date, Totals(), emptyList()), spec)
        if (group == null) {
            EmptyState()
        } else {
            group.rows.forEach { row -> TxnRowItem(row, spec) { onOpenTxn(row.id) } }
        }
        if (onAdd != null) {
            Box(Modifier.fillMaxWidth().padding(MbDimens.fabMargin), contentAlignment = Alignment.CenterEnd) {
                AddFab(onClick = { onAdd(date) })
            }
        }
    }
}
