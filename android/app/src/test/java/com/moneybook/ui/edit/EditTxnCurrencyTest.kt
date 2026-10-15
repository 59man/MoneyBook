package com.moneybook.ui.edit

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.AccountRepository
import com.moneybook.data.AppSettings
import com.moneybook.data.BookmarkRepository
import com.moneybook.data.CategoryRepository
import com.moneybook.data.CurrencyRepository
import com.moneybook.data.DefaultData
import com.moneybook.data.RepeatRepository
import com.moneybook.data.Seeder
import com.moneybook.data.TimeSource
import com.moneybook.data.TxnRepository
import com.moneybook.data.db.AccountEntity
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.CategoryType
import com.moneybook.shared.money.SymbolPosition
import com.moneybook.testutil.FakeSettingsRepository
import com.moneybook.testutil.MainDispatcherRule
import com.moneybook.testutil.inMemoryDb
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
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

/** Entries in a sub-currency, as measured on Realbyte (main USD, EUR at 1.1). */
@RunWith(AndroidJUnit4::class)
class EditTxnCurrencyTest {
    @get:Rule val main = MainDispatcherRule()
    private lateinit var db: MoneyBookDatabase
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC))
    private val settings = FakeSettingsRepository(AppSettings(mainCurrency = "USD"))
    private val cash = DefaultData.accountId(AccountGroupType.CASH)
    private val food = DefaultData.categoryId(CategoryType.EXPENSE, 0)
    private lateinit var currencies: CurrencyRepository

    @Before fun setUp() {
        db = inMemoryDb()
        currencies = CurrencyRepository(db, settings, time) {}
        runBlocking {
            Seeder(db, time).seedIfEmpty("USD")
            currencies.add("EUR")
            currencies.update("EUR", BigDecimal("1.1"), SymbolPosition.START, 2)
            currencies.add("JPY")
        }
    }

    /** The form collects the currencies for as long as it lives; a test's VMs must stop before its database closes. */
    private val vms = mutableListOf<EditTxnViewModel>()

    @After fun tearDown() {
        // Joined, so nothing resumes on Dispatchers.Main while the rule resets it.
        runBlocking { vms.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        db.close()
    }

    private fun vm(txnId: String? = null) = EditTxnViewModel(
        txnId, null, TxnRepository(db, time), AccountRepository(db, time), CategoryRepository(db, time),
        settings, time, BookmarkRepository(db, time), RepeatRepository(db, time), currencies,
    ).also { vms += it }

    private fun EditTxnViewModel.type(digits: String) = digits.forEach { if (it == '.') onKey(KeypadKey.Dot) else onKey(KeypadKey.Digit(it)) }

    private suspend fun EditTxnViewModel.ready() = state.first { it.loaded && it.strip.size == 3 }

    @Test fun theStripShowsMainThenSubs() = runTest {
        val vm = vm()
        assertEquals(listOf("USD", "EUR", "JPY"), vm.ready().strip.map { it.code })
        assertEquals("USD", vm.state.value.currency.code)
        assertNull(vm.state.value.shownMain)
    }

    @Test fun aSubCurrencyAmountShowsItsMainAmountAndRate() = runTest {
        val vm = vm()
        vm.ready()
        vm.pickCurrency("EUR")
        vm.type("20")
        assertEquals(2_200L, vm.state.value.shownMain)
        assertEquals("1.1", vm.state.value.shownRate)

        vm.openRateSheet()
        vm.focusRate(RateField.MAIN)
        "26".forEach { vm.onRateKey(KeypadKey.Digit(it)) }
        assertEquals("1.3", vm.state.value.rateSheet!!.rate)
        vm.rateSheetDone()
        assertNull(vm.state.value.rateSheet)
        assertEquals(2_600L, vm.state.value.shownMain)
        assertEquals("1.3", vm.state.value.shownRate)

        vm.pickCategory(food)
        vm.pickAccount(cash)
        vm.save()
        vm.events.first()
        val row = db.txnDao().allRows().single()
        assertEquals(2_000L, row.amount)
        assertEquals("EUR", row.currency)
        assertEquals(2_600L, row.amountMain)
        assertEquals(2_600L, row.accountAmount)
        assertEquals(0, BigDecimal("1.1").compareTo(currencies.current().fx.rate("EUR"))) // the sub-currency keeps its rate
    }

    @Test fun typingTheRateRecomputesTheMainAmount() = runTest {
        val vm = vm()
        vm.ready()
        vm.pickCurrency("EUR")
        vm.type("20")
        vm.openRateSheet()
        vm.onRateKey(KeypadKey.Digit('1'))
        vm.onRateKey(KeypadKey.Dot)
        vm.onRateKey(KeypadKey.Digit('2'))
        vm.onRateKey(KeypadKey.Digit('5'))
        assertEquals(2_500L, vm.state.value.rateSheet!!.main.minorUnits())
        vm.closeRateSheet()
        assertEquals(2_200L, vm.state.value.shownMain) // ✕ changes nothing
    }

    @Test fun pickCurrencyTrimsDecimals() = runTest {
        val vm = vm()
        vm.ready()
        vm.pickCurrency("EUR")
        vm.type("12.5")
        vm.pickCurrency("JPY")
        assertEquals(12L, vm.state.value.amount.minorUnits())
        assertEquals(0, vm.state.value.amount.decimals)
    }

    @Test fun resaveKeepsStoredAmounts() = runTest {
        val first = vm()
        first.ready()
        first.pickCurrency("EUR")
        first.type("20")
        first.openRateSheet(); first.focusRate(RateField.MAIN); "26".forEach { first.onRateKey(KeypadKey.Digit(it)) }; first.rateSheetDone()
        first.pickCategory(food); first.pickAccount(cash)
        first.save(); first.events.first()
        val id = db.txnDao().allRows().single().id
        currencies.update("EUR", BigDecimal("1.2"), SymbolPosition.START, 2)

        val again = vm(id)
        again.ready()
        assertEquals("1.3", again.state.value.shownRate) // the entry's own rate
        again.setNote("Lunch")
        again.save(); again.events.first()
        val row = db.txnDao().get(id)!!
        assertEquals(2_600L, row.amountMain)
        assertEquals(2_600L, row.accountAmount)
        assertEquals("Lunch", row.note)
    }

    /** Review: touching a field (re-picking the account, a digit then ⌫) must not move an old entry's stored sides. */
    @Test fun touchingWithoutChangingKeepsStoredAmounts() = runTest {
        db.accountDao().upsert(
            AccountEntity("eurosave", DefaultData.groupId(AccountGroupType.ACCOUNTS), "EuroSave", "EUR", 0, sortOrder = 9, updatedAt = 0),
        )
        currencies.update("EUR", BigDecimal("1.2"), SymbolPosition.START, 2)
        val first = vm()
        first.ready()
        first.type("5")
        first.pickCategory(food); first.pickAccount("eurosave")
        first.save(); first.events.first()
        val id = db.txnDao().allRows().single().id
        assertEquals(417L, db.txnDao().get(id)!!.accountAmount)
        currencies.update("EUR", BigDecimal("1.1"), SymbolPosition.START, 2)

        val again = vm(id)
        again.ready()
        again.openPanel(Panel.ACCOUNT); again.pickAccount("eurosave")
        again.onKey(KeypadKey.Digit('1')); again.onKey(KeypadKey.Backspace)
        again.save(); again.events.first()
        assertEquals(417L, db.txnDao().get(id)!!.accountAmount)
    }
}
