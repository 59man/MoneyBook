package com.moneybook.ui.accounts

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.AccountRepository
import com.moneybook.data.CurrencyRepository
import com.moneybook.data.DefaultData
import com.moneybook.data.Seeder
import com.moneybook.data.TimeSource
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.ArchiveState
import com.moneybook.shared.model.TxnType
import com.moneybook.testutil.FakeSettingsRepository
import com.moneybook.testutil.Fixtures
import com.moneybook.testutil.MainDispatcherRule
import com.moneybook.testutil.inMemoryDb
import com.moneybook.ui.edit.KeypadKey
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountEditViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private lateinit var db: MoneyBookDatabase
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneOffset.UTC))
    private val cash = DefaultData.accountId(AccountGroupType.CASH)
    private val savingsGroup = DefaultData.groupId(AccountGroupType.SAVINGS)
    private lateinit var repo: AccountRepository

    @Before fun setUp() {
        db = inMemoryDb()
        repo = AccountRepository(db, time)
        runBlocking {
            Seeder(db, time).seedIfEmpty("CZK")
            db.txnDao().upsert(Fixtures.txn("e", TxnType.EXPENSE, "2026-09-02T10:00:00.000", cash, 10_000))
        }
    }

    @After fun tearDown() = db.close()

    private fun vm(id: String? = null) = AccountEditViewModel(id, repo, FakeSettingsRepository(), time, CurrencyRepository(db, FakeSettingsRepository(), time) {})
    private fun AccountEditViewModel.type(digits: String) = digits.forEach { onKey(KeypadKey.Digit(it)) }

    @Test fun newAccountStartsWithGroupPickerAndSavesInitialBalance() = runTest {
        val vm = vm()
        val s = vm.state.first { it.loaded }
        assertTrue(s.isNew)
        assertTrue(s.showGroupPicker)
        assertEquals(11, s.groups.size)
        vm.pickGroup(savingsGroup)
        vm.setName("  Spořák ")
        vm.type("25000")
        vm.save()
        assertEquals(AccountEditEvent.Saved, vm.events.first())
        val created = repo.accounts().single { it.name == "Spořák" }
        assertEquals(savingsGroup, created.groupId)
        assertEquals(2_500_000L, created.initialBalance)
        assertEquals("CZK", created.currency)
        assertEquals(2_500_000L, repo.balanceOf(created.id))
    }

    @Test fun doubleTappedSaveCreatesOneAccount() = runTest {
        val vm = vm()
        vm.state.first { it.loaded }
        vm.pickGroup(savingsGroup)
        vm.setName("Spořák")
        vm.save()
        vm.save()
        repeat(2) { assertEquals(AccountEditEvent.Saved, vm.events.first()) }
        assertEquals(1, repo.accounts().count { it.name == "Spořák" })
    }

    @Test fun editingAmountSetsTheCurrentBalance() = runTest {
        val vm = vm(cash)
        val s = vm.state.first { it.loaded }
        assertFalse(s.isNew)
        assertEquals("-100", s.amount.text)
        repeat(4) { vm.onKey(KeypadKey.Backspace) }
        vm.type("4151")
        vm.save()
        assertEquals(AccountEditEvent.Saved, vm.events.first())
        assertEquals(415_100L, repo.balanceOf(cash))
        assertEquals(1, db.txnDao().count())
    }

    @Test fun validation() = runTest {
        val vm = vm()
        vm.state.first { it.loaded }
        vm.save()
        assertEquals(AccountEditEvent.Error("Please select a group."), vm.events.first())
        vm.pickGroup(savingsGroup)
        vm.save()
        assertEquals(AccountEditEvent.Error("Please enter a name."), vm.events.first())
    }

    @Test fun deleteMovesTheAccountToDeletedAccountsAndKeepsItsTransactions() = runTest {
        val vm = vm(cash)
        vm.state.first { it.loaded }
        val before = db.txnDao().count()
        vm.delete()
        assertEquals(AccountEditEvent.Deleted, vm.events.first())
        assertEquals(ArchiveState.DELETED, db.accountDao().get(cash)!!.state)
        assertEquals(before, db.txnDao().count())
    }
}
