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
import com.moneybook.shared.model.CategoryType
import com.moneybook.testutil.FakeSettingsRepository
import com.moneybook.testutil.Fixtures
import com.moneybook.testutil.MainDispatcherRule
import com.moneybook.testutil.inMemoryDb
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
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

/** The form's photo list: at most three, changes are saved only with the entry, order is kept. */
@RunWith(AndroidJUnit4::class)
class EditTxnPhotosTest {
    @get:Rule val main = MainDispatcherRule()
    private lateinit var db: MoneyBookDatabase
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneOffset.UTC))
    private val cash = DefaultData.accountId(AccountGroupType.CASH)
    private val food = DefaultData.categoryId(CategoryType.EXPENSE, 0)
    private val settings = FakeSettingsRepository(AppSettings(mainCurrency = "CZK"))
    private val vms = mutableListOf<EditTxnViewModel>()
    private lateinit var txns: TxnRepository

    @Before fun setUp() {
        db = inMemoryDb()
        txns = TxnRepository(db, time)
        runBlocking { Seeder(db, time).seedIfEmpty("CZK") }
    }

    @After fun tearDown() {
        runBlocking { vms.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        db.close()
    }

    private fun vm(txnId: String? = null, copyOf: String? = null) = EditTxnViewModel(
        txnId, null, txns, AccountRepository(db, time), CategoryRepository(db, time), settings, time, BookmarkRepository(db, time),
        RepeatRepository(db, time), CurrencyRepository(db, settings, time) {}, copyOf = copyOf,
    ).also { vms += it }

    private suspend fun EditTxnViewModel.loaded() = state.first { it.loaded }

    private suspend fun stored(vararg photos: String) {
        txns.save(Fixtures.txn("t1", com.moneybook.shared.model.TxnType.EXPENSE, "2026-09-20T08:30:00.000", cash, 18_600, categoryId = food), photos.toList())
    }

    @Test fun threePhotosHideTheCameraAndAFourthIsIgnored() = runTest {
        val vm = vm().also { it.loaded() }
        assertTrue(vm.state.value.canAddPhoto)
        listOf("a.jpg", "b.jpg", "c.jpg", "d.jpg", "a.jpg").forEach(vm::addPhoto)
        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), vm.state.value.photos)
        assertFalse(vm.state.value.canAddPhoto)
        vm.removePhoto("b.jpg")
        assertTrue(vm.state.value.canAddPhoto)
    }

    @Test fun editLoadsThePhotosAndAPhotoChangeAloneMakesItDirty() = runTest {
        stored("a.jpg", "b.jpg")
        val vm = vm("t1")
        val s = vm.loaded()
        assertEquals(listOf("a.jpg", "b.jpg"), s.photos)
        assertFalse(s.dirty)
        vm.removePhoto("a.jpg")
        assertTrue(vm.state.value.dirty)
    }

    @Test fun removingAPhotoTakesEffectOnSaveOnly() = runTest {
        stored("a.jpg", "b.jpg")
        val vm = vm("t1").also { it.loaded() }
        vm.removePhoto("a.jpg")
        assertEquals(listOf("a.jpg", "b.jpg"), txns.photos("t1"))
        vm.save()
        assertEquals(EditEvent.Closed, vm.events.first())
        assertEquals(listOf("b.jpg"), txns.photos("t1"))
    }

    @Test fun reSaveKeepsTheOrderAndNewPhotosGoLast() = runTest {
        stored("c.jpg", "a.jpg")
        val vm = vm("t1").also { it.loaded() }
        vm.addPhoto("b.jpg")
        vm.save()
        vm.events.first()
        assertEquals(listOf("c.jpg", "a.jpg", "b.jpg"), txns.photos("t1"))
    }

    @Test fun aNewEntrySavesItsPhotos() = runTest {
        val vm = vm().also { it.loaded() }
        "5".forEach { vm.onKey(KeypadKey.Digit(it)) }
        vm.pickCategory(food)
        vm.pickAccount(cash)
        vm.addPhoto("x.jpg")
        vm.save()
        vm.events.first()
        val id = db.txnDao().observeRange("2026-01-01T00:00:00.000", "2027-01-01T00:00:00.000").first().single().id
        assertEquals(listOf("x.jpg"), txns.photos(id))
    }

    @Test fun saveWaitsForAPhotoStillImporting() = runTest {
        val vm = vm().also { it.loaded() }
        "5".forEach { vm.onKey(KeypadKey.Digit(it)) }
        vm.pickCategory(food)
        vm.pickAccount(cash)
        vm.beginImport()
        vm.save()
        vm.save() // a second tap while waiting does not queue another save
        assertEquals(0, db.txnDao().allRows().size)
        vm.addPhoto("x.jpg")
        vm.endImport()
        assertEquals(EditEvent.Closed, vm.events.first())
        val id = db.txnDao().allRows().single().id
        assertEquals(listOf("x.jpg"), txns.photos(id))
    }

    @Test fun aCopyStartsWithoutPhotos() = runTest {
        stored("a.jpg")
        assertEquals(emptyList<String>(), vm(copyOf = "t1").loaded().photos)
    }
}
