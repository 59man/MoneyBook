package com.moneybook.data

import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CategoryRepositoryTest {
    private lateinit var db: MoneyBookDatabase
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"), ZoneOffset.UTC))
    private val cash = DefaultData.accountId(AccountGroupType.CASH)
    private val food = DefaultData.categoryId(CategoryType.EXPENSE, 0)

    @Before fun setUp() {
        db = inMemoryDb()
        runBlocking {
            Seeder(db, time).seedIfEmpty("CZK")
            db.categoryDao().upsert(Fixtures.category("lunch", name = "Lunch", parentId = food))
            db.txnDao().upsertAll(listOf(
                Fixtures.txn("a", TxnType.EXPENSE, "2026-09-26T12:00:00.000", cash, 52_500, categoryId = food),
                Fixtures.txn("b", TxnType.EXPENSE, "2026-09-25T12:00:00.000", cash, 18_600, categoryId = "lunch"),
            ))
        }
    }

    @After fun tearDown() = db.close()

    private fun repo() = CategoryRepository(db, time)

    @Test fun addAppendsATrimmedCategoryAtTheEnd() = runTest {
        val sport = repo().add(CategoryType.EXPENSE, "  Sport ")
        assertEquals("Sport", sport.name)
        assertEquals(sport.id, repo().observeTop(CategoryType.EXPENSE).first().last().id)
    }

    @Test fun renameTrims() = runTest {
        repo().rename(food, " Jídlo ")
        assertEquals("Jídlo", repo().get(food)!!.name)
    }

    @Test fun deleteTombstonesChildrenAndKeepsEntryLabels() = runTest {
        repo().delete(food)
        assertTrue(repo().observeTop(CategoryType.EXPENSE).first().none { it.id == food })
        assertTrue(db.categoryDao().allRows().single { it.id == "lunch" }.deleted)
        val rows = TxnRepository(db, time).observeRange(DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1))).first()
        assertEquals(setOf("🍜 Food", "Lunch"), rows.map { it.categoryName }.toSet())
    }

    @Test fun reorderWritesTheNewPositions() = runTest {
        val ids = repo().observeTop(CategoryType.EXPENSE).first().map { it.id }
        repo().reorder(ids.reversed())
        assertEquals(ids.reversed(), repo().observeTop(CategoryType.EXPENSE).first().map { it.id })
    }
}
