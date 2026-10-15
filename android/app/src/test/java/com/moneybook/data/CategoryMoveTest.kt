package com.moneybook.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.CategoryType
import com.moneybook.shared.model.TxnType
import com.moneybook.testutil.Fixtures
import com.moneybook.testutil.inMemoryDb
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
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
class CategoryMoveTest {
    private lateinit var db: MoneyBookDatabase
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-10-06T10:00:00Z"), ZoneOffset.UTC))
    private val cash = DefaultData.accountId(AccountGroupType.CASH)
    private val food = DefaultData.categoryId(CategoryType.EXPENSE, 0)

    @Before fun setUp() {
        db = inMemoryDb()
        runBlocking {
            Seeder(db, time).seedIfEmpty("CZK")
            db.categoryDao().upsert(Fixtures.category("misc", name = "Misc"))
            db.categoryDao().upsert(Fixtures.category("misc-sub", name = "Sub", parentId = "misc"))
            db.txnDao().upsertAll(listOf(
                Fixtures.txn("a", TxnType.EXPENSE, "2026-09-01T12:00:00.000", cash, 100, categoryId = "misc").copy(updatedAt = 1, dirty = false),
                Fixtures.txn("b", TxnType.EXPENSE, "2026-09-02T12:00:00.000", cash, 200, categoryId = "misc-sub").copy(updatedAt = 1, dirty = false),
                Fixtures.txn("c", TxnType.EXPENSE, "2026-09-03T12:00:00.000", cash, 300, categoryId = food).copy(updatedAt = 1, dirty = false),
            ))
            BookmarkRepository(db, time).add(Fixtures.txn("t", TxnType.EXPENSE, "2026-09-04T12:00:00.000", cash, 400, categoryId = "misc"))
        }
    }

    @After fun tearDown() = db.close()

    @Test fun movingEntriesToAnotherCategoryThenDeletesTheOldOne() = runTest {
        CategoryRepository(db, time).moveAndDelete("misc", food)
        val rows = listOf("a", "b", "c").map { db.txnDao().get(it)!! }
        assertEquals(listOf(food, food, food), rows.map { it.categoryId })
        assertTrue(rows.take(2).all { it.dirty && it.updatedAt == time.millis() })
        assertEquals(1L, rows[2].updatedAt) // an entry already in the target is not touched
        assertNull(CategoryRepository(db, time).get("misc"))
        assertNull(CategoryRepository(db, time).get("misc-sub"))
        assertEquals(food, db.bookmarkDao().allRows().single().categoryId)
    }

    @Test fun movingEntriesAlsoMovesRepeats() = runTest {
        val entry = Fixtures.txn("r0", TxnType.EXPENSE, "2026-09-01T12:00:00.000", cash, 100, categoryId = "misc-sub")
        RepeatRepository(db, time).create("r", entry, com.moneybook.shared.model.RepeatFrequency.MONTHLY)
        CategoryRepository(db, time).moveAndDelete("misc", food)
        assertEquals(food, RepeatRepository(db, time).get("r")!!.categoryId)
    }
}
