package com.moneybook.ui.edit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import com.moneybook.data.exifTurn
import com.moneybook.data.matrix
import java.io.File
import kotlin.math.max

/** The largest power of two that still leaves the long side at or above [maxSide]. */
internal fun photoSampleSize(width: Int, height: Int, maxSide: Int): Int {
    var sample = 1
    while (max(width, height) / (sample * 2) >= maxSide) sample *= 2
    return sample
}

/**
 * The photo in [file] sampled so its long side stays at or above [maxSide] and turned upright by its EXIF orientation,
 * or null when it is not an image. Photos up to 2 MiB are stored unchanged with their EXIF tag and BitmapFactory ignores it,
 * so every place that shows a photo (the form's strip, the viewer) decodes it here. The format is read from the bytes:
 * the stored name always ends in .jpg, even for a PNG. Blocking: call it off the main thread.
 */
fun decodePhoto(file: File, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val bitmap = BitmapFactory.decodeFile(
        file.path, BitmapFactory.Options().apply { inSampleSize = photoSampleSize(bounds.outWidth, bounds.outHeight, maxSide) },
    ) ?: return null
    val orientation = runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val turn = exifTurn(orientation)
    if (turn.degrees == 0 && !turn.mirror) return bitmap
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, turn.matrix(), true)
}
