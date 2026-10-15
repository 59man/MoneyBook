package com.moneybook.widget

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.AppSettings
import com.moneybook.data.DefaultData
import com.moneybook.data.Seeder
import com.moneybook.data.TimeSource
import com.moneybook.data.TxnRepository
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.domain.Mom
import com.moneybook.domain.Totals
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.TxnType
import com.moneybook.testutil.FakeSettingsRepository
import com.moneybook.testutil.Fixtures
import com.moneybook.testutil.inMemoryDb
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SummaryDataTest {
    private lateinit var db: MoneyBookDatabase
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-10-07T10:00:00Z"), ZoneOffset.UTC))
    private val cash = DefaultData.accountId(AccountGroupType.CASH)

    @Before fun setUp() {
        db = inMemoryDb()
        runBlocking { Seeder(db, time).seedIfEmpty("CZK") }
    }

    @After fun tearDown() = db.close()

    @Test fun theWidgetReadsBothMonthsUnderTheMonthlyStartDate() = runBlocking {
        db.txnDao().upsertAll(
            listOf(
                Fixtures.txn("a", TxnType.EXPENSE, "2026-08-24T09:00:00.000", cash, 1_000), // before Aug 25: outside
                Fixtures.txn("b", TxnType.EXPENSE, "2026-08-30T09:00:00.000", cash, 4_000),
                Fixtures.txn("c", TxnType.EXPENSE, "2026-09-26T09:00:00.000", cash, 6_000),
                Fixtures.txn("d", TxnType.INCOME, "2026-10-20T09:00:00.000", cash, 9_000),
            ),
        )
        val data = loadSummary(FakeSettingsRepository(AppSettings("CZK", monthStartDay = 25)), TxnRepository(db, time), time.today())
        assertEquals(Totals(income = 9_000, expense = 6_000), data.summary.month)
        assertEquals(Mom(2_000, 50, good = false), data.summary.expense)
        assertEquals(Mom(0, null, null), data.summary.income)
        assertEquals("CZK", data.currency.code)
    }

    // A widget session that is still open must show a new entry too, so the widget collects a flow, not a snapshot.
    @Test fun theSummaryFlowFollowsNewEntries() = runBlocking {
        val flow = summaryFlow(FakeSettingsRepository(AppSettings("CZK")), TxnRepository(db, time)) { time.today() }
        val seen = mutableListOf<Long>()
        val job = launch(Dispatchers.Default) { flow.collect { seen += it.summary.month.expense } }
        withTimeout(5_000) { while (seen.isEmpty()) delay(20) }
        db.txnDao().upsert(Fixtures.txn("n", TxnType.EXPENSE, "2026-10-05T09:00:00.000", cash, 2_500))
        withTimeout(5_000) { while (seen.last() != 2_500L) delay(20) }
        job.cancel()
    }
}
