package com.moneybook.ui.memo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moneybook.data.MemoRepository
import com.moneybook.data.TimeSource
import com.moneybook.data.db.MemoEntity
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MemoEditState(
    val loaded: Boolean = false,
    val isNew: Boolean = true,
    val date: LocalDate,
    val title: String = "",
    val text: String = "",
    /** ARGB, or null for none. */
    val color: Int? = null,
    val pinned: Boolean = false,
    val paletteOpen: Boolean = false,
)

/** The editor has closed (saved, discarded or deleted). */
object MemoClosed

class MemoEditViewModel(
    memoId: String?,
    date: LocalDate?,
    private val memos: MemoRepository,
    time: TimeSource,
) : ViewModel() {
    private val _state = MutableStateFlow(MemoEditState(date = date ?: time.today()))
    val state: StateFlow<MemoEditState> = _state.asStateFlow()

    private val _events = Channel<MemoClosed>(Channel.BUFFERED)
    val events: Flow<MemoClosed> = _events.receiveAsFlow()

    private var original: MemoEntity? = null
    /** Fixed per editor, so a double-tapped Back writes the same row. */
    private val newId = UUID.randomUUID().toString()
    private var changed = false
    private var closing = false

    init {
        viewModelScope.launch {
            val memo = memoId?.let { memos.get(it) }
            original = memo
            _state.update { s ->
                if (memo == null) {
                    s.copy(loaded = true)
                } else {
                    s.copy(
                        loaded = true, isNew = false, date = LocalDate.parse(memo.date), title = memo.title, text = memo.text,
                        color = memo.color, pinned = memo.pinned,
                    )
                }
            }
        }
    }

    private fun edit(change: (MemoEditState) -> MemoEditState) {
        changed = true
        _state.update(change)
    }

    fun setTitle(title: String) = edit { it.copy(title = title) }
    fun setText(text: String) = edit { it.copy(text = text) }
    fun setDate(date: LocalDate) = edit { it.copy(date = date) }
    fun setColor(color: Int?) = edit { it.copy(color = color, paletteOpen = false) }
    fun togglePin() = edit { it.copy(pinned = !it.pinned) }
    fun togglePalette() = _state.update { it.copy(paletteOpen = !it.paletteOpen) }

    /** Back: saves a changed memo that is not blank. A blank memo is never written, so clearing one keeps its old text. */
    fun close() {
        if (closing) return
        closing = true
        val s = _state.value
        viewModelScope.launch {
            if (changed && (s.title.isNotBlank() || s.text.isNotBlank())) {
                val base = original ?: MemoEntity(id = newId, date = "", updatedAt = 0)
                memos.save(base.copy(date = s.date.toString(), title = s.title.trim(), text = s.text.trimEnd(), color = s.color, pinned = s.pinned))
            }
            _events.send(MemoClosed)
        }
    }

    fun delete() {
        val id = original?.id ?: return
        closing = true
        viewModelScope.launch {
            memos.delete(id)
            _events.send(MemoClosed)
        }
    }
}
