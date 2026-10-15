package com.moneybook.ui.edit

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.moneybook.AppLock
import com.moneybook.R
import com.moneybook.data.PhotoStore
import com.moneybook.ui.common.IconButtonBox
import com.moneybook.ui.common.MessageDialog
import com.moneybook.ui.theme.Mb
import com.moneybook.ui.theme.MbDimens
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Long side of the viewer's decode: enough for a 10x zoom of a screen-wide photo without holding a 2048 px original twice. */
private const val VIEWER_MAX_SIDE = 2048
private const val MIN_SCALE = 0.75f
private const val MAX_SCALE = 10f

/** The scale after a pinch of [zoom]; Realbyte's PhotoView allows 0.75x to 10x. */
internal fun nextPhotoScale(scale: Float, zoom: Float) = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)

/** A double tap toggles 1x and 2x: anything zoomed in goes back to 1x, anything else goes to 2x. */
internal fun doubleTapScale(scale: Float) = if (scale > 1f) 1f else 2f

/**
 * Realbyte's photo viewer (PhotoViewPagerActivity): a black full-screen pager opened at [start], a 54 dp top bar with ←, page dots
 * and, for a photo in [saved] (already stored with the entry), a download icon. One tap hides or shows the bar. It is drawn over
 * the form, takes every pointer and closes on Back.
 */
@Composable
fun PhotoViewer(
    names: List<String>,
    saved: Set<String>,
    start: Int,
    store: PhotoStore,
    onBack: () -> Unit,
    onDownload: (String) -> Unit,
    decode: suspend (String) -> ImageBitmap? = { name -> decodePhoto(store.file(name), VIEWER_MAX_SIDE)?.asImageBitmap() },
) {
    BackHandler(onBack = onBack)
    val pager = rememberPagerState(initialPage = start.coerceIn(0, max(0, names.size - 1))) { names.size }
    var bar by rememberSaveable { mutableStateOf(true) }
    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {}) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { names[it] }) { i ->
            val bitmap by produceState<ImageBitmap?>(null, names[i]) { value = withContext(Dispatchers.IO) { decode(names[i]) } }
            ZoomablePage(bitmap, "Photo ${i + 1}") { bar = !bar }
        }
        AnimatedVisibility(
            bar, Modifier.align(Alignment.TopCenter),
            enter = slideInVertically(tween(100)) { -it }, exit = slideOutVertically(tween(100)) { -it },
        ) {
            Box(Modifier.fillMaxWidth().background(Mb.colors.photoViewerBar)) {
                Row(Modifier.statusBarsPadding().fillMaxWidth().height(MbDimens.photoViewerBar), verticalAlignment = Alignment.CenterVertically) {
                    IconButtonBox(R.drawable.ic_arrow_back, "Back", onBack, tint = Color.White, iconSize = MbDimens.photoViewerGlyph)
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        if (names.size > 1) repeat(names.size) { i ->
                            Spacer(
                                Modifier.padding(horizontal = MbDimens.photoViewerDotMargin).size(MbDimens.photoViewerDot)
                                    .background(if (i == pager.currentPage) Mb.colors.accent else Mb.colors.photoViewerDot, CircleShape),
                            )
                        }
                    }
                    val current = names.getOrNull(pager.currentPage)
                    if (current != null && current in saved) {
                        IconButtonBox(R.drawable.ic_download, "Download", { onDownload(current) }, tint = Color.White, iconSize = MbDimens.photoViewerDownload)
                    } else {
                        Spacer(Modifier.width(MbDimens.iconTouch))
                    }
                }
            }
        }
    }
}

/**
 * One photo, fitted inside the page. Pinch zooms about the center (0.75x to 10x), one finger pans while zoomed in, and a double tap
 * toggles 1x and 2x. A drag that cannot pan any further sideways is left unconsumed, so the pager takes it at the photo's edge.
 */
@Composable
private fun ZoomablePage(bitmap: ImageBitmap?, label: String, onTap: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    fun clamp(o: Offset, s: Float) = Offset(
        o.x.coerceIn(-max(0f, size.width * (s - 1) / 2), max(0f, size.width * (s - 1) / 2)),
        o.y.coerceIn(-max(0f, size.height * (s - 1) / 2), max(0f, size.height * (s - 1) / 2)),
    )
    Box(
        Modifier.fillMaxSize().onSizeChanged { size = it }.semantics { contentDescription = label }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTap() }, onDoubleTap = { scale = doubleTapScale(scale); offset = Offset.Zero })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pinching = event.changes.count { it.pressed } > 1
                        if (pinching || scale > 1f) {
                            val pan = event.calculatePan()
                            val next = nextPhotoScale(scale, event.calculateZoom())
                            val moved = clamp(offset + pan, next)
                            val stuckSideways = !pinching && pan.x != 0f && moved.x == offset.x
                            scale = next
                            offset = moved
                            if (!stuckSideways) event.changes.forEach { c: PointerInputChange -> if (c.positionChanged()) c.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        bitmap?.let {
            Image(
                it, null,
                Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
                contentScale = ContentScale.Fit,
            )
        }
    }
}

/** The image type from the file's bytes: the stored name always ends in .jpg, even for a small PNG or HEIC kept as it was. */
fun photoMimeType(file: File): String {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    return bounds.outMimeType ?: "image/jpeg"
}

/** `MoneyBook_yyyyMMdd_HHmmss.<ext>` for the gallery copy. */
fun photoDisplayName(mime: String, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val ext = if (mime == "image/jpeg") "jpg" else MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "jpg"
    return "MoneyBook_${DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(Instant.ofEpochMilli(nowMillis).atZone(zone))}.$ext"
}

/** The MediaStore row for Android 10+: Pictures/MoneyBook, pending until the bytes are written. */
fun photoDownloadValues(displayName: String, mime: String) = ContentValues().apply {
    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
    put(MediaStore.MediaColumns.MIME_TYPE, mime)
    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/MoneyBook")
    put(MediaStore.MediaColumns.IS_PENDING, 1)
}

/** Copies [file] into the public Pictures/MoneyBook folder (MediaStore on Android 10+, the folder itself before). Blocking. */
fun savePhotoToPictures(context: Context, file: File): Boolean = try {
    val mime = photoMimeType(file)
    val name = photoDisplayName(mime, System.currentTimeMillis())
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photoDownloadValues(name, mime))
        if (uri == null) false else {
            val written = resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } != null
            if (written) resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) else resolver.delete(uri, null, null)
            written
        }
    } else {
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "MoneyBook").apply { mkdirs() }
        val target = File(dir, name)
        file.copyTo(target, overwrite = true)
        MediaScannerConnection.scanFile(context, arrayOf(target.path), arrayOf(mime), null)
        true
    }
} catch (e: java.io.IOException) {
    false
} catch (e: SecurityException) {
    false
}

/**
 * The viewer's download: saves the stored photo and toasts "Saved.". Android 8 and 9 need the storage permission first; asking is
 * done here, and a refusal shows Realbyte's "Storage Permission" dialog. The permission prompt is a trip out of the app, so the
 * lock is told before it.
 */
@Composable
fun rememberPhotoDownload(store: PhotoStore, lock: AppLock): (String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<String?>(null) }
    var refused by remember { mutableStateOf(false) }
    fun save(name: String) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { savePhotoToPictures(context, store.file(name)) }
            Toast.makeText(context, if (ok) "Saved." else "Could not save the photo.", Toast.LENGTH_SHORT).show()
        }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        lock.returned()
        val name = pending
        pending = null
        if (!granted) refused = true else if (name != null) save(name)
    }
    if (refused) MessageDialog("Storage Permission", "This feature requires storage permission.") { refused = false }
    return { name ->
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pending = name
            lock.expectReturn()
            ask.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            save(name)
        }
    }
}
