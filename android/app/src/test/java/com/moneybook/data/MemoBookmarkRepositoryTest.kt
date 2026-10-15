package com.moneybook.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.db.MemoEntity
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.domain.DateRange
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.CategoryType
import com.moneybook.shared.model.TxnType
import com.moneybook.testutil.Fixtures
import com.moneybook.testutil.inMemoryDb
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
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
class MemoBookmarkRepositoryTest {
    private lateinit var db: MoneyBookDatabase
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneOffset.UTC))
    private val cash = DefaultData.accountId(AccountGroupType.CASH)
    private val food = DefaultData.categoryId(CategoryType.EXPENSE, 0)
    private val september = DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1))

    @Before fun setUp() {
        db = inMemoryDb()
        runBlocking { Seeder(db, time).seedIfEmpty("CZK") }
    }

    @After fun tearDown() = db.close()

    @Test fun memosOfAMonthPinnedFirstThenNewest() = runTest {
        val memos = MemoRepository(db, time)
        memos.save(MemoEntity(id = "old", date = "2026-09-02", title = "Old", updatedAt = 0))
        memos.save(MemoEntity(id = "new", date = "2026-09-20", title = "New", updatedAt = 0))
        memos.save(MemoEntity(id = "pin", date = "2026-09-01", title = "Pinned", pinned = true, updatedAt = 0))
        memos.save(MemoEntity(id = "oct", date = "2026-10-01", title = "October", updatedAt = 0))
        memos.save(MemoEntity(id = "gone", date = "2026-09-10", title = "Gone", updatedAt = 0))
        memos.delete("gone")
        assertEquals(listOf("pin", "new", "old"), memos.observeRange(september).first().map { it.id })
        assertNull(memos.get("gone"))
        assertTrue(db.memoDao().get("gone")!!.let { it.deleted && it.dirty })
    }

    @Test fun bookmarksCopyAnEntryAndListWithNames() = runTest {
        val bookmarks = BookmarkRepository(db, time)
        val lunch = Fixtures.txn("t1", TxnType.EXPENSE, "2026-09-26T12:00:00.000", cash, 17_400, categoryId = food, note = "Oběd")
        val unnamed = Fixtures.txn("t2", TxnType.EXPENSE, "2026-09-25T12:00:00.000", cash, 1_500, categoryId = food)
        val b1 = bookmarks.add(lunch)
        val b2 = bookmarks.add(unnamed)
        assertEquals("Oběd", b1.name)
        assertEquals("🍜 Food", b2.name) // no note: the category's name
        assertEquals(17_400L, bookmarks.get(b1.id)!!.amount)
        val rows = bookmarks.observeRows().first()
        assertEquals(setOf(b1.id, b2.id), rows.map { it.id }.toSet())
        val row = rows.first { it.id == b1.id }
        assertEquals("Cash", row.accountName)
        assertEquals("🍜 Food", row.asTxnRow().categoryName)
        bookmarks.delete(b2.id)
        assertEquals(listOf(b1.id), bookmarks.observeRows().first().map { it.id })
    }
}
