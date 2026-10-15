package com.moneybook.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.shared.model.CategoryType
import com.moneybook.shared.model.TxnType
import com.moneybook.testutil.Fixtures
import com.moneybook.testutil.inMemoryDb
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CategorySubcategoryTest {
    private lateinit var db: MoneyBookDatabase
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-10-10T10:00:00Z"), ZoneOffset.UTC))
    private lateinit var repo: CategoryRepository

    @Before fun setUp() {
        db = inMemoryDb()
        repo = CategoryRepository(db, time)
        runBlocking {
            val dao = db.categoryDao()
            dao.upsert(Fixtures.category("food", name = "Food", sortOrder = 0))
            dao.upsert(Fixtures.category("lunch", name = "Lunch", parentId = "food", sortOrder = 0))
            dao.upsert(Fixtures.category("dinner", name = "Dinner", parentId = "food", sortOrder = 1))
            dao.upsert(Fixtures.category("pets", name = "Pets", sortOrder = 1))
            dao.upsert(Fixtures.category("misc", name = "Misc", sortOrder = 2))
            dao.upsert(Fixtures.category("misc-a", name = "Misc A", parentId = "misc", sortOrder = 0))
            dao.upsert(Fixtures.category("salary", type = CategoryType.INCOME, name = "Salary"))
        }
    }

    @After fun tearDown() = db.close()

    @Test fun observeChildrenIsTheOrderedLiveSubcategories() = runTest {
        assertEquals(listOf("lunch", "dinner"), repo.observeChildren("food").first().map { it.id })
        repo.delete("lunch")
        assertEquals(listOf("dinner"), repo.observeChildren("food").first().map { it.id })
    }

    @Test fun observeSubsGroupsEveryLiveSubOfOneType() = runTest {
        assertEquals(setOf("lunch", "dinner", "misc-a"), repo.observeSubs(CategoryType.EXPENSE).first().map { it.id }.toSet())
    }

    @Test fun addSubGoesAtTheEndOfTheParentWithItsType() = runTest {
        val sub = repo.addSub("food", "  Snacks ")
        assertEquals("Snacks", sub.name)
        assertEquals("food", sub.parentId)
        assertEquals(CategoryType.EXPENSE, sub.type)
        assertEquals(2, sub.sortOrder)
        assertEquals(listOf("lunch", "dinner", sub.id), repo.observeChildren("food").first().map { it.id })
    }

    @Test fun toSubcategoryMovesTheCategoryAndItsChildrenAfterTheTargetsOwn() = runTest {
        repo.toSubcategory("food", "misc")
        val kids = repo.observeChildren("misc").first()
        assertEquals(listOf("misc-a", "food", "lunch", "dinner"), kids.map { it.id })
        assertEquals(listOf("misc", "misc", "misc", "misc"), kids.map { it.parentId })
        assertEquals(listOf("pets", "misc"), repo.top(CategoryType.EXPENSE).map { it.id })
        assertEquals(true, kids.all { it.dirty })
    }

    @Test fun toSubcategoryLeavesEntriesOnTheirCategories() = runTest {
        db.accountDao().upsert(Fixtures.account("cash"))
        db.txnDao().upsertAll(listOf(Fixtures.txn("a", TxnType.EXPENSE, "2026-09-01T12:00:00.000", "cash", 100, categoryId = "food")))
        repo.toSubcategory("food", "misc")
        assertEquals("food", db.txnDao().get("a")!!.categoryId)
    }

    @Test fun toSubcategoryRefusesItselfASubcategoryOrAnotherType() = runTest {
        repo.toSubcategory("food", "food")
        repo.toSubcategory("lunch", "pets") // not a main category
        repo.toSubcategory("food", "salary") // other type
        repo.toSubcategory("pets", "lunch") // target is a subcategory
        assertNull(repo.get("food")!!.parentId)
        assertEquals("food", repo.get("lunch")!!.parentId)
        assertNull(repo.get("pets")!!.parentId)
    }

    @Test fun toMainGoesToTheEndOfTheTopLevel() = runTest {
        repo.toMain("lunch")
        assertNull(repo.get("lunch")!!.parentId)
        assertEquals(listOf("food", "pets", "misc", "lunch"), repo.top(CategoryType.EXPENSE).map { it.id })
        assertEquals(listOf("dinner"), repo.observeChildren("food").first().map { it.id })
    }
}
