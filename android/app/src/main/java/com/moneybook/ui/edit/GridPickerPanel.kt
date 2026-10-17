package com.moneybook.ui.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.lazy.items as columnItems
import androidx.compose.ui.unit.dp
import com.moneybook.R
import com.moneybook.data.db.AccountEntity
import com.moneybook.data.db.AccountGroupEntity
import com.moneybook.data.db.CategoryEntity
import com.moneybook.ui.common.FitText
import com.moneybook.ui.common.MbIcon
import com.moneybook.ui.common.PanelHeader
import com.moneybook.ui.common.cellLines
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType

/**
 * 3-column grid panel for categories and accounts (Realbyte). The panel is always 350 dp tall: fewer items leave the
 * background showing below them, more scroll. [actions] go in the header left of the close button.
 */
@Composable
fun GridPickerPanel(
    title: String, items: List<Pair<String, String>>, onPick: (String) -> Unit, onClose: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().background(Mb.colors.background)) {
        PanelHeader(title, onClose, titleInset = 20.dp, actions = actions)
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxWidth().height(MbDimens.pickerPanel - MbDimens.panelHeader),
        ) {
            items(items, key = { it.first }) { (id, label) ->
                Box(
                    Modifier.height(MbDimens.pickerCell).background(Mb.colors.surface).cellLines(Mb.colors.divider).clickable { onPick(id) }
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    FitText(label, MbType.pickerCell, Mb.colors.textPrimary, maxLines = 3, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/**
 * Category picker with Subcategory ON: [top] on the left (a chevron on those with subcategories), the selected one's
 * subcategories on the right. A category without subcategories, or a subcategory, is picked; one with subcategories is selected.
 */
@Composable
fun TwoPaneCategoryPanel(
    top: List<CategoryEntity>, subs: List<CategoryEntity>, current: String?, onPick: (String) -> Unit, onClose: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val selected = subs.firstOrNull { it.id == current }?.parentId ?: current?.takeIf { id -> top.any { it.id == id } }
    TwoPanePanel(
        "Category", top.map { it.id to it.name }, subs.groupBy({ it.parentId.orEmpty() }, { it.id to it.name }), selected, onPick, onClose, actions,
    )
}

/**
 * The account picker by group (the header's group button): the account groups on the left, the selected group's accounts on the
 * right. Groups with no account listed are left out.
 */
@Composable
fun GroupedAccountPanel(
    groups: List<AccountGroupEntity>, accounts: List<AccountEntity>, current: String?, onPick: (String) -> Unit, onClose: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val byGroup = accounts.groupBy({ it.groupId }, { it.id to it.name })
    val left = groups.filter { it.id in byGroup }.map { it.id to it.name }
    val selected = accounts.firstOrNull { it.id == current }?.groupId ?: left.firstOrNull()?.first
    TwoPanePanel("Accounts", left, byGroup, selected, onPick, onClose, actions)
}

/** Two lists, as tall as the grid panel: [left] (a chevron on those with [right] items) selects, or picks one with none; the right list picks. */
@Composable
private fun TwoPanePanel(
    title: String, left: List<Pair<String, String>>, right: Map<String, List<Pair<String, String>>>, initial: String?,
    onPick: (String) -> Unit, onClose: () -> Unit, actions: @Composable RowScope.() -> Unit,
) {
    var selected by remember { mutableStateOf(initial) }
    Column(Modifier.fillMaxWidth().background(Mb.colors.surface)) {
        PanelHeader(title, onClose, titleInset = 20.dp, actions = actions)
        Row(Modifier.fillMaxWidth().height(MbDimens.pickerPanel - MbDimens.panelHeader)) {
            LazyColumn(Modifier.weight(1f)) {
                columnItems(left, key = { it.first }) { (id, name) ->
                    val hasRight = right[id].orEmpty().isNotEmpty()
                    PickerRow(name, id == selected, hasRight) { if (hasRight) selected = id else onPick(id) }
                }
            }
            LazyColumn(Modifier.weight(1f)) {
                columnItems(right[selected].orEmpty(), key = { it.first }) { (id, name) -> PickerRow(name, false, false) { onPick(id) } }
            }
        }
    }
}

@Composable
private fun PickerRow(label: String, selected: Boolean, chevron: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = MbDimens.subPickerRow).background(if (selected) Mb.colors.pickerSelected else Mb.colors.surface)
            .cellLines(Mb.colors.divider).clickable(onClick = onClick).padding(start = 23.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FitText(label, MbType.choiceText, if (selected) Mb.colors.accent else Mb.colors.textPrimary, Modifier.weight(1f), maxLines = 2)
        if (chevron) MbIcon(R.drawable.ic_chevron_right, null, tint = Mb.colors.textPrimary, size = 18.dp)
    }
}
