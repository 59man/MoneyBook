package com.moneybook.ui.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.moneybook.data.db.CategoryEntity
import com.moneybook.ui.common.FitText
import com.moneybook.ui.common.MbIcon
import com.moneybook.ui.common.PanelHeader
import com.moneybook.ui.common.cellLines
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType

/** 3-column grid panel for categories and accounts; shows five rows and scrolls (Realbyte). */
@Composable
fun GridPickerPanel(title: String, items: List<Pair<String, String>>, onPick: (String) -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Mb.colors.surface)) {
        PanelHeader(title, onClose, titleInset = 20.dp)
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxWidth().heightIn(max = MbDimens.pickerCell * 5),
        ) {
            items(items, key = { it.first }) { (id, label) ->
                Box(
                    Modifier.height(MbDimens.pickerCell).cellLines(Mb.colors.divider).clickable { onPick(id) }
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
) {
    val subsOf = subs.groupBy { it.parentId }
    var selected by remember { mutableStateOf(subs.firstOrNull { it.id == current }?.parentId ?: current?.takeIf { id -> top.any { it.id == id } }) }
    Column(Modifier.fillMaxWidth().background(Mb.colors.surface)) {
        PanelHeader("Category", onClose, titleInset = 20.dp)
        Row(Modifier.fillMaxWidth().heightIn(max = MbDimens.pickerCell * 5)) {
            LazyColumn(Modifier.weight(1f)) {
                columnItems(top, key = { it.id }) { c ->
                    val hasSubs = subsOf[c.id].orEmpty().isNotEmpty()
                    PickerRow(c.name, c.id == selected, hasSubs) { if (hasSubs) selected = c.id else onPick(c.id) }
                }
            }
            LazyColumn(Modifier.weight(1f)) {
                columnItems(subsOf[selected].orEmpty(), key = { it.id }) { c -> PickerRow(c.name, false, false) { onPick(c.id) } }
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
