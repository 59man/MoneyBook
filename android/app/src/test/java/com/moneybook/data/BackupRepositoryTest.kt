package com.moneybook.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.data.db.TxnEntity
import com.moneybook.shared.backup.BackupException
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.ArchiveState
import com.moneybook.shared.model.CategoryType
import com.moneybook.shared.model.TxnType
import com.moneybook.testutil.FakeSettingsRepository
import com.moneybook.testutil.Fixtures
import com.moneybook.testutil.inMemoryDb
import com.moneybook.testutil.testPhotos
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupRepositoryTest {
    private lateinit var db: MoneyBookDatabase
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"), ZoneOffset.UTC))
    private val settings = FakeSettingsRepository(AppSettings("CZK", monthStartDay = 5, weekStart = DayOfWeek.MONDAY))
    private val cash = DefaultData.accountId(AccountGroupType.CASH)
    private val food = DefaultData.categoryId(CategoryType.EXPENSE, 0)

    @Before fun setUp() {
        db = inMemoryDb()
        runBlocking {
            Seeder(db, time).seedIfEmpty("CZK")
            db.txnDao().upsertAll(listOf(
                Fixtures.txn("t1", TxnType.EXPENSE, "2026-09-26T12:00:00.000", cash, 52_500, categoryId = food, note = "Zelenina"),
                Fixtures.txn("t2", TxnType.INCOME, "2026-09-01T10:00:00.000", cash, 5_230_000),
                Fixtures.txn("gone", TxnType.EXPENSE, "2026-09-02T10:00:00.000", cash, 100).copy(deleted = true),
            ))
        }
    }

    @After fun tearDown() = db.close()

    private fun repo(target: MoneyBookDatabase = db, s: SettingsRepository = settings) = BackupRepository(target, s, time, "0.2.0", testPhotos())

    private fun List<TxnEntity>.plain() = map { it.copy(dirty = false, serverVersion = 0, updatedAt = 0) }.sortedBy { it.id }

    @Test fun backupThenRestoreIntoAnEmptyDatabaseKeepsEveryRowAndSetting() = runTest {
        val out = ByteArrayOutputStream()
        assertEquals(2, repo().backup(out))
        val other = inMemoryDb()
        val otherSettings = FakeSettingsRepository()
        try {
            val result = repo(other, otherSettings).restore(ByteArrayInputStream(out.toByteArray()))
            assertEquals(RestoreResult(entries = 2, createdAt = "2026-09-28T10:00"), result)
            assertEquals(db.txnDao().allRows().plain(), other.txnDao().allRows().plain())
            assertEquals(
                db.accountDao().allAccountRows().map { it.copy(dirty = false, updatedAt = 0) }.sortedBy { it.id },
                other.accountDao().allAccountRows().map { it.copy(dirty = false, updatedAt = 0) }.sortedBy { it.id },
            )
            assertEquals(db.categoryDao().allRows().size, other.categoryDao().allRows().size)
            assertEquals(settings.current(), otherSettings.current())
            assertTrue(other.txnDao().allRows().all { it.dirty && it.serverVersion == 0L })
        } finally {
            other.close()
        }
    }

    @Test fun restoreReplacesCurrentData() = runTest {
        val out = ByteArrayOutputStream()
        repo().backup(out)
        db.txnDao().upsert(Fixtures.txn("new", TxnType.EXPENSE, "2026-09-27T09:00:00.000", cash, 1_000))
        repo().restore(ByteArrayInputStream(out.toByteArray()))
        assertTrue(db.txnDao().get("new")!!.deleted)
        assertEquals(2, db.txnDao().count())
    }

    @Test fun restoreTombstonesRowsTheBackupLacks() = runTest {
        val out = ByteArrayOutputStream()
        repo().backup(out)
        db.txnDao().upsert(Fixtures.txn("new", TxnType.EXPENSE, "2026-09-27T09:00:00.000", cash, 1_000).copy(updatedAt = 5))
        db.categoryDao().upsert(Fixtures.category("extra"))
        repo().restore(ByteArrayInputStream(out.toByteArray()))
        val gone = db.txnDao().get("new")!!
        assertTrue(gone.deleted && gone.dirty)
        assertTrue(gone.updatedAt >= time.millis())
        assertTrue(db.categoryDao().get("extra")!!.deleted)
    }

    @Test fun restoredRowsAreStampedNowAndDirty() = runTest {
        val out = ByteArrayOutputStream()
        repo().backup(out)
        db.txnDao().applyRemoteAll(db.txnDao().allRows().map { it.copy(updatedAt = 50, dirty = false) })
        repo().restore(ByteArrayInputStream(out.toByteArray()))
        val rows = db.txnDao().allRows()
        assertTrue(rows.all { it.dirty && it.updatedAt >= time.millis() })
        assertEquals(setOf("t1", "t2"), rows.filter { !it.deleted }.map { it.id }.toSet())
    }

    @Test fun restoreOfTombstonesKeepsThemTombstones() = runTest {
        val out = ByteArrayOutputStream()
        repo().backup(out)
        repo().restore(ByteArrayInputStream(out.toByteArray()))
        val gone = db.txnDao().get("gone")!!
        assertTrue(gone.deleted && gone.dirty)
        assertEquals(2, db.txnDao().count())
    }

    @Test fun restoreMarksSettingsDirty() = runTest {
        val out = ByteArrayOutputStream()
        repo().backup(out)
        val other = FakeSettingsRepository()
        assertEquals(0L, other.sync.value.updatedAt)
        repo(db, other).restore(ByteArrayInputStream(out.toByteArray()))
        assertEquals(5, other.current().monthStartDay)
        assertTrue(other.sync.value.dirty && other.sync.value.updatedAt > 0)
    }

    @Test fun damagedBackupLeavesDataUntouched() = runTest {
        val before = db.txnDao().allRows()
        assertFailsWith<BackupException> { repo().restore(ByteArrayInputStream("not a zip".toByteArray())) }
        assertEquals(before, db.txnDao().allRows())
        assertEquals(AppSettings("CZK", monthStartDay = 5, weekStart = DayOfWeek.MONDAY), settings.current())
    }

    @Test fun budgetsSurviveBackupAndRestore() = runTest {
        val budgets = BudgetRepository(db, time)
        budgets.add(null, 30_000, 202609)
        budgets.setMonth(null, 202610, 25_000)
        val before = db.budgetDao().allRows().map { it.copy(dirty = false, updatedAt = 0) }.sortedBy { it.id }
        val out = ByteArrayOutputStream()
        repo().backup(out)
        budgets.delete(null)
        repo().restore(ByteArrayInputStream(out.toByteArray()))
        assertEquals(before, db.budgetDao().allRows().map { it.copy(dirty = false, updatedAt = 0) }.sortedBy { it.id })
        assertTrue(db.budgetDao().allRows().all { it.dirty })
    }

    @Test fun accountAndGroupStatesAndTheTransferExpenseFlagSurviveBackupAndRestore() = runTest {
        val accounts = AccountRepository(db, time)
        val bank = DefaultData.accountId(AccountGroupType.ACCOUNTS)
        accounts.delete(cash)
        accounts.purge(bank)
        accounts.setTransferExpense(bank, true)
        accounts.deleteGroup(DefaultData.groupId(AccountGroupType.SAVINGS))
        val before = db.accountDao().allAccountRows().map { it.copy(dirty = false, updatedAt = 0) }.sortedBy { it.id }
        val groupsBefore = db.accountDao().allGroupRows().map { it.copy(dirty = false, updatedAt = 0) }.sortedBy { it.id }
        val out = ByteArrayOutputStream()
        repo().backup(out)
        accounts.restore(cash)
        accounts.restoreGroup(DefaultData.groupId(AccountGroupType.SAVINGS))
        repo().restore(ByteArrayInputStream(out.toByteArray()))
        assertEquals(before, db.accountDao().allAccountRows().map { it.copy(dirty = false, updatedAt = 0) }.sortedBy { it.id })
        assertEquals(groupsBefore, db.accountDao().allGroupRows().map { it.copy(dirty = false, updatedAt = 0) }.sortedBy { it.id })
        assertEquals(ArchiveState.PURGED, db.accountDao().get(bank)!!.state)
        assertTrue(db.accountDao().get(bank)!!.transferExpense)
    }

    @Test fun aVersion5BackupRestoresEveryAccountAndGroupAsActive() = runTest {
        val v5 = """{"format":"moneybook-backup","version":5,"createdAt":"2026-09-28T10:00","appVersion":"0.2.0","settings":{"mainCurrency":"CZK"},
            |"accountGroups":[{"id":"g","name":"Cash","type":"CASH","sortOrder":0,"updatedAt":1}],
            |"accounts":[{"id":"a","groupId":"g","name":"Cash","currency":"CZK","initialBalance":0,"sortOrder":0,"updatedAt":1}],
            |"categories":[],"txns":[]}""".trimMargin()
        val zip = ByteArrayOutputStream().also { o ->
            java.util.zip.ZipOutputStream(o).use { z ->
                z.putNextEntry(java.util.zip.ZipEntry("backup.json")); z.write(v5.toByteArray()); z.closeEntry()
            }
        }
        repo().restore(ByteArrayInputStream(zip.toByteArray()))
        val a = db.accountDao().get("a")!!
        assertEquals(ArchiveState.ACTIVE, a.state)
        assertEquals(false, a.transferExpense)
        assertEquals(ArchiveState.ACTIVE, db.accountDao().getGroup("g")!!.state)
        assertEquals(false, settings.current().carryOver)
        assertEquals(false, settings.current().subcategory)
    }
}
