package com.moneybook.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.shared.backup.BackupException
import com.moneybook.shared.model.TxnType
import com.moneybook.testutil.FakeSettingsRepository
import com.moneybook.testutil.Fixtures
import com.moneybook.testutil.inMemoryDb
import com.moneybook.testutil.testPhotos
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AttachmentRepositoryTest {
    private lateinit var db: MoneyBookDatabase
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"), ZoneOffset.UTC))
    private val photos = testPhotos()
    private val settings = FakeSettingsRepository(AppSettings("CZK"))
    private val cash = DefaultData.accountId(com.moneybook.shared.model.AccountGroupType.CASH)
    private val a = "a".repeat(32) + ".jpg"
    private val b = "b".repeat(32) + ".jpg"
    private val c = "c".repeat(32) + ".jpg"
    private lateinit var txns: TxnRepository

    @Before fun setUp() {
        db = inMemoryDb()
        txns = TxnRepository(db, time)
    }

    @After fun tearDown() = db.close()

    private fun entry(id: String) = Fixtures.txn(id, TxnType.EXPENSE, "2026-09-26T12:00:00.000", cash, 100)

    private suspend fun seed() = Seeder(db, time).seedIfEmpty("CZK")

    private fun put(name: String, text: String = name) {
        photos.dir.mkdirs()
        photos.file(name).writeText(text)
        photos.file(name).setLastModified(System.currentTimeMillis() - 2 * SWEEP_GRACE_MS) // past the sweep's grace period
    }

    @Test fun `saving an entry writes its photos in order, a re-save replaces them and a removed photo comes back`() = runTest {
        seed()
        txns.save(entry("t"), photos = listOf(a, b))
        assertEquals(listOf(a, b), txns.photos("t"))
        txns.save(entry("t"), photos = listOf(b))
        assertEquals(listOf(b), txns.photos("t"))
        assertTrue(db.attachmentDao().allRows().single { it.fileName == a }.deleted)
        txns.save(entry("t"), photos = listOf(a, b))
        assertEquals(listOf(a, b), txns.photos("t"))
        assertEquals(2, db.attachmentDao().allRows().size)
    }

    @Test fun `a save without photos leaves them as they are and an unchanged list writes nothing`() = runTest {
        seed()
        txns.save(entry("t"), photos = listOf(a))
        db.attachmentDao().allRows().forEach { db.attachmentDao().applyRemoteAll(listOf(it.copy(dirty = false, updatedAt = 5))) }
        txns.save(entry("t"))
        txns.save(entry("t"), photos = listOf(a))
        assertEquals(listOf(a), txns.photos("t"))
        assertFalse(db.attachmentDao().allRows().single().dirty)
    }

    @Test fun `deleting an entry tombstones its photos`() = runTest {
        seed()
        txns.save(entry("t"), photos = listOf(a))
        txns.save(entry("u"), photos = listOf(b))
        txns.delete("t")
        assertEquals(emptyList<String>(), txns.photos("t"))
        assertEquals(listOf(b), txns.photos("u"))
        assertTrue(db.attachmentDao().allRows().single { it.txnId == "t" }.let { it.deleted && it.dirty })
    }

    @Test fun `hasPhoto is true only while a live photo exists`() = runTest {
        seed()
        txns.save(entry("t"), photos = listOf(a))
        txns.save(entry("u"))
        fun rows() = txns.observeAll()
        assertEquals(mapOf("t" to true, "u" to false), rows().first().associate { it.id to it.hasPhoto })
        txns.save(entry("t"), photos = emptyList())
        assertFalse(rows().first().single { it.id == "t" }.hasPhoto)
    }

    @Test fun `the sweep keeps a file two entries share until both let go`() = runTest {
        seed()
        listOf(a, b, c).forEach { put(it) }
        txns.save(entry("t"), photos = listOf(a, b))
        txns.save(entry("u"), photos = listOf(b))
        photos.sweepUnused(db)
        assertEquals(setOf(a, b), photos.dir.list()!!.toSet())
        txns.delete("t")
        photos.sweepUnused(db)
        assertEquals(setOf(b), photos.dir.list()!!.toSet())
        txns.delete("u")
        photos.sweepUnused(db)
        assertEquals(0, photos.dir.list()!!.size)
    }

    @Test fun `both resets tombstone the photos and sweep their files`() = runTest {
        seed()
        put(a); put(b)
        txns.save(entry("t"), photos = listOf(a))
        val reset = ResetRepository(db, settings, Seeder(db, time), time, defaultCurrency = "CZK", photos = photos)
        reset.resetContents()
        assertTrue(db.attachmentDao().allRows().all { it.deleted })
        assertEquals(0, photos.dir.list()!!.size)
        txns.save(entry("u"), photos = listOf(b))
        put(b)
        reset.resetAll()
        assertEquals(0, db.attachmentDao().liveFileNames().size)
        assertEquals(0, photos.dir.list()!!.size)
    }

    @Test fun `a version 7 backup carries two photos through a restore into an empty app`() = runTest {
        seed()
        put(a, "first"); put(b, "second"); put(c, "stray")
        txns.save(entry("t"), photos = listOf(a, b))
        val out = ByteArrayOutputStream()
        BackupRepository(db, settings, time, "0.2.0", photos).backup(out)

        val other = inMemoryDb()
        val otherPhotos = testPhotos()
        otherPhotos.dir.mkdirs()
        otherPhotos.file(c).writeText("old")
        otherPhotos.file(c).setLastModified(System.currentTimeMillis() - 2 * SWEEP_GRACE_MS)
        try {
            BackupRepository(other, FakeSettingsRepository(), time, "0.2.0", otherPhotos).restore(ByteArrayInputStream(out.toByteArray()))
            assertEquals(listOf(a, b), other.attachmentDao().forTxn("t").map { it.fileName })
            assertTrue(other.attachmentDao().allRows().all { it.dirty })
            assertEquals("first", otherPhotos.file(a).readText())
            assertEquals("second", otherPhotos.file(b).readText())
            assertEquals(setOf(a, b), otherPhotos.dir.list()!!.toSet())
            assertFalse(otherPhotos.newStaging().exists())
        } finally {
            other.close()
        }
    }

    @Test fun `a live photo whose file is gone is left out of the backup so the backup still restores`() = runTest {
        seed()
        put(a)
        txns.save(entry("t"), photos = listOf(a, b))
        val out = ByteArrayOutputStream()
        BackupRepository(db, settings, time, "0.2.0", photos).backup(out)
        BackupRepository(db, settings, time, "0.2.0", photos).restore(ByteArrayInputStream(out.toByteArray()))
        assertEquals(listOf(a), txns.photos("t"))
    }

    @Test fun `a damaged restore keeps the current photos`() = runTest {
        seed()
        put(a)
        txns.save(entry("t"), photos = listOf(a))
        assertFailsWith<BackupException> { BackupRepository(db, settings, time, "0.2.0", photos).restore(ByteArrayInputStream("nope".toByteArray())) }
        assertEquals(listOf(a), txns.photos("t"))
        assertTrue(photos.file(a).exists())
        assertFalse(photos.newStaging().exists())
    }

    @Test fun `a version 6 file restores with no photos and the copy choice survives a version 7 round trip`() = runTest {
        seed()
        settings.replace(AppSettings("CZK", copyDateBy = CopyDateBy.ENTRY_DATE))
        val out = ByteArrayOutputStream()
        BackupRepository(db, settings, time, "0.2.0", photos).backup(out)
        settings.replace(AppSettings("CZK"))
        BackupRepository(db, settings, time, "0.2.0", photos).restore(ByteArrayInputStream(out.toByteArray()))
        assertEquals(CopyDateBy.ENTRY_DATE, settings.current().copyDateBy)

        val v6 = """{"format":"moneybook-backup","version":6,"createdAt":"x","appVersion":"x","settings":{"mainCurrency":"CZK"},
            |"accountGroups":[{"id":"g","name":"Cash","type":"CASH","sortOrder":0,"updatedAt":1}],
            |"accounts":[{"id":"a","groupId":"g","name":"Cash","currency":"CZK","initialBalance":0,"sortOrder":0,"updatedAt":1}],
            |"categories":[],"txns":[]}""".trimMargin()
        val zip = ByteArrayOutputStream().also { o ->
            java.util.zip.ZipOutputStream(o).use { z ->
                z.putNextEntry(java.util.zip.ZipEntry("backup.json")); z.write(v6.toByteArray()); z.closeEntry()
            }
        }.toByteArray()
        put(c)
        BackupRepository(db, settings, time, "0.2.0", photos).restore(ByteArrayInputStream(zip))
        assertEquals(0, db.attachmentDao().allRows().size)
        assertEquals(0, photos.dir.list()!!.size)
        assertEquals(CopyDateBy.TODAY, settings.current().copyDateBy)
    }
}
