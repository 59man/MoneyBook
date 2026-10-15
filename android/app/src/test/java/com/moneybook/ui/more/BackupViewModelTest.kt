package com.moneybook.ui.more

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.BackupRepository
import com.moneybook.data.DefaultData
import com.moneybook.data.ExportRepository
import com.moneybook.data.ResetRepository
import com.moneybook.data.Seeder
import com.moneybook.data.TimeSource
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.TxnType
import com.moneybook.testutil.FakeSettingsRepository
import com.moneybook.testutil.Fixtures
import com.moneybook.testutil.MainDispatcherRule
import com.moneybook.testutil.inMemoryDb
import com.moneybook.testutil.testPhotos
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private lateinit var db: MoneyBookDatabase
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"), ZoneOffset.UTC))
    private val settings = FakeSettingsRepository()
    private val cash = DefaultData.accountId(AccountGroupType.CASH)

    @Before fun setUp() {
        db = inMemoryDb()
        runBlocking {
            Seeder(db, time).seedIfEmpty("CZK")
            db.txnDao().upsert(Fixtures.txn("t1", TxnType.EXPENSE, "2026-09-26T12:00:00.000", cash, 52_500))
        }
    }

    @After fun tearDown() = db.close()

    private fun vm() = BackupViewModel(
        BackupRepository(db, settings, time, "0.2.0", testPhotos()),
        ExportRepository(db, settings),
        ResetRepository(db, settings, Seeder(db, time), time, "CZK", testPhotos()),
        time,
    )

    private suspend fun BackupViewModel.finished() = state.first { it is BackupState.Finished } as BackupState.Finished

    @Test fun fileNamesCarryTheDate() {
        assertEquals("MoneyBook-backup-2026-09-28.zip", vm().backupFileName())
        assertEquals("MoneyBook-2026-09-28.xlsx", vm().excelFileName())
    }

    @Test fun backupThenRestoreReportsWhatHappened() = runTest {
        val vm = vm()
        val out = ByteArrayOutputStream()
        vm.backup { out }
        assertEquals(BackupState.Finished("Backup complete", "Saved 1 entry."), vm.finished())
        vm.dismiss()
        vm.restore { ByteArrayInputStream(out.toByteArray()) }
        assertEquals(BackupState.Finished("Restore complete", "Restored 1 entry from the backup of 2026-09-28."), vm.finished())
    }

    @Test fun unreadableRestoreIsReported() = runTest {
        val vm = vm()
        vm.restore { ByteArrayInputStream("hello".toByteArray()) }
        assertEquals(BackupState.Finished("Restore failed", "This is not a MoneyBook backup."), vm.finished())
        assertEquals(1, db.txnDao().count())
    }

    @Test fun resetContentsDeletesEntries() = runTest {
        val vm = vm()
        vm.resetContents()
        assertEquals(BackupState.Finished("Reset complete", "All entries were deleted."), vm.finished())
        assertEquals(0, db.txnDao().count())
    }
}
