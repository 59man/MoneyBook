package com.moneybook.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.db.DbTime
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.shared.model.CategoryType
import com.moneybook.shared.model.TxnType.EXPENSE
import com.moneybook.shared.model.TxnType.TRANSFER
import com.moneybook.testutil.Fixtures
import com.moneybook.testutil.inMemoryDb
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RepositoriesTest {
    private lateinit var db: MoneyBookDatabase
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneOffset.UTC))

    @Before fun setUp() { db = inMemoryDb() }
    @After fun tearDown() = db.close()

    @Test fun seederCreatesRealbyteDefaultsOnceWithDeterministicIds() = runTest {
        val seeder = Seeder(db, time)
        seeder.seedIfEmpty("CZK")
        seeder.seedIfEmpty("CZK")
        val groups = db.accountDao().groups()
        assertEquals(11, groups.size)
        assertEquals("Cash", groups.first().name)
        assertEquals(DefaultData.groupId(groups.first().type), groups.first().id)
        assertEquals(listOf("Cash", "Accounts", "Card"), db.accountDao().accounts().map { it.name })
        assertEquals("CZK", db.accountDao().accounts().first().currency)
        val expense = CategoryRepository(db, time).top(CategoryType.EXPENSE)
        assertEquals(DefaultData.expenseCategories, expense.map { it.name })
        assertEquals(DefaultData.incomeCategories, CategoryRepository(db, time).top(CategoryType.INCOME).map { it.name })
    }

    @Test fun firstStartSeedsHaveUpdatedAtZero() = runTest {
        Seeder(db, time).seedIfEmpty("CZK", stamp = 0)
        val rows = db.accountDao().allGroupRows().map { it.updatedAt } + db.accountDao().allAccountRows().map { it.updatedAt } +
            db.categoryDao().allRows().map { it.updatedAt }
        assertTrue(rows.isNotEmpty() && rows.all { it == 0L })
        assertTrue(db.categoryDao().allRows().all { it.dirty })
    }

    @Test fun seedWithoutStampUsesNow() = runTest {
        Seeder(db, time).seedIfEmpty("CZK")
        assertEquals(setOf(time.millis()), db.categoryDao().allRows().map { it.updatedAt }.toSet())
        assertEquals(setOf(time.millis()), db.accountDao().allGroupRows().map { it.updatedAt }.toSet())
    }

    @Test fun txnSaveStampsUpdatedAtAndDirtyAndDeleteTombstones() = runTest {
        Seeder(db, time).seedIfEmpty("CZK")
        val repo = TxnRepository(db, time)
        val cash = db.accountDao().accounts().first()
        repo.save(Fixtures.txn("t1", EXPENSE, "2026-09-26T09:00:00.000", cash.id, 52_500).copy(dirty = false))
        val saved = repo.get("t1")!!
        assertEquals(time.millis(), saved.updatedAt)
        assertTrue(saved.dirty)
        repo.delete("t1")
        assertTrue(repo.get("t1")!!.deleted)
    }

    @Test fun rangeUsesDbTimeKeys() = runTest {
        Seeder(db, time).seedIfEmpty("CZK")
        val cash = db.accountDao().accounts().first()
        val repo = TxnRepository(db, time)
        repo.save(Fixtures.txn("a", EXPENSE, DbTime.format(java.time.LocalDateTime.of(2026, 9, 30, 23, 0)), cash.id, 1))
        val range = com.moneybook.domain.Periods.month(java.time.YearMonth.of(2026, 9), 1)
        assertEquals(listOf("a"), repo.observeRange(range).first().map { it.id })
    }
}
