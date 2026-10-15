package com.moneybook.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.TxnType.EXPENSE
import com.moneybook.shared.model.TxnType.INCOME
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoteSuggestionsTest {
    private lateinit var db: MoneyBookDatabase
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneOffset.UTC))
    private val cash = DefaultData.accountId(AccountGroupType.CASH)

    @Before fun setUp() {
        db = inMemoryDb()
        runBlocking {
            Seeder(db, time).seedIfEmpty("CZK")
            db.txnDao().upsertAll(listOf(
                Fixtures.txn("a", EXPENSE, "2026-09-01T12:00:00.000", cash, 100, note = "Oběd jídlo").copy(updatedAt = 10),
                Fixtures.txn("b", EXPENSE, "2026-09-02T12:00:00.000", cash, 100, note = "Oběd jídlo").copy(updatedAt = 20),
                Fixtures.txn("c", EXPENSE, "2026-09-03T12:00:00.000", cash, 100, note = "Nákup jídlo").copy(updatedAt = 30),
                Fixtures.txn("d", INCOME, "2026-09-04T12:00:00.000", cash, 100, note = "Jídelenky").copy(updatedAt = 40),
                Fixtures.txn("e", EXPENSE, "2026-09-05T12:00:00.000", cash, 100, note = "Smazáno jídlo").copy(updatedAt = 50, deleted = true),
            ))
        }
    }

    @After fun tearDown() = db.close()

    @Test fun suggestionsCountLiveNotesOfTheTypeSinceTheReset() = runTest {
        val repo = TxnRepository(db, time)
        assertEquals(listOf("Oběd jídlo", "Nákup jídlo"), repo.noteSuggestions(EXPENSE, "JÍD", since = 0))
        assertEquals(listOf("Jídelenky"), repo.noteSuggestions(INCOME, "jíd", since = 0))
        // After a reset at 25 only entry c (saved at 30) still counts.
        assertEquals(listOf("Nákup jídlo"), repo.noteSuggestions(EXPENSE, "jíd", since = 25))
    }

    @Test fun observeAllListsEveryLiveEntryNewestFirst() = runTest {
        assertEquals(listOf("d", "c", "b", "a"), TxnRepository(db, time).observeAll().first().map { it.id })
    }
}
