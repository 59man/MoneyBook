package com.moneybook.ui.common

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex

/**
 * A column whose rows are dragged by a handle to reorder them (Realbyte's ≡ handle). [row] receives
 * the modifier to put on its handle. [onMove] fires as soon as the dragged row passes a neighbour's
 * midpoint, so the caller's list stays in display order; [onDragEnd] is the moment to save it.
 * Rows are keyed by [keyOf], so a row keeps its drag while it moves through the list.
 */
@Composable
fun <T> ReorderableColumn(
    items: List<T>,
    keyOf: (T) -> String,
    onMove: (from: Int, to: Int) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
    row: @Composable (item: T, handle: Modifier) -> Unit,
) {
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<String, Int>() }
    val currentItems by rememberUpdatedState(items)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    Column(modifier) {
        items.forEach { item ->
            val k = keyOf(item)
            key(k) {
                val isDragged = dragging == k
                Box(
                    Modifier
                        .onSizeChanged { heights[k] = it.height }
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragged) offset else 0f },
                ) {
                    val handle = Modifier.pointerInput(k) {
                        // The drag keeps its own order: several drag events can arrive before the caller's
                        // new list recomposes, and a stale index would move the row straight back.
                        val order = mutableListOf<String>()
                        fun finish() {
                            dragging = null
                            offset = 0f
                            currentOnDragEnd()
                        }
                        detectDragGestures(
                            onDragStart = {
                                order.clear()
                                order += currentItems.map(keyOf)
                                dragging = k
                                offset = 0f
                            },
                            onDragEnd = { finish() },
                            onDragCancel = { finish() },
                        ) { change, amount ->
                            change.consume()
                            offset += amount.y
                            val i = order.indexOf(k)
                            if (i < 0) return@detectDragGestures
                            val next = order.getOrNull(i + 1)?.let { heights[it] }
                            val previous = order.getOrNull(i - 1)?.let { heights[it] }
                            if (next != null && offset > next / 2f) {
                                order.add(i + 1, order.removeAt(i))
                                currentOnMove(i, i + 1)
                                offset -= next
                            } else if (previous != null && offset < -previous / 2f) {
                                order.add(i - 1, order.removeAt(i))
                                currentOnMove(i, i - 1)
                                offset += previous
                            }
                        }
                    }
                    row(item, handle)
                }
            }
        }
    }
}
