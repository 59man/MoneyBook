package com.moneybook.ui.accounts

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.AccountRepository
import com.moneybook.data.AppSettings
import com.moneybook.data.CurrencyRepository
import com.moneybook.data.DefaultData
import com.moneybook.data.Seeder
import com.moneybook.data.TimeSource
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.TxnType
import com.moneybook.shared.money.SymbolPosition
import com.moneybook.testutil.FakeSettingsRepository
import com.moneybook.testutil.Fixtures
import com.moneybook.testutil.MainDispatcherRule
import com.moneybook.testutil.inMemoryDb
import com.moneybook.ui.edit.KeypadKey
import java.math.BigDecimal
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Account Info → Currency: a new account takes the chip; an existing one asks first, then converts. */
@RunWith(AndroidJUnit4::class)
class AccountEditCurrencyTest {
    @get:Rule val main = MainDispatcherRule()
    private lateinit var db: MoneyBookDatabase
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC))
    private val settings = FakeSettingsRepository(AppSettings(mainCurrency = "USD"))
    private val cash = DefaultData.accountId(AccountGroupType.CASH)
    private val savings = DefaultData.groupId(AccountGroupType.SAVINGS)
    private lateinit var repo: AccountRepository
    private lateinit var currencies: CurrencyRepository

    @Before fun setUp() {
        db = inMemoryDb()
        repo = AccountRepository(db, time)
        currencies = CurrencyRepository(db, settings, time) {}
        runBlocking {
            Seeder(db, time).seedIfEmpty("USD")
            currencies.add("EUR")
            currencies.update("EUR", BigDecimal("1.1"), SymbolPosition.START, 2)
        }
    }

    @After fun tearDown() = db.close()

    private fun vm(id: String? = null) = AccountEditViewModel(id, repo, settings, time, currencies)

    @Test fun aNewAccountTakesTheChosenCurrency() = runTest {
        val vm = vm()
        vm.state.first { it.loaded && it.currencies.size == 2 }
        assertEquals(listOf("USD", "EUR"), vm.state.value.currencies.map { it.code })
        vm.pickGroup(savings)
        vm.setName("EuroSave")
        vm.pickCurrency("EUR")
        assertNull(vm.state.value.pendingCurrency)
        "100".forEach { vm.onKey(KeypadKey.Digit(it)) }
        vm.save()
        vm.events.first()
        val saved = repo.accounts().single { it.name == "EuroSave" }
        assertEquals("EUR", saved.currency)
        assertEquals(10_000L, saved.initialBalance)
    }

    @Test fun anExistingAccountAsksThenConvertsItsBalanceAndEntries() = runTest {
        db.txnDao().upsert(Fixtures.txn("e", TxnType.EXPENSE, "2026-10-08T09:00:00.000", cash, 1_100).copy(currency = "USD"))
        repo.save(repo.get(cash)!!.copy(initialBalance = 12_100)) // balance $110.00
        val vm = vm(cash)
        vm.state.first { it.loaded && it.currencies.size == 2 }
        vm.pickCurrency("EUR")
        assertEquals("EUR", vm.state.value.pendingCurrency)
        assertEquals("USD", vm.state.value.currency.code)
        vm.confirmCurrency()
        assertEquals("EUR", vm.state.value.currency.code)
        assertEquals(10_000L, vm.state.value.amount.minorUnits()) // $110 at 1.1
        vm.save()
        vm.events.first()
        assertEquals("EUR", repo.get(cash)!!.currency)
        assertEquals(10_000L, repo.balanceOf(cash))
        assertEquals(1_000L, db.txnDao().get("e")!!.accountAmount)
    }

    @Test fun dismissingTheNoticeKeepsTheCurrency() = runTest {
        val vm = vm(cash)
        vm.state.first { it.loaded && it.currencies.size == 2 }
        vm.pickCurrency("EUR")
        vm.dismissCurrency()
        assertNull(vm.state.value.pendingCurrency)
        assertEquals("USD", vm.state.value.currency.code)
    }
}
