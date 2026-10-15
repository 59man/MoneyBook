package com.moneybook.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.shared.importer.LegacyBatch
import com.moneybook.shared.importer.LegacyRow
import com.moneybook.shared.importer.SkippedRow
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.CategoryType
import com.moneybook.shared.model.TxnType
import com.moneybook.testutil.FakeSettingsRepository
import com.moneybook.testutil.Fixtures
import com.moneybook.testutil.inMemoryDb
import com.moneybook.testutil.testPhotos
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyImportRepositoryTest {
    private lateinit var db: MoneyBookDatabase
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"), ZoneOffset.UTC))
    private val cash = DefaultData.accountId(AccountGroupType.CASH)
    private val food = DefaultData.categoryId(CategoryType.EXPENSE, 0)
    private val salary = DefaultData.categoryId(CategoryType.INCOME, 1)

    @Before fun setUp() {
        db = inMemoryDb()
        runBlocking {
            Seeder(db, time).seedIfEmpty("CZK")
            db.accountDao().upsert(db.accountDao().get(cash)!!.copy(initialBalance = 1_000_000))
            db.categoryDao().upsert(Fixtures.category("alk", name = "charita", sortOrder = 20))
            // The first entry of the Realbyte era.
            db.txnDao().upsert(Fixtures.txn("rb", TxnType.EXPENSE, "2023-03-01T09:15:00.000", cash, 10_000, categoryId = food))
        }
    }

    @After fun tearDown() = db.close()

    private fun row(n: Int, date: String, type: TxnType, category: String, amount: Long, memo: String = "") =
        LegacyRow(n, "k$n", LocalDate.parse(date), type, category, memo, amount)

    private val batch = LegacyBatch(
        rows = listOf(
            row(2, "2023-03-02", TxnType.EXPENSE, "Food", 30_000),
            row(3, "2023-03-01", TxnType.EXPENSE, "Food", 9_000),
            row(4, "2023-02-28", TxnType.EXPENSE, "Food", 600, memo = "nákup"),
            row(5, "2022-12-24", TxnType.EXPENSE, "Charity", 20_000),
            row(6, "2021-06-01", TxnType.EXPENSE, "Clothing", 150_000),
            row(7, "2020-01-15", TxnType.INCOME, "Salary", 2_000_000),
        ),
        skipped = listOf(SkippedRow(9, "Unreadable date")),
    )
    private val net = 2_000_000L - 600 - 20_000 - 150_000
    private val balances get() = AccountRepository(db, time)
    private fun repo() = LegacyImportRepository(db, time)

    private fun suggested(p: LegacyPreview) =
        p.categories.associate { it.key to (it.suggestionId?.let { id -> CategoryChoice.Existing(id) } ?: CategoryChoice.CreateNew) }

    @Test fun previewCutsAtTheFirstCoveredDayAndSuggestsCategories() = runTest {
        val p = repo().preview(batch)
        assertEquals(LocalDate.of(2023, 3, 1), p.cutoff)
        assertEquals(4, p.importable)
        assertEquals(2, p.overlapping)
        assertEquals(listOf(SkippedRow(9, "Unreadable date")), p.unreadable)
        val byName = p.categories.associateBy { it.key.name }
        assertEquals(1, byName.getValue("Food").count)
        assertEquals(food, byName.getValue("Food").suggestionId)
        assertEquals("alk", byName.getValue("Charity").suggestionId)
        assertNull(byName.getValue("Clothing").suggestionId)
        assertEquals(LegacyCategoryKey(CategoryType.INCOME, "Salary"), byName.getValue("Salary").key)
        assertEquals(salary, byName.getValue("Salary").suggestionId)
        assertEquals(CategoryType.INCOME, p.categories.last().key.type)
    }

    @Test fun importKeepsTheBalanceAndCreatesMissingCategories() = runTest {
        val before = balances.balanceOf(cash)
        val result = repo().apply(batch, cash, keepBalance = true, suggested(repo().preview(batch)))
        assertEquals(LegacyImportResult(imported = 4, duplicates = 0, overlapping = 2, categoriesCreated = 1, balanceAdjustment = -net), result)
        assertEquals(before, balances.balanceOf(cash))
        assertEquals(CategoryType.EXPENSE, db.categoryDao().all().single { it.name == "Clothing" }.type)
        val tip = db.txnDao().get(LegacyImportRepository.txnId("k4"))!!
        assertEquals("2023-02-28T12:00:00.000", tip.dateTime)
        assertEquals(food, tip.categoryId)
        assertEquals("nákup", tip.note)
        assertEquals(600L, tip.amount)
        assertEquals("CZK", tip.currency)
        assertNull(db.txnDao().get(LegacyImportRepository.txnId("k3")))
    }

    @Test fun reimportAddsNothing() = runTest {
        repo().apply(batch, cash, keepBalance = true, suggested(repo().preview(batch)))
        val balance = balances.balanceOf(cash)
        val again = repo().preview(batch)
        assertEquals(LocalDate.of(2023, 3, 1), again.cutoff)
        val result = repo().apply(batch, cash, keepBalance = true, suggested(again) + (LegacyCategoryKey(CategoryType.EXPENSE, "Clothing") to CategoryChoice.CreateNew))
        assertEquals(LegacyImportResult(imported = 0, duplicates = 4, overlapping = 2, categoriesCreated = 0, balanceAdjustment = 0), result)
        assertEquals(balance, balances.balanceOf(cash))
        assertEquals(1, db.categoryDao().all().count { it.name == "Clothing" })
    }

    @Test fun reimportAfterResetContentsBringsTheEntriesBack() = runTest {
        repo().apply(batch, cash, keepBalance = true, suggested(repo().preview(batch)))
        ResetRepository(db, FakeSettingsRepository(AppSettings("CZK")), Seeder(db, time), time, defaultCurrency = "CZK", photos = testPhotos()).resetContents()
        val again = repo().preview(batch)
        assertNull(again.cutoff) // the reset removed the Realbyte-era entry too
        val result = repo().apply(batch, cash, keepBalance = true, suggested(again))
        assertEquals(6, result.imported)
        assertEquals(0, result.duplicates)
        assertEquals(6, db.txnDao().count())
        assertEquals(false, db.txnDao().get(LegacyImportRepository.txnId("k4"))!!.deleted)
    }

    @Test fun withoutKeepBalanceTheEntriesMoveTheBalance() = runTest {
        val before = balances.balanceOf(cash)
        repo().apply(batch, cash, keepBalance = false, suggested(repo().preview(batch)))
        assertEquals(before + net, balances.balanceOf(cash))
    }
}
