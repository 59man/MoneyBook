package com.moneybook.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.shared.model.RepeatFrequency
import com.moneybook.shared.model.TxnType
import com.moneybook.shared.money.SymbolPosition
import com.moneybook.testutil.Fixtures
import com.moneybook.testutil.inMemoryDb
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** One synced table, driven through its DAO without naming the entity type. */
private class Case(
    val table: String,
    val key: String,
    val applyRemote: suspend (updatedAt: Long) -> Unit,
    val upsertLocal: suspend (updatedAt: Long) -> Unit,
    val dirtyRows: suspend () -> Int,
    val clear: suspend (updatedAt: Long) -> Unit,
    val markAll: suspend () -> Unit,
    val softDeleteAll: suspend (now: Long) -> Unit,
    val dirtyCount: () -> Flow<Int>,
)

@RunWith(AndroidJUnit4::class)
class ChangeTrackingTest {
    private lateinit var db: MoneyBookDatabase

    @Before fun setUp() {
        db = inMemoryDb()
    }

    @After fun tearDown() = db.close()

    private fun cases(): List<Case> {
        val g = { at: Long -> Fixtures.group().copy(updatedAt = at) }
        val a = { at: Long -> Fixtures.account("a").copy(updatedAt = at) }
        val c = { at: Long -> Fixtures.category("c").copy(updatedAt = at) }
        val t = { at: Long -> Fixtures.txn("t", TxnType.EXPENSE, "2026-09-26T12:00:00.000", "a", 100).copy(updatedAt = at) }
        val m = { at: Long -> MemoEntity(id = "m", date = "2026-09-26", updatedAt = at) }
        val b = { at: Long -> BookmarkEntity(id = "b", name = "n", type = TxnType.EXPENSE, accountId = "a", amount = 1, currency = "CZK", createdAt = 1, updatedAt = at) }
        val r = { at: Long ->
            RepeatRuleEntity(
                id = "r", frequency = RepeatFrequency.MONTHLY, startDate = "2026-09-26", lastDate = "2026-09-26",
                type = TxnType.EXPENSE, accountId = "a", amount = 1, currency = "CZK", time = "12:00", updatedAt = at,
            )
        }
        val cur = { at: Long -> CurrencyEntity("EUR", "€", SymbolPosition.START, 2, "25", 0, updatedAt = at) }
        val bud = { at: Long -> BudgetEntity("budget-total-0", null, 0, 100, updatedAt = at) }
        val att = { at: Long -> AttachmentEntity("t/x.jpg", "t", "x.jpg", 0, updatedAt = at) }
        val ad = db.accountDao()
        return listOf(
            Case("account_group", "id", { ad.applyRemoteGroups(listOf(g(it).copy(dirty = false))) }, { ad.upsertGroups(listOf(g(it).copy(dirty = false))) },
                { ad.dirtyGroups().size }, { ad.clearDirtyGroup("g-cash", it) }, { ad.markAllGroupsDirty() }, { ad.softDeleteAllGroups(it) }, { ad.observeDirtyGroupCount() }),
            Case("account", "id", { ad.applyRemoteAccounts(listOf(a(it).copy(dirty = false))) }, { ad.upsert(a(it).copy(dirty = false)) },
                { ad.dirtyAccounts().size }, { ad.clearDirtyAccount("a", it) }, { ad.markAllAccountsDirty() }, { ad.softDeleteAllAccounts(it) }, { ad.observeDirtyAccountCount() }),
            db.categoryDao().let { d ->
                Case("category", "id", { d.applyRemoteAll(listOf(c(it).copy(dirty = false))) }, { d.upsert(c(it).copy(dirty = false)) },
                    { d.dirtyRows().size }, { d.clearDirty("c", it) }, { d.markAllDirty() }, { d.softDeleteAll(it) }, { d.observeDirtyCount() })
            },
            db.txnDao().let { d ->
                Case("txn", "id", { d.applyRemoteAll(listOf(t(it).copy(dirty = false))) }, { d.upsert(t(it).copy(dirty = false)) },
                    { d.dirtyRows().size }, { d.clearDirty("t", it) }, { d.markAllDirty() }, { d.softDeleteAll(it) }, { d.observeDirtyCount() })
            },
            db.memoDao().let { d ->
                Case("memo", "id", { d.applyRemoteAll(listOf(m(it).copy(dirty = false))) }, { d.upsert(m(it).copy(dirty = false)) },
                    { d.dirtyRows().size }, { d.clearDirty("m", it) }, { d.markAllDirty() }, { d.softDeleteAll(it) }, { d.observeDirtyCount() })
            },
            db.bookmarkDao().let { d ->
                Case("bookmark", "id", { d.applyRemoteAll(listOf(b(it).copy(dirty = false))) }, { d.upsert(b(it).copy(dirty = false)) },
                    { d.dirtyRows().size }, { d.clearDirty("b", it) }, { d.markAllDirty() }, { d.softDeleteAll(it) }, { d.observeDirtyCount() })
            },
            db.repeatRuleDao().let { d ->
                Case("repeat_rule", "id", { d.applyRemoteAll(listOf(r(it).copy(dirty = false))) }, { d.upsert(r(it).copy(dirty = false)) },
                    { d.dirtyRows().size }, { d.clearDirty("r", it) }, { d.markAllDirty() }, { d.softDeleteAll(it) }, { d.observeDirtyCount() })
            },
            db.currencyDao().let { d ->
                Case("currency", "code", { d.applyRemoteAll(listOf(cur(it).copy(dirty = false))) }, { d.upsert(cur(it).copy(dirty = false)) },
                    { d.dirtyRows().size }, { d.clearDirty("EUR", it) }, { d.markAllDirty() }, { d.softDeleteAll(it) }, { d.observeDirtyCount() })
            },
            db.budgetDao().let { d ->
                Case("budget", "id", { d.applyRemoteAll(listOf(bud(it).copy(dirty = false))) }, { d.upsertAll(listOf(bud(it).copy(dirty = false))) },
                    { d.dirtyRows().size }, { d.clearDirty("budget-total-0", it) }, { d.markAllDirty() }, { d.softDeleteAll(it) }, { d.observeDirtyCount() })
            },
            db.attachmentDao().let { d ->
                Case("attachment", "id", { d.applyRemoteAll(listOf(att(it).copy(dirty = false))) }, { d.upsertAll(listOf(att(it).copy(dirty = false))) },
                    { d.dirtyRows().size }, { d.clearDirty("t/x.jpg", it) }, { d.markAllDirty() }, { d.softDeleteAll(it) }, { d.observeDirtyCount() })
            },
        )
    }

    /** (updatedAt, raw dirty, deleted) of the table's only row. */
    private fun raw(table: String): Triple<Long, Int, Int> =
        db.openHelper.writableDatabase.query("SELECT updatedAt, dirty, deleted FROM `$table`").use {
            assertEquals("$table rows", 1, it.count)
            it.moveToFirst()
            Triple(it.getLong(0), it.getInt(1), it.getInt(2))
        }

    @Test fun everyDaoUpsertMarksACleanRowDirty() = runBlocking {
        for (c in cases()) {
            c.applyRemote(100)
            assertEquals(c.table, 0, raw(c.table).second)
            c.upsertLocal(200)
            assertEquals(c.table, 1, raw(c.table).second)
            assertEquals(c.table, 1, c.dirtyRows())
        }
    }

    @Test fun applyRemoteKeepsRowsClean() = runBlocking {
        for (c in cases()) {
            c.applyRemote(100)
            assertEquals(c.table, Triple(100L, 0, 0), raw(c.table))
            assertEquals(c.table, 0, c.dirtyRows())
        }
    }

    @Test fun triggerLiftsABackwardsUpdatedAt() = runBlocking {
        for (c in cases()) {
            c.applyRemote(100)
            c.upsertLocal(50)
            assertEquals("${c.table} backwards", 101L, raw(c.table).first)
            c.applyRemote(100)
            c.upsertLocal(100)
            assertEquals("${c.table} equal", 101L, raw(c.table).first)
            c.upsertLocal(500)
            assertEquals("${c.table} forwards", 500L, raw(c.table).first)
        }
    }

    @Test fun applyRemoteNeverLiftsUpdatedAt() = runBlocking {
        for (c in cases()) {
            c.applyRemote(100)
            c.applyRemote(100)
            assertEquals(c.table, 100L, raw(c.table).first)
            c.applyRemote(50)
            assertEquals(c.table, Triple(50L, 0, 0), raw(c.table))
        }
    }

    @Test fun markAllDirtyKeepsUpdatedAt() = runBlocking {
        for (c in cases()) {
            c.applyRemote(100)
            c.markAll()
            assertEquals(c.table, 100L, raw(c.table).first)
            assertEquals(c.table, 2, raw(c.table).second)
            assertEquals(c.table, 1, c.dirtyRows())
        }
        // A dirty row (1) is left alone by a second mark-all.
        db.txnDao().upsert(Fixtures.txn("t2", TxnType.EXPENSE, "2026-09-26T12:00:00.000", "a", 1).copy(updatedAt = 7))
        db.txnDao().markAllDirty()
        assertEquals(7L, db.txnDao().get("t2")!!.updatedAt)
        assertTrue(db.txnDao().get("t")!!.dirty)
    }

    @Test fun clearDirtyOnlyWhenUpdatedAtMatches() = runBlocking {
        for (c in cases()) {
            c.upsertLocal(100)
            c.clear(99)
            assertEquals("${c.table} stale", 1, c.dirtyRows())
            c.clear(100)
            assertEquals("${c.table} exact", 0, c.dirtyRows())
        }
    }

    @Test fun softDeleteQueriesStayDirty() = runBlocking {
        for (c in cases()) {
            c.applyRemote(100)
            c.softDeleteAll(200)
            assertEquals(c.table, Triple(200L, 1, 1), raw(c.table))
        }
    }

    @Test fun observeDirtyCountEmitsOnChange() = runBlocking {
        for (c in cases()) {
            assertEquals(c.table, 0, withTimeout(5_000) { c.dirtyCount().first() })
            c.upsertLocal(100)
            assertEquals(c.table, 1, withTimeout(5_000) { c.dirtyCount().first { it == 1 } })
            c.clear(100)
            assertEquals(c.table, 0, withTimeout(5_000) { c.dirtyCount().first { it == 0 } })
        }
    }

    @Test fun triggersExistOnAFreshDatabase() {
        val names = db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type = 'trigger'").use {
            generateSequence { if (it.moveToNext()) it.getString(0) else null }.toList()
        }
        assertEquals(SYNCED_TABLES.map { "${it.first}_mono" }.sorted(), names.sorted())
        assertEquals(10, names.size)
    }
}
