package com.moneybook.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.shared.model.TxnType.EXPENSE
import com.moneybook.shared.money.Fx
import com.moneybook.shared.money.MoneyFormat
import com.moneybook.shared.money.SymbolPosition
import com.moneybook.testutil.FakeSettingsRepository
import com.moneybook.testutil.Fixtures
import com.moneybook.testutil.inMemoryDb
import com.moneybook.testutil.testPhotos
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CurrencyRepositoryTest {
    private lateinit var db: MoneyBookDatabase
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC))
    private val settings = FakeSettingsRepository(AppSettings(mainCurrency = "USD"))
    private lateinit var repo: CurrencyRepository
    private lateinit var accounts: AccountRepository

    @Before fun setUp() {
        db = inMemoryDb()
        val reset = ResetRepository(db, settings, Seeder(db, time), time, defaultCurrency = "USD", photos = testPhotos())
        repo = CurrencyRepository(db, settings, time) { code -> reset.resetAll(code) }
        accounts = AccountRepository(db, time)
    }

    @After fun tearDown() = db.close()

    private suspend fun seedAccounts() {
        db.accountDao().upsertGroups(listOf(Fixtures.group()))
        db.accountDao().upsertAll(listOf(Fixtures.account("usd", currency = "USD", initialBalance = 5_000)))
    }

    @Test fun addIgnoresMainAndDuplicatesAndStartsAtRateOne() = runTest {
        assertTrue(repo.add("EUR"))
        assertFalse(repo.add("EUR"))
        assertFalse(repo.add("USD"))
        val s = repo.current()
        assertEquals(listOf("EUR"), s.subs.map { it.code })
        assertEquals("1", s.subs.single().rate)
        assertEquals(SymbolPosition.START, s.spec("EUR").position)
        assertEquals(listOf("USD", "EUR"), s.stripSpecs.map { it.code })
    }

    @Test fun deleteIsRefusedWhileAnAccountUsesIt() = runTest {
        seedAccounts()
        repo.add("EUR")
        accounts.save(Fixtures.account("eu", currency = "EUR"))
        assertFalse(repo.delete("EUR"))
        accounts.delete("eu")
        assertTrue(repo.delete("EUR"))
        assertTrue(repo.current().subs.isEmpty())
        assertTrue(repo.add("EUR")) // a deleted currency can come back
    }

    @Test fun tombstonedRowKeepsDisplay() = runTest {
        repo.add("EUR")
        repo.update("EUR", BigDecimal("1.1"), SymbolPosition.END, 1)
        repo.delete("EUR")
        assertEquals("10.0€", MoneyFormat.format(1_000, repo.current().spec("EUR")))
    }

    @Test fun mainDisplayIsStoredAsTheMainRow() = runTest {
        repo.setMainDisplay(SymbolPosition.END, 0)
        val s = repo.current()
        assertEquals("1,000$", MoneyFormat.format(100_000, s.mainSpec))
        assertTrue(s.subs.isEmpty())
    }

    @Test fun changeMainRetainConverts() = runTest {
        seedAccounts()
        repo.add("EUR")
        repo.update("EUR", BigDecimal("1.1"), SymbolPosition.START, 2)
        db.txnDao().upsert(Fixtures.txn("a", EXPENSE, "2026-10-08T10:00:00.000", "usd", 1_000).copy(currency = "USD"))
        db.txnDao().upsert(Fixtures.txn("b", EXPENSE, "2026-10-08T11:00:00.000", "usd", 2_000).copy(currency = "EUR", amountMain = 2_200, accountAmount = 2_200))

        repo.changeMainRetain("CZK", BigDecimal("25")) // 1 USD = 25 CZK

        val s = repo.current()
        assertEquals("CZK", settings.current().mainCurrency)
        assertEquals(listOf("EUR", "USD"), s.subs.map { it.code }.sorted())
        assertEquals(0, BigDecimal("27.5").compareTo(s.fx.rate("EUR")))
        assertEquals(0, BigDecimal("25").compareTo(s.fx.rate("USD")))
        assertEquals(listOf(25_000L, 55_000L), db.txnDao().allRows().sortedBy { it.id }.map { it.amountMain })
        assertEquals(2_200L, db.txnDao().get("b")!!.accountAmount) // accounts keep their own currency
        assertEquals(5_000L, accounts.get("usd")!!.initialBalance)
    }

    /** Review: 23.1 × (1 / 25.3 at 10 places) has 11 decimals, which parseRate rejects (the rate fell back to 1). */
    @Test fun changeMainRetainKeepsRatesWithinTenDecimals() = runTest {
        settings.setMainCurrency("CZK")
        repo.add("EUR"); repo.update("EUR", BigDecimal("25.3"), SymbolPosition.START, 2)
        repo.add("USD"); repo.update("USD", BigDecimal("23.1"), SymbolPosition.START, 2)
        repo.changeMainRetain("EUR", BigDecimal("0.0395256917"))
        val usd = repo.current().rows.single { it.code == "USD" }.rate
        assertEquals(0, BigDecimal("0.9130434783").compareTo(Fx.parseRate(usd)))
    }

    @Test fun changeMainInitializeResetsEverythingIntoTheNewCurrency() = runTest {
        Seeder(db, time).seedIfEmpty("USD")
        repo.add("EUR")
        val cash = accounts.accounts().first()
        db.txnDao().upsert(Fixtures.txn("a", EXPENSE, "2026-10-08T10:00:00.000", cash.id, 1_000))
        repo.changeMainInitialize("CZK")
        assertEquals("CZK", settings.current().mainCurrency)
        assertTrue(repo.current().subs.isEmpty())
        assertTrue(accounts.accounts().all { it.currency == "CZK" })
        assertTrue(db.txnDao().get("a")!!.deleted)
    }

    @Test fun changeAccountCurrencyConvertsTheInitialBalanceAndItsEntries() = runTest {
        db.accountDao().upsertGroups(listOf(Fixtures.group()))
        db.accountDao().upsertAll(listOf(Fixtures.account("a", currency = "USD", initialBalance = 11_000), Fixtures.account("b", currency = "USD")))
        repo.add("EUR")
        repo.update("EUR", BigDecimal("1.1"), SymbolPosition.START, 2)
        db.txnDao().upsert(Fixtures.txn("e1", EXPENSE, "2026-10-08T10:00:00.000", "a", 550).copy(currency = "USD"))
        db.txnDao().upsert(Fixtures.txn("e2", EXPENSE, "2026-10-08T10:00:00.000", "a", 1_000).copy(currency = "EUR", amountMain = 1_100, accountAmount = 1_100))
        db.txnDao().upsert(
            Fixtures.txn("t", com.moneybook.shared.model.TxnType.TRANSFER, "2026-10-08T11:00:00.000", "b", 2_200, toAccountId = "a").copy(currency = "USD"),
        )

        repo.changeAccountCurrency("a", "EUR")

        val a = accounts.get("a")!!
        assertEquals("EUR", a.currency)
        assertEquals(10_000L, a.initialBalance)
        assertEquals(500L, db.txnDao().get("e1")!!.accountAmount)
        assertEquals(null, db.txnDao().get("e2")!!.accountAmount) // entered in EUR: the amount itself
        assertEquals(2_000L, db.txnDao().get("t")!!.toAmount)
        assertEquals(null, db.txnDao().get("t")!!.accountAmount) // the From account did not change
    }

    @Test fun `changeMainRetain scales budgets`() = runTest {
        BudgetRepository(db, time).add(null, 10_000, 202610) // $100.00
        repo.changeMainRetain("CZK", BigDecimal("23.5"))
        assertEquals(235_000L, db.budgetDao().allRows().single().amount) // 2,350.00 Kč
    }
}
