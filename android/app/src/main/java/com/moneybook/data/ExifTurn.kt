package com.moneybook.data

import android.graphics.Matrix
import android.media.ExifInterface

/** An EXIF orientation as a horizontal mirror (applied first) followed by a clockwise turn. */
data class ExifTurn(val degrees: Int, val mirror: Boolean)

/** The EXIF orientation tag values 1..8; anything else (0 = undefined) is shown as stored. */
fun exifTurn(orientation: Int): ExifTurn = when (orientation) {
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> ExifTurn(0, true)
    ExifInterface.ORIENTATION_ROTATE_180 -> ExifTurn(180, false)
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> ExifTurn(180, true)
    ExifInterface.ORIENTATION_TRANSPOSE -> ExifTurn(270, true)
    ExifInterface.ORIENTATION_ROTATE_90 -> ExifTurn(90, false)
    ExifInterface.ORIENTATION_TRANSVERSE -> ExifTurn(90, true)
    ExifInterface.ORIENTATION_ROTATE_270 -> ExifTurn(270, false)
    else -> ExifTurn(0, false)
}

fun ExifTurn.matrix(): Matrix = Matrix().apply {
    if (mirror) postScale(-1f, 1f)
    postRotate(degrees.toFloat())
}
