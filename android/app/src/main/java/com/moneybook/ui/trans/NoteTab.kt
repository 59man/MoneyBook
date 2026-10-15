package com.moneybook.ui.trans

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moneybook.R
import com.moneybook.data.db.MemoEntity
import com.moneybook.domain.Formats
import com.moneybook.ui.common.EmptyState
import com.moneybook.ui.common.Hairline
import com.moneybook.ui.common.MbIcon
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import com.moneybook.ui.theme.MbType
import java.time.LocalDate

/** Trans → Note: the month's memos, pinned first, or the standard empty state. */
@Composable
fun NoteTab(memos: List<MemoEntity>, onOpen: (String) -> Unit) {
    if (memos.isEmpty()) {
        Box(Modifier.fillMaxSize().background(Mb.colors.surface)) { EmptyState() }
        return
    }
    LazyColumn(Modifier.fillMaxSize().background(Mb.colors.surface)) {
        items(memos, key = { it.id }) { memo -> MemoRow(memo) { onOpen(memo.id) } }
    }
}

@Composable
private fun MemoRow(memo: MemoEntity, onClick: () -> Unit) {
    val colors = Mb.colors
    Column(Modifier.background(colors.surface).clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = MbDimens.txnRow)) {
            Box(Modifier.width(MbDimens.memoColorBar).fillMaxHeight().background(memo.color?.let { Color(it) } ?: Color.Transparent))
            Column(Modifier.weight(1f).padding(start = MbDimens.sidePadding - MbDimens.memoColorBar, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
                Text(Formats.memoDate(LocalDate.parse(memo.date)), style = MbType.rowAccount, color = colors.textSecondary)
                if (memo.title.isNotBlank()) {
                    Text(memo.title, style = MbType.memoRowTitle, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (memo.text.isNotBlank()) {
                    Text(memo.text.lineSequence().first(), style = MbType.rowNote, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (memo.pinned) {
                Box(Modifier.fillMaxHeight().padding(end = MbDimens.sidePadding), contentAlignment = Alignment.Center) {
                    MbIcon(R.drawable.ic_pin, "Pinned", tint = colors.accent, size = 18.dp)
                }
            }
        }
        Hairline()
    }
}

/** Realbyte's Note FAB: a white circle with an accent outline and a "list +" icon. */
@Composable
fun MemoFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(MbDimens.fab).shadow(6.dp, CircleShape).clip(CircleShape).background(Mb.colors.surface)
            .border(2.dp, Mb.colors.accent, CircleShape).clickable(onClick = onClick).semantics { contentDescription = "Add note" },
        contentAlignment = Alignment.Center,
    ) {
        MbIcon(R.drawable.ic_memo_add, null, tint = Mb.colors.accent, size = 28.dp)
    }
}
