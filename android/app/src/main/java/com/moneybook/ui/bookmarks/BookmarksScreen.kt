package com.moneybook.ui.bookmarks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moneybook.data.BookmarkRepository
import com.moneybook.data.SettingsRepository
import com.moneybook.data.db.BookmarkRow
import com.moneybook.shared.money.CurrencySpec
import com.moneybook.ui.common.ConfirmDialog
import com.moneybook.ui.common.Hairline
import com.moneybook.ui.common.MbTopBar
import com.moneybook.ui.common.TitleText
import com.moneybook.ui.common.TxnRowItem
import com.moneybook.ui.common.appContainer
import com.moneybook.ui.common.specOf
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbType
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BookmarksState(val rows: List<BookmarkRow> = emptyList(), val currency: CurrencySpec = specOf("USD"))

class BookmarksViewModel(private val bookmarks: BookmarkRepository, settings: SettingsRepository) : ViewModel() {
    val state: StateFlow<BookmarksState> = combine(bookmarks.observeRows(), settings.settings) { rows, s ->
        BookmarksState(rows, specOf(s.mainCurrency))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookmarksState())

    fun delete(id: String) {
        viewModelScope.launch { bookmarks.delete(id) }
    }
}

/** Realbyte's help text on the Bookmarks screen (capture 20). */
private const val HELP =
    "Please select the contents in Trans. tab. There are Delete and Copy, Bookmark, Edit button. Tap Bookmark button and it shall be registered."

@Composable
fun BookmarksScreen(onBack: () -> Unit, onUse: (String) -> Unit) {
    val c = appContainer()
    val vm: BookmarksViewModel = viewModel { BookmarksViewModel(c.bookmarks, c.settings) }
    val s by vm.state.collectAsStateWithLifecycle()
    BookmarksContent(s, onBack, onUse, vm::delete)
}

/** The help card, then the bookmarks as Daily rows: a tap starts a new entry from one, a long press deletes it. */
@Composable
fun BookmarksContent(s: BookmarksState, onBack: () -> Unit, onUse: (String) -> Unit, onDelete: (String) -> Unit) {
    var deleting by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(Mb.colors.surface).systemBarsPadding()) {
        MbTopBar(onBack = onBack, title = { TitleText("Bookmarks") })
        Hairline(px = 2)
        Column(Modifier.fillMaxWidth().background(Mb.colors.background).padding(horizontal = 23.dp, vertical = 16.dp)) {
            Text("How to register Bookmark?", style = MbType.helpTitle, color = Mb.colors.textPrimary)
            Spacer(Modifier.height(10.dp))
            Text(HELP, style = MbType.helpText, color = Mb.colors.textSecondary)
        }
        Hairline()
        LazyColumn(Modifier.weight(1f)) {
            items(s.rows, key = { it.id }) { row ->
                TxnRowItem(row.asTxnRow(), s.currency, onClick = { onUse(row.id) }, onLongClick = { deleting = row.id })
                Hairline()
            }
        }
    }
    deleting?.let { id ->
        ConfirmDialog("Delete bookmark", "Delete this bookmark?", "Delete", onConfirm = { onDelete(id) }, onDismiss = { deleting = null })
    }
}
