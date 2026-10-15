package com.moneybook.ui.more

import android.content.ContentResolver
import android.net.Uri
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

internal const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

/**
 * Opens [uri] for writing from the start. It uses "wt" because with plain "w" some providers keep the
 * old tail when a picked file is overwritten, which corrupts the file.
 */
internal fun ContentResolver.writer(uri: Uri): () -> OutputStream =
    { openOutputStream(uri, "wt") ?: throw IOException("Cannot open the file") }

internal fun ContentResolver.reader(uri: Uri): () -> InputStream =
    { openInputStream(uri) ?: throw IOException("Cannot open the selected file") }
