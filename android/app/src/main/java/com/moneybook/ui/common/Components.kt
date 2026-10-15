package com.moneybook.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.moneybook.R
import com.moneybook.data.db.TxnRow
import com.moneybook.domain.CardExpenseMode
import com.moneybook.domain.DayGroup
import com.moneybook.domain.Formats
import com.moneybook.domain.countedAs
import com.moneybook.domain.isCarryOver
import com.moneybook.shared.model.TxnType
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType
import java.time.DayOfWeek
import java.time.LocalDate

@Composable
fun MbIcon(@DrawableRes id: Int, contentDescription: String?, tint: Color = Mb.colors.textPrimary, size: Dp = 24.dp) {
    Icon(painterResource(id), contentDescription, Modifier.size(size), tint = tint)
}

@Composable
fun IconButtonBox(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    width: Dp = MbDimens.iconTouch,
    tint: Color = Mb.colors.textPrimary,
    iconSize: Dp = 24.dp,
) {
    Box(Modifier.width(width).fillMaxHeight().clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        MbIcon(icon, contentDescription, tint, iconSize)
    }
}

/** A divider exactly [px] device pixels thick; Dp.Hairline anti-aliases into a lighter two-pixel line. */
@Composable
fun Hairline(color: Color = Mb.colors.divider, px: Int = 1) {
    val thickness = with(LocalDensity.current) { px.toDp() }
    Box(Modifier.fillMaxWidth().height(thickness).background(color))
}

/** Right and bottom cell borders, so neighbouring cells share one line as in Realbyte's grids. */
fun Modifier.cellLines(color: Color, width: Dp = 1.dp): Modifier = drawBehind {
    val w = width.toPx()
    drawLine(color, Offset(size.width - w / 2, 0f), Offset(size.width - w / 2, size.height), w)
    drawLine(color, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w)
}

/** A 1 px line along the bottom edge, inside the element's height: Realbyte's reorderable rows include their divider. */
fun Modifier.bottomHairline(color: Color): Modifier = drawBehind {
    drawRect(color, Offset(0f, size.height - 1f), Size(size.width, 1f))
}

@Composable
fun MbTopBar(
    onBack: (() -> Unit)? = null,
    titleInset: Dp = MbDimens.sidePadding,
    title: @Composable RowScope.() -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().height(MbDimens.topBar).background(Mb.colors.surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) IconButtonBox(R.drawable.ic_arrow_back, "Back", onBack) else Spacer(Modifier.width(titleInset))
        // The title gets what the actions leave, so a long title ellipsizes instead of pushing them off screen.
        Row(Modifier.weight(1f).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) { title() }
        actions()
    }
}

@Composable
fun TitleText(text: String) {
    Text(text, style = MbType.screenTitle, color = Mb.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
fun MonthNavigator(label: String, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
        IconButtonBox(R.drawable.ic_chevron_left, "Previous", onPrev, width = MbDimens.monthArrow, iconSize = MbDimens.monthArrowIcon)
        Text(label, style = MbType.topTitle, color = Mb.colors.textPrimary)
        IconButtonBox(R.drawable.ic_chevron_right, "Next", onNext, width = MbDimens.monthArrow, iconSize = MbDimens.monthArrowIcon)
    }
}

@Composable
fun SubTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Mb.colors.surface)) {
        Row(Modifier.fillMaxWidth().height(MbDimens.subTabRow)) {
            labels.forEachIndexed { i, label ->
                Box(Modifier.weight(1f).fillMaxHeight().clickable { onSelect(i) }, contentAlignment = Alignment.Center) {
                    FitText(
                        label, MbType.subTab, if (i == selected) Mb.colors.textPrimary else Mb.colors.tabUnselected,
                        Modifier.padding(bottom = 2.dp),
                    )
                    if (i == selected) {
                        Box(
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(MbDimens.tabIndicator)
                                .background(Mb.colors.accent),
                        )
                    }
                }
            }
        }
        Hairline(px = 2)
    }
}

@Composable
fun SummaryBar(
    items: List<SummaryItem>,
    height: Dp = MbDimens.summaryBar,
    sideInset: Dp = 0.dp,
    labelGap: Dp = 2.dp,
) {
    Column(Modifier.fillMaxWidth().background(Mb.colors.surface)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = height).padding(horizontal = sideInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                Column(Modifier.weight(1f).padding(top = 1.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    FitText(item.label, MbType.summaryLabel, Mb.colors.textBody)
                    Spacer(Modifier.height(labelGap))
                    FitText(item.value, MbType.summaryValue, item.color.resolve(Mb.colors))
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = Mb.colors.divider)
    }
}

@Composable
fun WeekdayChip(date: LocalDate) {
    val color = when (date.dayOfWeek) {
        DayOfWeek.SATURDAY -> Mb.colors.saturday
        DayOfWeek.SUNDAY -> Mb.colors.sunday
        else -> Mb.colors.weekdayChip
    }
    Text(
        Formats.weekday(date),
        style = MbType.weekdayChip,
        color = Color.White,
        modifier = Modifier.background(color, RoundedCornerShape(2.5.dp)).heightIn(min = 17.dp).wrapContentHeight()
            .padding(horizontal = 4.dp),
    )
}

@Composable
fun DayHeader(group: DayGroup, spec: CurrencySpec) {
    Column(Modifier.background(Mb.colors.surface)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = MbDimens.dayHeader).padding(horizontal = MbDimens.sidePadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(group.date.dayOfMonth.toString().padStart(2, '0'), style = MbType.dayNumber, color = Mb.colors.textPrimary, modifier = Modifier.offset(y = 1.dp))
            Spacer(Modifier.width(3.dp))
            WeekdayChip(group.date)
            Spacer(Modifier.width(4.dp))
            // Realbyte sits the date on the amounts' baseline rather than centering it.
            Text(Formats.dayHeaderDate(group.date), style = MbType.dayDate, color = Mb.colors.textTertiary, modifier = Modifier.offset(y = 2.dp))
            Spacer(Modifier.weight(1f))
            AmountText(group.totals.income, spec, Mb.colors.income, Modifier.width(MbDimens.amountColumn))
            Spacer(Modifier.width(10.dp))
            AmountText(group.totals.expense, spec, Mb.colors.expense, Modifier.width(MbDimens.amountColumn))
        }
        Hairline()
    }
}

fun TxnRow.categoryLabel(subcategory: Boolean): String = when {
    categoryName == null -> ""
    parentCategoryName == null -> categoryName
    subcategory -> "$parentCategoryName/$categoryName"
    else -> parentCategoryName
}

/** The note as the lists show it: an installment part gets its "n/N" after the note ("Telefon 2/6"). */
fun TxnRow.listNote(): String {
    val index = installmentIndex
    val total = installmentTotal
    if (index == null || total == null) return note
    return listOf(note, "$index/$total").filter { it.isNotEmpty() }.joinToString(" ")
}

/** Configuration → Time Input "Show on list" and Show description, for every entry row (provided by MainActivity). */
/** [cardLumpSum]: Card expenses display config B (card spending in the body color, payments into a card in the expense color). */
/** [subcategory]: Configuration → Subcategory ON (a subcategory entry shows its sub below its parent; OFF shows the parent only). */
data class ListOptions(
    val showTime: Boolean = false, val showDescription: Boolean = false, val cardLumpSum: Boolean = false, val subcategory: Boolean = false,
)

val LocalListOptions = compositionLocalOf { ListOptions() }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TxnRowItem(row: TxnRow, spec: CurrencySpec, onLongClick: (() -> Unit)? = null, balance: String? = null, onClick: () -> Unit) {
    val options = LocalListOptions.current
    val colors = Mb.colors
    val category: String
    var sub: String? = null
    val account: String
    var amountColor: Color
    when (row.type) {
        TxnType.TRANSFER -> { category = "Transfer"; account = "${row.accountName} → ${row.toAccountName.orEmpty()}"; amountColor = colors.textBody }
        TxnType.INCOME -> { category = row.parentCategoryName ?: row.categoryName.orEmpty(); account = row.accountName; amountColor = colors.income }
        TxnType.EXPENSE -> { category = row.parentCategoryName ?: row.categoryName.orEmpty(); account = row.accountName; amountColor = colors.expense }
    }
    if (row.type != TxnType.TRANSFER && options.subcategory && row.parentCategoryName != null) sub = row.categoryName
    val carry = row.isCarryOver
    val counted = row.countedAs(if (options.cardLumpSum) CardExpenseMode.LUMP_SUM else CardExpenseMode.AT_THE_TIME)
    if (row.type == TxnType.EXPENSE && counted == null) amountColor = colors.textBody
    if (row.type == TxnType.TRANSFER && counted == TxnType.EXPENSE) amountColor = colors.expense
    if (row.type == TxnType.TRANSFER && counted == TxnType.INCOME) amountColor = colors.income
    Row(
        Modifier.fillMaxWidth().heightIn(min = MbDimens.txnRow).background(colors.surface)
            .combinedClickable(enabled = !carry, onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = MbDimens.sidePadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(80.dp)) {
            Text(if (carry) "Carry-over" else category, style = MbType.rowCategory, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!carry) sub?.let { Text(it, style = MbType.rowSubcategory, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)) }
            // Bookmark rows have no date, so they show no time.
            if (!carry && options.showTime && row.dateTime.length >= 16) {
                Text(row.dateTime.substring(11, 16), style = MbType.rowAccount, color = colors.textSecondary, maxLines = 1)
            }
        }
        Spacer(Modifier.width(3.dp))
        Column(Modifier.weight(1f).padding(top = 2.dp), verticalArrangement = Arrangement.Center) {
            val note = row.listNote()
            if (!carry && note.isNotEmpty()) {
                Text(note, style = MbType.rowNote, color = colors.textBody, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(5.dp))
            }
            if (!carry) {
                // Realbyte's photo icon follows the second line: after the account under a note, else on a line of its own.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(account, Modifier.weight(1f, fill = false), style = MbType.rowAccount, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (row.hasPhoto && note.isNotEmpty()) PhotoMark(Modifier.padding(start = 2.dp))
                }
                if (row.hasPhoto && note.isEmpty()) PhotoMark(Modifier.padding(start = 2.dp, top = 4.dp))
            }
            if (!carry && options.showDescription && row.description.isNotEmpty()) {
                Text(row.description, style = MbType.rowAccount, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (carry && row.amount == 0L) Unit // a zero carry-over prints no amount
        else if (balance == null) {
            AmountText(row.amount, specOf(row.currency), amountColor, Modifier.widthIn(max = amountMaxWidth()))
        } else {
            // Account detail: the account's balance after this entry, under the amount.
            Column(horizontalAlignment = Alignment.End) {
                AmountText(row.amount, specOf(row.currency), amountColor, Modifier.widthIn(max = amountMaxWidth()))
                FitText(balance, MbType.rowAccount, colors.textSecondary, textAlign = TextAlign.End)
            }
        }
    }
}

/** The small solid image icon of an entry that has photos (Realbyte's faPhotoIcon, in the account text's color). */
@Composable
fun PhotoMark(modifier: Modifier = Modifier) {
    Box(modifier) { MbIcon(R.drawable.ic_image, "Photo", Mb.colors.textSecondary, MbDimens.listPhotoIcon) }
}

@Composable
fun EmptyState(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        MbIcon(R.drawable.ic_empty, null, tint = Mb.colors.divider, size = 56.dp)
        Spacer(Modifier.height(6.dp))
        Text("No data available.", style = MbType.emptyText, color = Mb.colors.textSecondary)
    }
}

@Composable
fun AddFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(MbDimens.fab).shadow(6.dp, CircleShape).clip(CircleShape).background(Mb.colors.accent)
            .clickable(onClick = onClick).semantics { contentDescription = "Add" },
        contentAlignment = Alignment.Center,
    ) {
        MbIcon(R.drawable.ic_add, null, tint = Color.White, size = 30.dp)
    }
}

/**
 * Realbyte's overflow menu: a white box with a 2 px border, flush with the right edge and hanging from the
 * bottom of the top bar. Put it next to the menu button so the button is its anchor.
 */
@Composable
fun MenuPopup(
    expanded: Boolean,
    onDismiss: () -> Unit,
    items: List<Pair<String, () -> Unit>>,
    width: Dp = MbDimens.menuWidth,
    itemHeight: Dp = MbDimens.menuItem,
    itemPadding: Dp = 15.dp,
) {
    if (!expanded) return
    if (LocalAppLocked.current) return // a window of its own, above the lock screen
    val border = with(LocalDensity.current) { 2.toDp() }
    Popup(popupPositionProvider = HangFromAnchorAtEnd, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Column(
            Modifier.width(width).shadow(16.dp).background(Mb.colors.surface)
                .border(border, Mb.colors.divider).padding(border),
        ) {
            items.forEach { (label, action) ->
                Text(
                    label, style = MbType.listItem, color = Mb.colors.textPrimary,
                    modifier = Modifier.fillMaxWidth().clickable { onDismiss(); action() }
                        .heightIn(min = itemHeight).padding(horizontal = itemPadding).wrapContentHeight(),
                )
            }
        }
    }
}

/** Top edge on the anchor's bottom (the menu's border covers the top bar's divider), right edge on the window's. */
private object HangFromAnchorAtEnd : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
        IntOffset(windowSize.width - popupContentSize.width, anchorBounds.bottom)
}

/** The label and big amount on top of category detail and Total Stats ("Total Balance" / "8,229.00Kč"). */
@Composable
fun BigTotal(label: String, amount: String) {
    Column(
        Modifier.fillMaxWidth().background(Mb.colors.surface)
            .padding(start = MbDimens.bigTotalInset, end = MbDimens.sidePadding, top = 17.dp, bottom = 10.dp),
    ) {
        Text(label, style = MbType.summaryLabel, color = Mb.colors.textSecondary)
        Spacer(Modifier.height(5.dp))
        FitText(amount, MbType.bigAmount, Mb.colors.textPrimary)
    }
}
