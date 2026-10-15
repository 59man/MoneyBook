package com.moneybook.ui.accounts

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.AccountRepository
import com.moneybook.data.CurrencyRepository
import com.moneybook.data.DefaultData
import com.moneybook.data.Seeder
import com.moneybook.data.TimeSource
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.testutil.FakeSettingsRepository
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountEditCardTest {
    @get:Rule val main = MainDispatcherRule()
    private lateinit var db: MoneyBookDatabase
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-10-07T10:00:00Z"), ZoneOffset.UTC))
    private val card = DefaultData.accountId(AccountGroupType.CARD)
    private val bank = DefaultData.accountId(AccountGroupType.ACCOUNTS)
    private val cardGroup = DefaultData.groupId(AccountGroupType.CARD)
    private val cashGroup = DefaultData.groupId(AccountGroupType.CASH)
    private lateinit var repo: AccountRepository

    @Before fun setUp() {
        db = inMemoryDb()
        repo = AccountRepository(db, time)
        runBlocking { Seeder(db, time).seedIfEmpty("CZK") }
    }

    @After fun tearDown() = db.close()

    private fun vm(id: String? = null) = AccountEditViewModel(id, repo, FakeSettingsRepository(), time, CurrencyRepository(db, FakeSettingsRepository(), time) {})
    private fun AccountEditViewModel.type(digits: String) = digits.forEach { onKey(KeypadKey.Digit(it)) }

    @Test fun `a card shows Realbyte's default preview`() = runTest {
        val vm = vm()
        vm.state.first { it.loaded }
        vm.pickGroup(cardGroup)
        val s = vm.state.value
        assertTrue(s.isCard)
        assertEquals("09/01  ~  09/30 (Pay: 10/01)", s.preview!!.payable)
        assertEquals("10/01  ~  10/31 (Pay: 11/01)", s.preview!!.outstanding)
    }

    @Test fun `typed days update the preview and are saved`() = runTest {
        val vm = vm(card)
        vm.state.first { it.loaded }
        vm.openSettlement(); vm.type("20"); vm.closePanel()
        vm.openPaymentDay(); vm.type("15"); vm.closePanel()
        vm.openPaymentAccount(); vm.pickPaymentAccount(bank)
        val s = vm.state.value
        assertEquals("08/20  ~  09/19 (Pay: 10/15)", s.preview!!.payable)
        assertEquals("09/20  ~  10/19 (Pay: 11/15)", s.preview!!.outstanding)
        assertEquals("Accounts", s.paymentAccountName)
        vm.save()
        vm.events.first()
        val saved = repo.get(card)!!
        assertEquals(20, saved.statementDay)
        assertEquals(15, saved.paymentDay)
        assertEquals(bank, saved.paymentAccountId)
    }

    @Test fun `reopening a day keypad replaces the day`() = runTest {
        val vm = vm(card)
        vm.state.first { it.loaded }
        vm.openSettlement(); vm.type("2"); vm.closePanel()
        vm.openSettlement(); vm.type("5")
        assertEquals(5, vm.state.value.statementDay.value)
    }

    @Test fun `payment choices leave out the card itself`() = runTest {
        val s = vm(card).state.first { it.loaded }
        assertFalse(s.paymentChoices.any { it.id == card })
        assertTrue(s.paymentChoices.any { it.id == bank })
    }

    @Test fun `non card group hides the card fields and keeps the stored days`() = runTest {
        db.accountDao().upsert(db.accountDao().get(card)!!.copy(statementDay = 20, paymentDay = 15))
        val vm = vm(card)
        vm.state.first { it.loaded }
        vm.pickGroup(cashGroup)
        assertFalse(vm.state.value.isCard)
        assertNull(vm.state.value.preview)
        vm.save()
        vm.events.first()
        assertEquals(20, repo.get(card)!!.statementDay)
        assertEquals(cashGroup, repo.get(card)!!.groupId)
    }
}
