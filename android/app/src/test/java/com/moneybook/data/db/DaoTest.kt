package com.moneybook.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.TxnType.EXPENSE
import com.moneybook.shared.model.TxnType.INCOME
import com.moneybook.shared.model.TxnType.TRANSFER
import com.moneybook.testutil.Fixtures
import com.moneybook.testutil.inMemoryDb
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
class DaoTest {
    private lateinit var db: MoneyBookDatabase

    @Before fun setUp() {
        db = inMemoryDb()
        runBlocking {
            db.accountDao().upsertGroups(listOf(Fixtures.group()))
            db.accountDao().upsertAll(listOf(
                Fixtures.account("cash", name = "Cash", initialBalance = 100_000),
                Fixtures.account("bank", name = "Bank", sortOrder = 1),
            ))
            db.categoryDao().upsertAll(listOf(Fixtures.category("food", name = "🍜 Food")))
        }
    }

    @After fun tearDown() = db.close()

    @Test fun rangeQueryJoinsNamesExcludesDeletedAndSortsNewestFirst() = runTest {
        db.txnDao().upsertAll(listOf(
            Fixtures.txn("a", EXPENSE, "2026-09-01T08:00:00.000", "cash", 1_000, categoryId = "food"),
            Fixtures.txn("b", EXPENSE, "2026-09-30T23:59:59.999", "cash", 2_000, categoryId = "food"),
            Fixtures.txn("c", EXPENSE, "2026-10-01T00:00:00.000", "cash", 3_000, categoryId = "food"),
            Fixtures.txn("d", TRANSFER, "2026-09-15T12:00:00.000", "cash", 500, toAccountId = "bank"),
            Fixtures.txn("e", EXPENSE, "2026-09-10T12:00:00.000", "cash", 100).copy(deleted = true),
        ))
        val rows = db.txnDao().observeRange("2026-09-01T00:00:00.000", "2026-10-01T00:00:00.000").first()
        assertEquals(listOf("b", "d", "a"), rows.map { it.id })
        assertEquals("🍜 Food", rows[2].categoryName)
        assertEquals("Cash", rows[1].accountName)
        assertEquals("Bank", rows[1].toAccountName)
    }

    @Test fun rangeRowsCarryParentCategoryAndGroupTypes() = runTest {
        db.accountDao().upsertGroups(listOf(Fixtures.group("g-card", AccountGroupType.CARD, "Card", sortOrder = 1)))
        db.accountDao().upsertAll(listOf(Fixtures.account("visa", groupId = "g-card", name = "Visa")))
        db.categoryDao().upsertAll(listOf(Fixtures.category("lunch", name = "Lunch", parentId = "food")))
        db.txnDao().upsertAll(listOf(
            Fixtures.txn("l", EXPENSE, "2026-09-02T12:00:00.000", "visa", 1_000, categoryId = "lunch"),
            Fixtures.txn("t", TRANSFER, "2026-09-03T12:00:00.000", "cash", 500, toAccountId = "visa"),
        ))
        // A deleted parent still names its subcategory's entries.
        db.categoryDao().upsertAll(listOf(Fixtures.category("food", name = "🍜 Food").copy(deleted = true)))

        val rows = db.txnDao().observeRange("2026-09-01T00:00:00.000", "2026-10-01T00:00:00.000").first().associateBy { it.id }
        val lunch = rows.getValue("l")
        assertEquals("food", lunch.parentCategoryId)
        assertEquals("🍜 Food", lunch.parentCategoryName)
        assertEquals(AccountGroupType.CARD, lunch.accountGroupType)
        val transfer = rows.getValue("t")
        assertEquals(AccountGroupType.CASH, transfer.accountGroupType)
        assertEquals(AccountGroupType.CARD, transfer.toAccountGroupType)
        assertNull(transfer.parentCategoryId)
    }

    @Test fun accountRangeIncludesBothTransferDirections() = runTest {
        db.txnDao().upsertAll(listOf(
            Fixtures.txn("in", TRANSFER, "2026-09-15T12:00:00.000", "cash", 500, toAccountId = "bank"),
            Fixtures.txn("other", EXPENSE, "2026-09-16T12:00:00.000", "cash", 500, categoryId = "food"),
        ))
        val rows = db.txnDao().observeRangeForAccount("bank", "2026-09-01T00:00:00.000", "2026-10-01T00:00:00.000").first()
        assertEquals(listOf("in"), rows.map { it.id })
    }

    @Test fun balancesIncludeInitialIncomeExpenseAndTransfers() = runTest {
        db.txnDao().upsertAll(listOf(
            Fixtures.txn("i", INCOME, "2026-09-01T08:00:00.000", "cash", 50_000),
            Fixtures.txn("x", EXPENSE, "2026-09-02T08:00:00.000", "cash", 20_000, categoryId = "food"),
            Fixtures.txn("t", TRANSFER, "2026-09-03T08:00:00.000", "cash", 10_000, toAccountId = "bank", fee = 100),
            Fixtures.txn("gone", EXPENSE, "2026-09-04T08:00:00.000", "cash", 99_900).copy(deleted = true),
            Fixtures.txn("future", EXPENSE, "2026-12-01T08:00:00.000", "cash", 1),
        ))
        val all = db.accountDao().observeBalances(DbTime.MAX).first().associate { it.id to it.balance }
        assertEquals(100_000L + 50_000 - 20_000 - 10_000 - 100 - 1, all["cash"])
        assertEquals(10_000L, all["bank"])
        val untilOct = db.accountDao().observeBalances("2026-10-01T00:00:00.000").first().associate { it.id to it.balance }
        assertEquals(100_000L + 50_000 - 20_000 - 10_000 - 100, untilOct["cash"])
    }

    @Test fun dbTimeRoundTripsAndSortsAsText() {
        val t = java.time.LocalDateTime.of(2026, 9, 26, 18, 40, 0)
        assertEquals("2026-09-26T18:40:00.000", DbTime.format(t))
        assertEquals(t, DbTime.parse(DbTime.format(t)))
    }

    @Test fun rangeRowsCarryTransferExpenseFlagsAndGroups() = runTest {
        db.accountDao().upsertGroups(listOf(Fixtures.group("g-save", AccountGroupType.OTHERS, "EuroSave", sortOrder = 1)))
        db.accountDao().upsertAll(listOf(Fixtures.account("save", groupId = "g-save", name = "Save", transferExpense = true)))
        db.txnDao().upsertAll(listOf(
            Fixtures.txn("in", TRANSFER, "2026-09-03T12:00:00.000", "cash", 500, toAccountId = "save"),
            Fixtures.txn("out", TRANSFER, "2026-09-04T12:00:00.000", "save", 500, toAccountId = "cash"),
            Fixtures.txn("x", EXPENSE, "2026-09-05T12:00:00.000", "cash", 500),
        ))
        val rows = db.txnDao().observeRange("2026-09-01T00:00:00.000", "2026-10-01T00:00:00.000").first().associateBy { it.id }
        val into = rows.getValue("in")
        assertEquals(false, into.fromTransferExpense)
        assertEquals(true, into.toTransferExpense)
        assertEquals("g-cash", into.accountGroupId)
        assertEquals("Cash", into.accountGroupName)
        assertEquals("g-save", into.toAccountGroupId)
        assertEquals("EuroSave", into.toAccountGroupName)
        assertEquals(true, rows.getValue("out").fromTransferExpense)
        val plain = rows.getValue("x")
        assertEquals(false, plain.toTransferExpense)
        assertNull(plain.toAccountGroupId)
    }
}
