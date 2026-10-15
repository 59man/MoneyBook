package com.moneybook.ui.edit

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.AccountRepository
import com.moneybook.data.AppSettings
import com.moneybook.data.BookmarkRepository
import com.moneybook.data.CategoryRepository
import com.moneybook.data.CopyDateBy
import com.moneybook.data.CurrencyRepository
import com.moneybook.data.DefaultData
import com.moneybook.data.RepeatRepository
import com.moneybook.data.Seeder
import com.moneybook.data.TimeSource
import com.moneybook.data.TxnRepository
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.CategoryType
import com.moneybook.shared.model.TxnType
import com.moneybook.shared.money.SymbolPosition
import com.moneybook.testutil.FakeSettingsRepository
import com.moneybook.testutil.Fixtures
import com.moneybook.testutil.MainDispatcherRule
import com.moneybook.testutil.inMemoryDb
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Edit → Copy loads an entry as a NEW one: Save writes a new row and leaves the original alone. */
@RunWith(AndroidJUnit4::class)
class EditTxnCopyTest {
    @get:Rule val main = MainDispatcherRule()
    private lateinit var db: MoneyBookDatabase
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-10-08T10:15:00Z"), ZoneOffset.UTC))
    private val settings = FakeSettingsRepository(AppSettings(mainCurrency = "USD"))
    private val cash = DefaultData.accountId(AccountGroupType.CASH)
    private val bank = DefaultData.accountId(AccountGroupType.ACCOUNTS)
    private val food = DefaultData.categoryId(CategoryType.EXPENSE, 0)
    private lateinit var currencies: CurrencyRepository
    private val vms = mutableListOf<EditTxnViewModel>()

    @Before fun setUp() {
        db = inMemoryDb()
        currencies = CurrencyRepository(db, settings, time) {}
        runBlocking {
            Seeder(db, time).seedIfEmpty("USD")
            currencies.add("EUR")
            currencies.update("EUR", BigDecimal("1.1"), SymbolPosition.START, 2)
        }
    }

    @After fun tearDown() {
        runBlocking { vms.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        db.close()
    }

    private fun copy(id: String, by: CopyDateBy? = null): EditTxnViewModel = EditTxnViewModel(
        null, null, TxnRepository(db, time), AccountRepository(db, time), CategoryRepository(db, time),
        settings, time, BookmarkRepository(db, time), RepeatRepository(db, time), currencies, copyOf = id, copyBy = by,
    ).also { vms += it }

    private suspend fun EditTxnViewModel.saved() { save(); events.first() }

    private suspend fun source() = db.txnDao().upsert(
        Fixtures.txn("t1", TxnType.EXPENSE, "2026-09-20T08:30:00.000", cash, 18_600, categoryId = food, note = "Lunch").copy(description = "With Eva"),
    )

    @Test fun todayModeDatesTheCopyNowAndCopiesTheFields() = runTest {
        source()
        val s = copy("t1").state.first { it.loaded }
        assertTrue(s.isNew)
        assertEquals(LocalDate.of(2026, 10, 8), s.date)
        assertEquals(LocalTime.of(10, 15), s.time)
        assertEquals(TxnType.EXPENSE, s.type)
        assertEquals(food, s.categoryId)
        assertEquals(cash, s.accountId)
        assertEquals("Lunch", s.note)
        assertEquals("With Eva", s.description)
        assertEquals(18_600L, s.amount.minorUnits())
    }

    @Test fun entryDateModeKeepsTheEntrysOwnDateAndTime() = runTest {
        source()
        settings.state.value = settings.state.value.copy(copyDateBy = CopyDateBy.ENTRY_DATE)
        val s = copy("t1").state.first { it.loaded }
        assertEquals(LocalDate.of(2026, 9, 20), s.date)
        assertEquals(LocalTime.of(8, 30), s.time)
    }

    @Test fun theRoutesChoiceBeatsTheStoredSetting() = runTest {
        source() // the stored setting is still Today, as when the Copy dialog's write has not landed yet
        val s = copy("t1", CopyDateBy.ENTRY_DATE).state.first { it.loaded }
        assertEquals(LocalDate.of(2026, 9, 20), s.date)
        assertEquals(LocalTime.of(8, 30), s.time)
    }

    @Test fun savingCreatesANewEntryAndLeavesTheOriginal() = runTest {
        source()
        val before = db.txnDao().get("t1")
        val vm = copy("t1")
        vm.state.first { it.loaded }
        vm.saved()
        assertEquals(before, db.txnDao().get("t1"))
        val added = db.txnDao().allRows().single { it.id != "t1" }
        assertNotEquals("t1", added.id)
        assertEquals("2026-10-08T10:15:00.000", added.dateTime)
        assertEquals(18_600L, added.amount)
        assertEquals("Lunch", added.note)
        assertEquals("With Eva", added.description)
        assertTrue(!added.deleted)
    }

    @Test fun aCopyIsNotAnInstallmentOrARepeatAndHasNoPhotos() = runTest {
        val photo = "0123456789abcdef0123456789abcdef.jpg"
        TxnRepository(db, time).save(
            Fixtures.txn("t1", TxnType.EXPENSE, "2026-09-20T08:30:00.000", cash, 18_600, categoryId = food)
                .copy(repeatRuleId = "r-t1", installmentTotal = 3, installmentIndex = 1),
            photos = listOf(photo),
        )
        val vm = copy("t1")
        vm.state.first { it.loaded }
        vm.saved()
        val added = db.txnDao().allRows().single { it.id != "t1" }
        assertNull(added.repeatRuleId)
        assertNull(added.installmentTotal)
        assertNull(added.installmentIndex)
        assertEquals(listOf(photo), TxnRepository(db, time).photos("t1"))
        assertEquals(emptyList<String>(), TxnRepository(db, time).photos(added.id))
    }

    @Test fun aSubCurrencyEntryKeepsItsOwnRate() = runTest {
        // 50.00 EUR entered at 1.2 while the current rate is 1.1
        db.txnDao().upsert(Fixtures.txn("t1", TxnType.EXPENSE, "2026-09-20T08:30:00.000", cash, 5_000, categoryId = food).copy(currency = "EUR", amountMain = 6_000))
        val vm = copy("t1")
        val s = vm.state.first { it.loaded }
        assertEquals("EUR", s.currency.code)
        assertEquals(0, BigDecimal("1.2").compareTo(s.rateOverride))
        vm.saved()
        val added = db.txnDao().allRows().single { it.id != "t1" }
        assertEquals("EUR", added.currency)
        assertEquals(6_000L, added.amountMain)
    }

    @Test fun anAccountThatIsNoLongerUsableStaysEmpty() = runTest {
        db.txnDao().upsert(Fixtures.txn("t1", TxnType.EXPENSE, "2026-09-20T08:30:00.000", bank, 18_600, categoryId = food))
        AccountRepository(db, time).delete(bank)
        assertNull(copy("t1").state.first { it.loaded }.accountId)
    }

    @Test fun theChoiceIsStoredAtOnce() = runTest {
        source()
        val vm = copy("t1")
        assertEquals(CopyDateBy.TODAY, vm.copyDateBy.value)
        vm.setCopyDateBy(CopyDateBy.ENTRY_DATE)
        assertEquals(CopyDateBy.ENTRY_DATE, settings.state.first { it.copyDateBy == CopyDateBy.ENTRY_DATE }.copyDateBy)
        assertEquals(CopyDateBy.ENTRY_DATE, vm.copyDateBy.first { it == CopyDateBy.ENTRY_DATE })
    }
}
