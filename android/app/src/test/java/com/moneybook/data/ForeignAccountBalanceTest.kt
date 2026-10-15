package com.moneybook.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.db.DbTime
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.domain.accountPeriodSummary
import com.moneybook.domain.txnEffect
import com.moneybook.shared.model.TxnType.EXPENSE
import com.moneybook.shared.model.TxnType.TRANSFER
import com.moneybook.testutil.Fixtures
import com.moneybook.testutil.inMemoryDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Measured on Realbyte: a EUR account keeps its balance in EUR; entries carry its side in accountAmount / toAmount. */
@RunWith(AndroidJUnit4::class)
class ForeignAccountBalanceTest {
    private lateinit var db: MoneyBookDatabase

    @Before fun setUp() { db = inMemoryDb() }
    @After fun tearDown() = db.close()

    @Test fun balancesUseEachAccountsOwnSide() = runTest {
        db.accountDao().upsertGroups(listOf(Fixtures.group()))
        db.accountDao().upsertAll(listOf(Fixtures.account("cash", currency = "USD"), Fixtures.account("eu", currency = "EUR", initialBalance = 10_000)))
        // $50 Cash → EuroSave at 1.1 lands as €45.45; $5 from EuroSave at 1.2 takes €4.17.
        db.txnDao().upsert(
            Fixtures.txn("t", TRANSFER, "2026-10-08T12:15:00.000", "cash", 5_000, toAccountId = "eu").copy(currency = "USD", toAmount = 4_545),
        )
        db.txnDao().upsert(Fixtures.txn("e", EXPENSE, "2026-10-08T12:21:00.000", "eu", 500).copy(currency = "USD", accountAmount = 417))

        val balances = db.accountDao().observeBalances(DbTime.MAX).first().associate { it.id to it.balance }
        assertEquals(-5_000L, balances["cash"])
        assertEquals(14_128L, balances["eu"])

        val rows = db.txnDao().observeRangeForAccount("eu", DbTime.MIN, DbTime.MAX).first()
        assertEquals(4_128L, rows.sumOf { txnEffect(it, "eu") })
        val summary = accountPeriodSummary("eu", rows)
        assertEquals(4_545L, summary.deposit)
        assertEquals(417L, summary.withdrawal)
    }
}
