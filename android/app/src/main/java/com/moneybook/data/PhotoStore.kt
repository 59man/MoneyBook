package com.moneybook.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import com.moneybook.data.db.MoneyBookDatabase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Realbyte's photo rules: up to 2 MiB is kept as it is; more is scaled to 2048 px and re-encoded at these JPEG qualities. */
const val PHOTO_MAX_BYTES = 2 * 1024 * 1024
const val PHOTO_MAX_SIDE = 2048
val PHOTO_QUALITIES = listOf(100, 97, 94, 91)
const val SWEEP_GRACE_MS = 10 * 60 * 1000L

sealed interface PhotoPlan {
    data object Keep : PhotoPlan

    /** Decode with [sampleSize], then scale to [width] x [height] (the long side is at most [PHOTO_MAX_SIDE]; never scaled up). */
    data class Scale(val width: Int, val height: Int, val sampleSize: Int) : PhotoPlan
}

fun photoPlan(width: Int, height: Int, bytes: Int): PhotoPlan {
    if (bytes <= PHOTO_MAX_BYTES) return PhotoPlan.Keep
    val factor = max(width, height).toDouble() / PHOTO_MAX_SIDE
    if (factor <= 1) return PhotoPlan.Scale(width, height, 1)
    var sample = 1
    while (sample * 2 <= factor) sample *= 2
    return PhotoPlan.Scale(max(1, (width / factor).roundToInt()), max(1, (height / factor).roundToInt()), sample)
}

/** The private photo folder: files are `<md5 of their bytes>.jpg`, so a photo used twice is stored once. */
class PhotoStore(val dir: File) {
    fun file(name: String) = File(dir, name)

    /** Stores the image in [input] and returns its file name. Throws [IOException] when it is not an image. */
    suspend fun import(input: InputStream): String = withContext(Dispatchers.IO) {
        val bytes = input.use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("This file format is unsupported.")
        val plan = photoPlan(bounds.outWidth, bounds.outHeight, bytes.size)
        store(if (plan is PhotoPlan.Scale) reencode(bytes, plan) else bytes)
    }

    private fun reencode(bytes: ByteArray, plan: PhotoPlan.Scale): ByteArray {
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = plan.sampleSize })
            ?: throw IOException("This file format is unsupported.")
        val scaled = if (decoded.width == plan.width && decoded.height == plan.height) decoded
        else Bitmap.createScaledBitmap(decoded, plan.width, plan.height, true)
        val turn = exifTurn(ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
        val upright = if (turn.degrees == 0 && !turn.mirror) scaled else Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, turn.matrix(), true)
        var out = ByteArray(0)
        for (q in PHOTO_QUALITIES) {
            out = ByteArrayOutputStream().also { upright.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()
            if (out.size <= PHOTO_MAX_BYTES) break
        }
        return out
    }

    private fun store(bytes: ByteArray): String {
        val name = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) } + ".jpg"
        val target = file(name)
        if (!target.exists()) {
            dir.mkdirs()
            val tmp = File(dir, "$name.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw IOException("Could not store the photo.")
            }
        }
        return name
    }

    /**
     * Deletes every file that is not in [keep] (and any half-written leftovers), except what changed in the last [SWEEP_GRACE_MS]:
     * a photo imported a moment ago may not be attached to an entry yet.
     */
    fun sweep(keep: Set<String>, now: Long = System.currentTimeMillis()) {
        dir.listFiles()?.forEach { if (it.name !in keep && now - it.lastModified() > SWEEP_GRACE_MS) it.deleteRecursively() }
    }

    /** An empty folder next to the photo folder for a restore to unpack into. */
    fun newStaging(): File = File(dir.parentFile, dir.name + "-restore").also { it.deleteRecursively() }

    /**
     * Moves the unpacked photos of a restore into the folder (a name already there is the same bytes) and removes [staging].
     * Throws [IOException] when a file can't be moved: the rows must not name a photo that is gone.
     */
    fun adopt(staging: File) {
        try {
            dir.mkdirs()
            staging.listFiles()?.forEach { f ->
                val target = file(f.name)
                if (!target.exists() && !f.renameTo(target)) throw IOException("Could not store the photo ${f.name}.")
            }
        } finally {
            staging.deleteRecursively()
        }
    }
}

/** Removes photo files no live attachment uses: at app start and after a restore or reset. */
suspend fun PhotoStore.sweepUnused(db: MoneyBookDatabase) = withContext(Dispatchers.IO) {
    sweep(db.attachmentDao().liveFileNames().toSet())
}
