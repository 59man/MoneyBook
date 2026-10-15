package com.moneybook.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.testutil.testPhotos
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.random.Random
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoStoreTest {
    private fun jpeg(w: Int, h: Int, noisy: Boolean, quality: Int = 100): ByteArray {
        val rnd = Random(7)
        // A gradient with mild noise: over 2 MiB at quality 100, but it fits once scaled to 2048 px.
        val px = IntArray(w * h) {
            val g = (it % w) * 255 / w
            if (noisy) 0xFF000000.toInt() or ((g + rnd.nextInt(24)).coerceAtMost(255) shl 16) or ((255 - g + rnd.nextInt(24)).coerceIn(0, 255) shl 8) or (it / w * 255 / h) else 0xFF3070B0.toInt()
        }
        val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
    }

    @Test fun `up to 2 MiB is kept as it is`() {
        assertEquals(PhotoPlan.Keep, photoPlan(4000, 3000, PHOTO_MAX_BYTES))
        assertEquals(PhotoPlan.Keep, photoPlan(10, 10, 1))
    }

    @Test fun `a larger landscape photo is scaled to 2048 on its long side`() {
        assertEquals(PhotoPlan.Scale(2048, 1536, 1), photoPlan(2048 + 0, 1536, PHOTO_MAX_BYTES + 1))
        assertEquals(PhotoPlan.Scale(2048, 1536, 1), photoPlan(3000, 2250, PHOTO_MAX_BYTES + 1))
        assertEquals(PhotoPlan.Scale(2048, 1152, 4), photoPlan(8192, 4608, PHOTO_MAX_BYTES + 1))
    }

    @Test fun `a larger portrait photo is scaled on its height and a smaller one is not enlarged`() {
        assertEquals(PhotoPlan.Scale(1536, 2048, 1), photoPlan(2250, 3000, PHOTO_MAX_BYTES + 1))
        assertEquals(PhotoPlan.Scale(1000, 800, 1), photoPlan(1000, 800, PHOTO_MAX_BYTES + 1))
    }

    @Test fun `a small image is stored unchanged under the md5 of its bytes`() = runTest {
        val store = testPhotos()
        val bytes = jpeg(64, 48, noisy = false)
        val name = store.import(ByteArrayInputStream(bytes))
        assertTrue(Regex("[0-9a-f]{32}\\.jpg").matches(name))
        assertArrayEquals(bytes, store.file(name).readBytes())
    }

    @Test fun `the same content is stored once`() = runTest {
        val store = testPhotos()
        val bytes = jpeg(64, 48, noisy = false)
        val a = store.import(ByteArrayInputStream(bytes))
        val stamp = store.file(a).lastModified()
        store.file(a).setLastModified(stamp - 100_000)
        assertEquals(a, store.import(ByteArrayInputStream(bytes)))
        assertEquals(stamp - 100_000, store.file(a).lastModified())
        assertEquals(1, store.dir.list()!!.size)
    }

    @Test fun `a big photo is scaled and re-encoded under 2 MiB`() = runTest {
        val store = testPhotos()
        val big = jpeg(3000, 2250, noisy = true)
        assertTrue("fixture must exceed 2 MiB", big.size > PHOTO_MAX_BYTES)
        val name = store.import(ByteArrayInputStream(big))
        val stored = store.file(name).readBytes()
        assertTrue("big ${big.size} stored ${stored.size}", stored.size <= PHOTO_MAX_BYTES)
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(stored, 0, stored.size, b)
        assertEquals(2048, b.outWidth)
        assertEquals(1536, b.outHeight)
    }

    @Test fun `something that is not an image is refused and leaves nothing behind`() = runTest {
        val store = testPhotos()
        assertFailsWith<IOException> { store.import(ByteArrayInputStream("not a picture".toByteArray())) }
        assertTrue(store.dir.list().orEmpty().isEmpty())
    }

    @Test fun `sweep removes what no live attachment uses and keeps the rest`() = runTest {
        val store = testPhotos()
        val a = store.import(ByteArrayInputStream(jpeg(8, 8, noisy = true)))
        val b = store.import(ByteArrayInputStream(jpeg(9, 9, noisy = true)))
        store.dir.resolve("left.tmp").writeText("x")
        store.dir.listFiles()!!.forEach { it.setLastModified(it.lastModified() - 2 * SWEEP_GRACE_MS) }
        store.sweep(setOf(a))
        assertTrue(store.file(a).exists())
        assertFalse(store.file(b).exists())
        assertEquals(listOf(a), store.dir.list()!!.toList())
    }

    @Test fun `sweep leaves files and half-written leftovers from the last ten minutes`() = runTest {
        val store = testPhotos()
        val fresh = store.import(ByteArrayInputStream(jpeg(8, 8, noisy = true)))
        store.dir.resolve("$fresh.tmp").writeText("x")
        store.sweep(emptySet())
        assertEquals(setOf(fresh, "$fresh.tmp"), store.dir.list()!!.toSet())
        store.sweep(emptySet(), now = System.currentTimeMillis() + 2 * SWEEP_GRACE_MS)
        assertTrue(store.dir.list()!!.isEmpty())
    }

    @Test fun `adopt moves the staged files in and throws when one cannot be moved`() {
        val store = testPhotos()
        val staging = store.newStaging().apply { mkdirs() }
        staging.resolve("a.jpg").writeText("a")
        store.adopt(staging)
        assertEquals("a", store.file("a.jpg").readText())
        assertFalse(staging.exists())

        // A folder that takes no new files: the rename fails and the restore must hear of it.
        val staging2 = store.newStaging().apply { mkdirs() }
        staging2.resolve("b.jpg").writeText("b")
        store.dir.setWritable(false)
        try {
            assertFailsWith<IOException> { store.adopt(staging2) }
        } finally {
            store.dir.setWritable(true)
        }
        assertFalse(staging2.exists())
    }

    private fun withOrientation(bytes: ByteArray, orientation: Int): ByteArray {
        val f = java.io.File.createTempFile("exif", ".jpg").apply { writeBytes(bytes) }
        android.media.ExifInterface(f.path).apply { setAttribute(android.media.ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes() }
        return f.readBytes().also { f.delete() }
    }

    @Test fun `a re-encode keeps every EXIF orientation, mirrored ones included`() = runTest {
        val big = jpeg(3000, 2250, noisy = true)
        suspend fun stored(o: Int): Bitmap {
            val bytes = testPhotos().let { st -> st.file(st.import(ByteArrayInputStream(withOrientation(big, o)))).readBytes() }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        // The fixture's red channel grows from left to right. Orientation 2 mirrors it, so the left edge ends up red.
        val flipped = stored(2)
        assertEquals(2048 to 1536, flipped.width to flipped.height)
        assertTrue(android.graphics.Color.red(flipped.getPixel(5, 768)) > 200)
        // 5 (transpose) and 6 (rotate 90) swap the sides.
        assertEquals(1536 to 2048, stored(5).let { it.width to it.height })
        assertEquals(1536 to 2048, stored(6).let { it.width to it.height })
    }
}
