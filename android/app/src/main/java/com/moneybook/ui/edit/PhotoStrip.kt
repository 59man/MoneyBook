package com.moneybook.ui.edit

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.moneybook.R
import com.moneybook.data.PhotoStore
import com.moneybook.ui.common.MbIcon
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Long side of the thumbnails' decode: the widest cell is the screen width. */
private const val THUMB_MAX_SIDE = 1024

private val cellNames = listOf("First photo", "Second photo", "Third photo")

/**
 * Realbyte's thumbnail strip under Description: one cell per photo, sharing the width, center-cropped, each with a ✕ that
 * removes it from the form (Save persists that); a tap on a cell calls [onOpen] with its index. Nothing is drawn without photos. [decode] turns a name into its bitmap.
 */
@Composable
fun PhotoStrip(
    names: List<String>,
    store: PhotoStore,
    onRemove: (String) -> Unit,
    onOpen: (Int) -> Unit = {},
    decode: suspend (String) -> ImageBitmap? = { name -> decodePhoto(store.file(name), THUMB_MAX_SIDE)?.asImageBitmap() },
) {
    if (names.isEmpty()) return
    Row(Modifier.fillMaxWidth().padding(horizontal = MbDimens.photoStripSide).padding(top = MbDimens.photoStripTop).height(MbDimens.photoStripHeight)) {
        names.forEachIndexed { i, name ->
            key(name) {
                // Decoded once per name: removing a neighbour keeps the others' bitmaps.
                val bitmap by produceState<ImageBitmap?>(null, name) { value = withContext(Dispatchers.IO) { decode(name) } }
                val shape = RoundedCornerShape(MbDimens.photoCorner)
                Box(
                    Modifier.weight(1f).fillMaxSize().padding(horizontal = MbDimens.photoCellMargin).clip(shape).background(Mb.colors.background).clickable { onOpen(i) }
                        .semantics { contentDescription = cellNames.getOrElse(i) { "Photo" } },
                ) {
                    bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    Box(
                        Modifier.align(Alignment.TopEnd).size(MbDimens.photoRemove).background(Mb.colors.photoRemoveScrim)
                            .clickable { onRemove(name) }.semantics { contentDescription = "Delete" },
                        contentAlignment = Alignment.Center,
                    ) { MbIcon(R.drawable.ic_close, null, tint = Color.White, size = MbDimens.photoRemoveGlyph) }
                }
            }
        }
    }
}
