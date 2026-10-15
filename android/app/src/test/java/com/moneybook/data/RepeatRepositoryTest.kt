package com.moneybook.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moneybook.data.db.MoneyBookDatabase
import com.moneybook.data.db.TxnEntity
import com.moneybook.shared.model.AccountGroupType
import com.moneybook.shared.model.CategoryType
import com.moneybook.shared.model.RepeatFrequency
import com.moneybook.shared.model.TxnType
import com.moneybook.shared.money.Fx
import com.moneybook.testutil.Fixtures
import com.moneybook.testutil.inMemoryDb
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RepeatRepositoryTest {
    private lateinit var db: MoneyBookDatabase
    private lateinit var repeats: RepeatRepository
    private val time = TimeSource(Clock.fixed(Instant.parse("2026-10-06T10:00:00Z"), ZoneOffset.UTC))
    private val today = LocalDate.of(2026, 10, 6)
    private val cash = DefaultData.accountId(AccountGroupType.CASH)
    private val food = DefaultData.categoryId(CategoryType.EXPENSE, 0)
    private lateinit var rent: TxnEntity

    @Before fun setUp() {
        db = inMemoryDb()
        repeats = RepeatRepository(db, time)
        runBlocking {
            Seeder(db, time).seedIfEmpty("CZK")
            rent = Fixtures.txn("t", TxnType.EXPENSE, "2026-07-31T09:30:00.000", cash, 10_000, categoryId = food, note = "Nájem")
                .copy(repeatRuleId = "r")
            db.txnDao().upsert(rent)
        }
    }

    @After fun tearDown() = db.close()

    private suspend fun live() = db.txnDao().allRows().filter { !it.deleted }.sortedBy { it.dateTime }

    @Test fun aSubCurrencyRuleConvertsEachOccurrenceAtTheCurrentRate() = runTest {
        val fxRepeats = RepeatRepository(db, time) { Fx("CZK", mapOf("EUR" to BigDecimal("25"))) }
        val euro = rent.copy(id = "e", repeatRuleId = "re", amount = 1_000, currency = "EUR", amountMain = 25_000, accountAmount = 25_000)
        db.txnDao().upsert(euro)
        fxRepeats.create("re", euro, RepeatFrequency.MONTHLY)
        fxRepeats.catchUp(today)
        val added = db.txnDao().get(RepeatRepository.occurrenceId("re", LocalDate.of(2026, 8, 31)))!!
        assertEquals(1_000L, added.amount)
        assertEquals("EUR", added.currency)
        assertEquals(25_000L, added.amountMain)
        assertEquals(25_000L, added.accountAmount)
    }

    @Test fun catchUpAddsTheMissedOccurrencesOnce() = runTest {
        repeats.create("r", rent, RepeatFrequency.MONTHLY)
        assertEquals(2, repeats.catchUp(today))
        val rows = live()
        assertEquals(listOf("2026-07-31T09:30:00.000", "2026-08-31T09:30:00.000", "2026-09-30T09:30:00.000"), rows.map { it.dateTime })
        assertEquals(RepeatRepository.occurrenceId("r", LocalDate.of(2026, 8, 31)), rows[1].id)
        assertTrue(rows.all { it.repeatRuleId == "r" && it.note == "Nájem" && it.amount == 10_000L && it.categoryId == food })
        assertEquals("2026-09-30", repeats.get("r")!!.lastDate)
        assertEquals(0, repeats.catchUp(today))
    }

    @Test fun aDeletedOccurrenceNeverComesBack() = runTest {
        repeats.create("r", rent, RepeatFrequency.MONTHLY)
        repeats.catchUp(today)
        val august = RepeatRepository.occurrenceId("r", LocalDate.of(2026, 8, 31))
        TxnRepository(db, time).delete(august)
        // Another device that has not seen the August occurrence yet syncs the rule back to its start.
        repeats.save(repeats.get("r")!!.copy(lastDate = "2026-07-31"))
        assertEquals(0, repeats.catchUp(today))
        assertTrue(db.txnDao().get(august)!!.deleted)
    }

    @Test fun anInstallmentPlanNumbersItsPartsAndStops() = runTest {
        val first = rent.copy(amount = 33_334, amountMain = 33_334, installmentTotal = 3, installmentIndex = 1)
        db.txnDao().upsert(first)
        repeats.create("r", first, RepeatFrequency.MONTHLY, installments = 3, laterAmount = 33_333)
        assertEquals(2, repeats.catchUp(LocalDate.of(2027, 1, 1)))
        val rows = live()
        assertEquals(listOf(1, 2, 3), rows.map { it.installmentIndex })
        assertEquals(listOf(3, 3, 3), rows.map { it.installmentTotal })
        assertEquals(listOf(33_334L, 33_333L, 33_333L), rows.map { it.amount })
        assertEquals(0, repeats.catchUp(LocalDate.of(2028, 1, 1)))
    }

    @Test fun aHiddenAccountOrDeletedCategoryPausesTheRule() = runTest {
        repeats.create("r", rent, RepeatFrequency.MONTHLY)
        val accounts = AccountRepository(db, time)
        accounts.setHidden(cash, true)
        assertEquals(0, repeats.catchUp(today))
        accounts.setHidden(cash, false)
        CategoryRepository(db, time).delete(food)
        assertEquals(0, repeats.catchUp(today))
        assertEquals("2026-07-31", repeats.get("r")!!.lastDate)
    }

    @Test fun aRuleOnADeletedAccountOrGroupWaitsAndCatchesUpAfterRestore() = runTest {
        repeats.create("r", rent, RepeatFrequency.MONTHLY)
        val accounts = AccountRepository(db, time)
        accounts.delete(cash)
        assertEquals(0, repeats.catchUp(today))
        accounts.restore(cash)
        accounts.deleteGroup(DefaultData.groupId(AccountGroupType.CASH))
        assertEquals(0, repeats.catchUp(today))
        accounts.restoreGroup(DefaultData.groupId(AccountGroupType.CASH))
        assertEquals(2, repeats.catchUp(today))
    }

    @Test fun deletingTheRuleKeepsItsEntriesAndDeletingTheAccountKeepsTheRule() = runTest {
        repeats.create("r", rent, RepeatFrequency.MONTHLY)
        repeats.catchUp(today)
        repeats.delete("r")
        assertNull(repeats.get("r"))
        assertEquals(3, live().size)
        repeats.create("r2", rent, RepeatFrequency.WEEKLY)
        AccountRepository(db, time).delete(cash)
        assertEquals(false, repeats.get("r2") == null)
    }

    @Test fun rowsCarryTheNamesRepeatSettingShows() = runTest {
        repeats.create("r", rent, RepeatFrequency.MONTHLY)
        val row = repeats.observeRows().first().single()
        assertEquals("Cash", row.accountName)
        assertEquals("🍜 Food", row.categoryName)
        assertEquals(RepeatFrequency.MONTHLY, row.frequency)
        assertEquals("2026-07-31", row.startDate)
    }

    @Test fun repeatSettingShowsNoNameForADeletedCategory() = runTest {
        repeats.create("r", rent, RepeatFrequency.MONTHLY)
        CategoryRepository(db, time).delete(food)
        assertNull(repeats.observeRows().first().single().categoryName)
    }

    @Test fun aRuleWithABadDateIsSkippedInsteadOfStoppingTheRest() = runTest {
        repeats.create("r", rent, RepeatFrequency.MONTHLY)
        db.repeatRuleDao().upsert(repeats.get("r")!!.copy(id = "bad", lastDate = "not a date"))
        assertEquals(2, repeats.catchUp(today))
    }
}
