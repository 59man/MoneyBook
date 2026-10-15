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
import com.moneybook.domain.totalsOf
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.CategoryType
import com.moneybook.shared.model.TxnType
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A transfer's Fees, as measured on Realbyte: $50 Cash → EuroSave with a $2 fee (main USD, EUR at 1.1). */
@RunWith(AndroidJUnit4::class)
class EditTxnFeeTest {
    @get:Rule val main = MainDispatcherRule()
    private lateinit var db: MoneyBookDatabase
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC))
    private val settings = FakeSettingsRepository(AppSettings(mainCurrency = "USD"))
    private val cash = DefaultData.accountId(AccountGroupType.CASH)
    private val euro = "eurosave"
    private val food = DefaultData.categoryId(CategoryType.EXPENSE, 0)
    private lateinit var currencies: CurrencyRepository

    @Before fun setUp() {
        db = inMemoryDb()
        currencies = CurrencyRepository(db, settings, time) {}
        runBlocking {
            Seeder(db, time).seedIfEmpty("USD")
            currencies.add("EUR")
            currencies.update("EUR", BigDecimal("1.1"), SymbolPosition.START, 2)
            db.accountDao().upsert(
                AccountEntity(euro, DefaultData.groupId(AccountGroupType.ACCOUNTS), "EuroSave", "EUR", 0, sortOrder = 9, updatedAt = 0),
            )
        }
    }

    /** The form collects the currencies for as long as it lives; a test's VMs must stop before its database closes. */
    private val vms = mutableListOf<EditTxnViewModel>()

    @After fun tearDown() {
        // Joined, so nothing resumes on Dispatchers.Main while the rule resets it.
        runBlocking { vms.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        db.close()
    }

    private fun vm(txnId: String? = null, copyOf: String? = null) = EditTxnViewModel(
        txnId, null, TxnRepository(db, time), AccountRepository(db, time), CategoryRepository(db, time),
        settings, time, BookmarkRepository(db, time), RepeatRepository(db, time), currencies, copyOf = copyOf,
    ).also { vms += it }

    private fun EditTxnViewModel.type(digits: String) = digits.forEach { onKey(KeypadKey.Digit(it)) }

    private suspend fun EditTxnViewModel.saved() { save(); events.first() }

    /** Saves the measured transfer and returns its id. */
    private suspend fun transferWithFee(): String {
        val vm = vm()
        vm.state.first { it.loaded }
        vm.setType(TxnType.TRANSFER)
        vm.type("50")
        vm.addFee()
        assertEquals(Panel.FEE, vm.state.value.panel)
        vm.type("2")
        vm.pickAccount(cash)
        vm.pickAccount(euro)
        vm.saved()
        return db.txnDao().allRows().single { it.type == TxnType.TRANSFER }.id
    }

    private suspend fun fee(id: String) = db.txnDao().get(EditTxnViewModel.feeId(id))

    @Test fun aFeeIsSavedAsAFeesExpenseFromTheFromAccount() = runTest {
        val id = transferWithFee()
        val transfer = db.txnDao().get(id)!!
        assertEquals(5_000L, transfer.amount)
        assertEquals(4_545L, transfer.toAmount)
        val fee = fee(id)!!
        assertEquals(TxnType.EXPENSE, fee.type)
        assertEquals(200L, fee.amount)
        assertEquals("USD", fee.currency)
        assertEquals("Fees", fee.note)
        assertEquals(cash, fee.accountId)
        assertEquals("Other", db.categoryDao().get(fee.categoryId!!)!!.name)
        assertEquals(transfer.dateTime, fee.dateTime)
        val rows = TxnRepository(db, time).observeAll().first()
        assertEquals(200L, totalsOf(rows).expense)
    }

    @Test fun openingTheTransferShowsItsFee() = runTest {
        val id = transferWithFee()
        val vm = vm(id)
        val s = vm.state.first { it.loaded }
        assertEquals(200L, s.fee?.minorUnits())
        assertEquals("USD", s.feeCurrency.code)
    }

    @Test fun removingTheFeeTombstonesIt() = runTest {
        val id = transferWithFee()
        val vm = vm(id)
        vm.state.first { it.loaded }
        vm.removeFee()
        assertNull(vm.state.value.fee)
        vm.saved()
        assertTrue(fee(id)!!.deleted)
    }

    @Test fun typeChangeDropsFee() = runTest {
        val id = transferWithFee()
        val vm = vm(id)
        vm.state.first { it.loaded }
        vm.setType(TxnType.EXPENSE)
        assertNull(vm.state.value.fee)
        vm.pickCategory(food)
        vm.saved()
        assertTrue(fee(id)!!.deleted)
    }

    @Test fun deletingTheTransferTombstonesItsFee() = runTest {
        val id = transferWithFee()
        val vm = vm(id)
        vm.state.first { it.loaded }
        vm.delete()
        vm.events.first()
        assertTrue(db.txnDao().get(id)!!.deleted)
        assertTrue(fee(id)!!.deleted)
    }

    @Test fun aCopyGetsItsOwnFee() = runTest {
        val id = transferWithFee()
        val vm = vm(copyOf = id)
        vm.state.first { it.loaded }
        vm.saved()
        val copyId = db.txnDao().allRows().single { it.type == TxnType.TRANSFER && it.id != id }.id
        assertNotNull(fee(copyId))
        assertEquals(200L, fee(copyId)!!.amount)
        assertTrue(!fee(id)!!.deleted)
    }

    @Test fun anUnchangedEuroFeeKeepsItsStoredAmountsAfterARateChange() = runTest {
        val vm = vm()
        vm.state.first { it.loaded && it.strip.size == 2 }
        vm.setType(TxnType.TRANSFER)
        vm.type("50")
        vm.addFee()
        vm.pickFeeCurrency("EUR")
        vm.type("2")
        vm.pickAccount(cash)
        vm.pickAccount(euro)
        vm.saved()
        val id = db.txnDao().allRows().single { it.type == TxnType.TRANSFER }.id
        assertEquals(220L, fee(id)!!.amountMain)
        currencies.update("EUR", BigDecimal("1.2"), SymbolPosition.START, 2)

        val again = vm(id)
        again.state.first { it.loaded }
        again.setNote("Rent")
        again.saved()
        assertEquals(220L, fee(id)!!.amountMain)
        assertEquals(220L, fee(id)!!.accountAmount)
    }
}
