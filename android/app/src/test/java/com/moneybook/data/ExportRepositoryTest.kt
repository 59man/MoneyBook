package com.moneybook.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.shared.importer.RealbyteExcelImporter
import com.moneybook.shared.importer.SkippedRow
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.CategoryType
import com.moneybook.shared.model.TxnType
import com.moneybook.testutil.FakeSettingsRepository
import com.moneybook.testutil.Fixtures
import com.moneybook.testutil.inMemoryDb
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExportRepositoryTest {
    private lateinit var db: MoneyBookDatabase
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"), ZoneOffset.UTC))
    private val cash = DefaultData.accountId(AccountGroupType.CASH)
    private val bank = DefaultData.accountId(AccountGroupType.ACCOUNTS)
    private val food = DefaultData.categoryId(CategoryType.EXPENSE, 0)

    @Before fun setUp() {
        db = inMemoryDb()
        runBlocking {
            Seeder(db, time).seedIfEmpty("CZK")
            db.categoryDao().upsert(Fixtures.category("lunch", name = "Lunch", parentId = food))
            db.txnDao().upsertAll(listOf(
                Fixtures.txn("e", TxnType.EXPENSE, "2026-09-26T18:40:12.345", cash, 52_500, categoryId = "lunch", note = "Zelenina z trhu"),
                Fixtures.txn("t", TxnType.TRANSFER, "2026-09-25T08:00:00.000", cash, 100_000, toAccountId = bank),
                Fixtures.txn("gone", TxnType.EXPENSE, "2026-09-24T08:00:00.000", cash, 5_00, categoryId = food).copy(deleted = true),
            ))
        }
    }

    @After fun tearDown() = db.close()

    @Test fun exportsLiveEntriesInRealbyteLayout() = runTest {
        val out = ByteArrayOutputStream()
        assertEquals(2, ExportRepository(db, FakeSettingsRepository(AppSettings(mainCurrency = "CZK", subcategory = true))).exportExcel(out))
        val batch = RealbyteExcelImporter.parse(ByteArrayInputStream(out.toByteArray()))
        assertEquals(emptyList<SkippedRow>(), batch.skipped)
        assertEquals(2, batch.txns.size)
        val lunch = batch.txns.single { it.type == TxnType.EXPENSE }
        assertEquals("🍜 Food", lunch.categoryName)
        assertEquals("Lunch", lunch.subcategoryName)
        assertEquals("Zelenina z trhu", lunch.note)
        assertEquals(52_500L, lunch.amountMinor)
        val transfer = batch.txns.single { it.type == TxnType.TRANSFER }
        assertEquals("Cash", transfer.accountName)
        assertEquals("Accounts", transfer.toAccountName)
    }

    @Test fun withSubcategoryOffTheColumnIsEmptyAndCategoryIsTheParent() = runTest {
        val out = ByteArrayOutputStream()
        ExportRepository(db, FakeSettingsRepository()).exportExcel(out)
        val lunch = RealbyteExcelImporter.parse(ByteArrayInputStream(out.toByteArray())).txns.single { it.type == TxnType.EXPENSE }
        assertEquals("🍜 Food", lunch.categoryName)
        assertEquals(null, lunch.subcategoryName)
    }
}
