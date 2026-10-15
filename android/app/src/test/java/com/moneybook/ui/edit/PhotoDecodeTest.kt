package com.moneybook.ui.edit

import com.moneybook.data.ExifTurn
import com.moneybook.data.exifTurn
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoDecodeTest {
    @Test fun exifOrientationsAreMirrorThenRotate() {
        // 1..8 as in the EXIF spec; the mirror is a horizontal flip applied before the clockwise turn.
        assertEquals(ExifTurn(0, false), exifTurn(1))
        assertEquals(ExifTurn(0, true), exifTurn(2))
        assertEquals(ExifTurn(180, false), exifTurn(3))
        assertEquals(ExifTurn(180, true), exifTurn(4))
        assertEquals(ExifTurn(270, true), exifTurn(5))
        assertEquals(ExifTurn(90, false), exifTurn(6))
        assertEquals(ExifTurn(90, true), exifTurn(7))
        assertEquals(ExifTurn(270, false), exifTurn(8))
        assertEquals(ExifTurn(0, false), exifTurn(0)) // undefined or unknown: as it is
        assertEquals(ExifTurn(0, false), exifTurn(99))
    }

    @Test fun sampleSizeKeepsTheLongSideAtOrAboveMax() {
        assertEquals(1, photoSampleSize(800, 600, 1024))
        assertEquals(1, photoSampleSize(2047, 100, 1024))
        assertEquals(2, photoSampleSize(2048, 100, 1024))
        assertEquals(4, photoSampleSize(3000, 4000, 1000))
        assertEquals(1, photoSampleSize(0, 0, 1024))
    }

    private fun jpeg(dir: File, name: String, w: Int, h: Int): File {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        return File(dir, name).also { f -> f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
    }

    @Test fun decodeSamplesAndAppliesTheExifTurnByContent() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val plain = jpeg(dir, "a.jpg", 400, 200)
        assertEquals(400 to 200, decodePhoto(plain, 1024)!!.let { it.width to it.height })
        // Orientation 6 (rotate 90): the stored 400x200 is shown 200x400.
        val rotated = jpeg(dir, "b.jpg", 400, 200)
        android.media.ExifInterface(rotated.path).apply {
            setAttribute(android.media.ExifInterface.TAG_ORIENTATION, "6"); saveAttributes()
        }
        assertEquals(200 to 400, decodePhoto(rotated, 1024)!!.let { it.width to it.height })
        // A big one is sampled down but not below the max.
        val big = decodePhoto(jpeg(dir, "c.jpg", 4000, 1000), 1000)!!
        assertEquals(1000 to 250, big.width to big.height)
    }

    @Test fun aPngNamedJpgDecodesAndGarbageIsNull() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val png = File(dir, "p.jpg")
        png.outputStream().use { Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertNotNull(decodePhoto(png, 1024))
        val junk = File(dir, "j.jpg").apply { writeText("not an image") }
        assertNull(decodePhoto(junk, 1024))
        assertNull(decodePhoto(File(dir, "missing.jpg"), 1024))
    }
}
