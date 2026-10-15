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
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.TxnType
import com.moneybook.testutil.FakeSettingsRepository
import com.moneybook.testutil.MainDispatcherRule
import com.moneybook.testutil.inMemoryDb
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

@RunWith(AndroidJUnit4::class)
class EditTxnPresetTest {
    @get:Rule val main = MainDispatcherRule()
    private lateinit var db: MoneyBookDatabase
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-10-07T10:00:00Z"), ZoneOffset.UTC))
    private val cash = DefaultData.accountId(AccountGroupType.CASH)
    private val bank = DefaultData.accountId(AccountGroupType.ACCOUNTS)
    private val card = DefaultData.accountId(AccountGroupType.CARD)

    @Before fun setUp() {
        db = inMemoryDb()
        runBlocking { Seeder(db, time).seedIfEmpty("CZK") }
    }

    /** The form collects the currencies for as long as it lives; a test's VMs must stop before its database closes. */
    private val vms = mutableListOf<EditTxnViewModel>()

    @After fun tearDown() {
        // Joined, so nothing resumes on Dispatchers.Main while the rule resets it.
        runBlocking { vms.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        db.close()
    }

    private fun vm(preset: TxnPreset, type: TxnType = TxnType.EXPENSE) = EditTxnViewModel(
        null, null, TxnRepository(db, time), AccountRepository(db, time), CategoryRepository(db, time),
        FakeSettingsRepository(AppSettings(mainCurrency = "CZK")), time, BookmarkRepository(db, time), RepeatRepository(db, time),
        CurrencyRepository(db, FakeSettingsRepository(), time) {}, null, null, type, preset,
    ).also { vms += it }

    private val pay = TxnPreset(accountId = bank, toAccountId = card, amount = 3_000, note = "Payment")

    @Test fun `plus from an account preselects it and opens the amount keypad`() = runTest {
        val s = vm(TxnPreset(accountId = cash)).state.first { it.loaded }
        assertEquals(cash, s.accountId)
        assertEquals(Panel.AMOUNT, s.panel)
        assertEquals(TxnType.EXPENSE, s.type)
    }

    @Test fun `pay prefills a transfer and saves it`() = runTest {
        val vm = vm(pay, TxnType.TRANSFER)
        val s = vm.state.first { it.loaded }
        assertEquals(bank, s.accountId)
        assertEquals(card, s.toAccountId)
        assertEquals(3_000L, s.amount.minorUnits())
        assertEquals("Payment", s.note)
        assertEquals(Panel.NONE, s.panel)
        vm.save()
        vm.events.first()
        val row = db.txnDao().observeRange("2026-01-01T00:00:00.000", "2027-01-01T00:00:00.000").first().single()
        assertEquals(TxnType.TRANSFER, row.type)
        assertEquals(bank, row.accountId)
        assertEquals(card, row.toAccountId)
        assertEquals(3_000L, row.amount)
        assertEquals("Payment", row.note)
    }

    @Test fun `pay with a deleted payment account leaves From empty`() = runTest {
        AccountRepository(db, time).delete(bank)
        val s = vm(pay, TxnType.TRANSFER).state.first { it.loaded }
        assertNull(s.accountId)
        assertEquals(card, s.toAccountId)
    }
}
