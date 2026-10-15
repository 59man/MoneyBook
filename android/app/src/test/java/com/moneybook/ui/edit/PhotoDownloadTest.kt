package com.moneybook.ui.edit

import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoDownloadTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun png(): File = tmp.newFile("x.jpg").also { f ->
        f.outputStream().use { Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun mimeTypeComesFromTheBytesNotTheJpgName() {
        assertEquals("image/png", photoMimeType(png()))
        assertEquals("image/jpeg", photoMimeType(tmp.newFile("junk.jpg").also { it.writeText("not an image") }))
    }

    @Test fun displayNameUsesTheMimesExtension() {
        assertEquals("MoneyBook_20261011_181700.png", photoDisplayName("image/png", 1_791_742_620_000L, java.time.ZoneOffset.UTC))
        assertEquals("MoneyBook_20261011_181700.jpg", photoDisplayName("image/jpeg", 1_791_742_620_000L, java.time.ZoneOffset.UTC))
    }

    @Test fun valuesPutThePhotoUnderPicturesMoneyBookAsPending() {
        val v = photoDownloadValues("MoneyBook_x.png", "image/png")
        assertEquals("MoneyBook_x.png", v.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
        assertEquals("image/png", v.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertEquals("Pictures/MoneyBook", v.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        assertEquals(1, v.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertNotNull(v)
    }
}
